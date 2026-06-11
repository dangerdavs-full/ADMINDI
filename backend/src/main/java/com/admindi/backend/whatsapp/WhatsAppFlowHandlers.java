package com.admindi.backend.whatsapp;

import com.admindi.backend.model.*;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.PropertyFileRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.service.FileStorageService;
import com.admindi.backend.service.MaintenanceWorkflowService;
import com.admindi.backend.service.TwilioWhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Handlers de los flujos específicos del bot (ticket, comprobante, consulta).
 *
 * Esta clase agrupa la lógica conversacional delegando la ejecución de
 * acciones de negocio a los servicios existentes:
 *  - Tickets: {@link MaintenanceWorkflowService#createTicketWithOwnerAuth}
 *  - Comprobantes: integración con OCR (Fase 3) y {@code LedgerService}
 *  - Consulta: {@link InvoiceRepository} directo para evitar dependencia de SecurityContext
 *
 * Todas las interacciones con la base de datos que requieren un actor autenticado
 * pasan por {@link BotSecurityBridge#runAs} para simular el contexto de seguridad
 * del user TENANT sin exponerlo a otros threads.
 */
@Service
public class WhatsAppFlowHandlers {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppFlowHandlers.class);

    private final WhatsAppSessionService sessions;
    private final TwilioWhatsAppService twilio;
    private final TenantProfileRepository tenantProfileRepo;
    private final PropertyRepository propertyRepo;
    private final InvoiceRepository invoiceRepo;
    private final PropertyFileRepository fileRepo;
    private final FileStorageService storageService;
    private final WhatsAppMediaDownloader mediaDownloader;
    private final BotSecurityBridge securityBridge;
    private final MaintenanceWorkflowService maintenanceWorkflow;
    private final WhatsAppProofFlow proofFlow;
    private final WhatsAppPropertyContext propertyContext;

    @Autowired
    public WhatsAppFlowHandlers(WhatsAppSessionService sessions,
                                 TwilioWhatsAppService twilio,
                                 TenantProfileRepository tenantProfileRepo,
                                 PropertyRepository propertyRepo,
                                 InvoiceRepository invoiceRepo,
                                 PropertyFileRepository fileRepo,
                                 FileStorageService storageService,
                                 WhatsAppMediaDownloader mediaDownloader,
                                 BotSecurityBridge securityBridge,
                                 MaintenanceWorkflowService maintenanceWorkflow,
                                 WhatsAppProofFlow proofFlow,
                                 WhatsAppPropertyContext propertyContext) {
        this.sessions = sessions;
        this.twilio = twilio;
        this.tenantProfileRepo = tenantProfileRepo;
        this.propertyRepo = propertyRepo;
        this.invoiceRepo = invoiceRepo;
        this.fileRepo = fileRepo;
        this.storageService = storageService;
        this.mediaDownloader = mediaDownloader;
        this.securityBridge = securityBridge;
        this.maintenanceWorkflow = maintenanceWorkflow;
        this.proofFlow = proofFlow;
        this.propertyContext = propertyContext;
    }

    // ─────────────────────────────────────────────────────────────────────
    // FLOW 1 — COMPROBANTE SPEI
    // ─────────────────────────────────────────────────────────────────────

    public void startProofFlow(String fromE164, UserEntity user,
                                WhatsappConversationStateEntity session) {
        if (propertyContext.needsSelection(user, session)) {
            propertyContext.promptSelection(fromE164, user, session);
            return;
        }
        List<InvoiceEntity> pending = findPendingInvoices(user, session);
        if (pending.isEmpty()) {
            String propHint = propertyContext.selectedPropertyLabel(session);
            twilio.sendFreeformWhatsApp(fromE164,
                    "No encuentro facturas pendientes"
                            + (propHint != null ? " en *" + propHint + "*" : " en tu expediente")
                            + ". Si crees que es un error, contacta a tu arrendador. Escribe MENU para volver.");
            return;
        }

        InvoiceEntity target = pending.get(0);
        String propLabel = propertyContext.selectedPropertyLabel(session);

        sessions.transition(session, WhatsAppBotState.PROOF_WAITING_IMAGE, Map.of(
                "invoiceId", target.getId(),
                "invoiceMonth", target.getMonthYear()));

        twilio.sendFreeformWhatsApp(fromE164,
                "Perfecto"
                        + (propLabel != null ? " (*" + propLabel + "*)" : "")
                        + ". Para tu renta de " + prettyMonth(target.getMonthYear())
                        + " (pendiente: $" + outstanding(target).toPlainString() + " MXN) tienes dos opciones:\n\n"
                        + "📷 Envía la *foto o PDF* del comprobante SPEI (lo leemos automáticamente).\n\n"
                        + "⌨️ Si no puedes enviar imagen, escribe *MANUAL* y te pediré paso a paso:\n"
                        + "clave de rastreo, monto, fecha y banco emisor para validar con Banxico.\n\n"
                        + "Escribe MENU para cancelar.");
    }

    public void handleProofWaitingImage(String fromE164, UserEntity user,
                                         WhatsappConversationStateEntity session,
                                         String body, List<WhatsAppBotOrchestrator.IncomingMedia> media) {
        if (media.isEmpty()) {
            if (proofFlow.isManualEntryKeyword(body)) {
                proofFlow.startManualEntry(fromE164, user, session);
                return;
            }
            twilio.sendFreeformWhatsApp(fromE164,
                    "Todavía no recibo la imagen. Envíame la foto o PDF del comprobante, "
                            + "o escribe *MANUAL* para capturar los datos escribiendo "
                            + "(clave de rastreo, monto, fecha y banco). Escribe MENU para cancelar.");
            return;
        }

        // Tomamos la primera imagen (para comprobantes uno basta).
        WhatsAppBotOrchestrator.IncomingMedia first = media.get(0);
        Optional<WhatsAppMediaDownloader.Media> downloaded =
                mediaDownloader.download(first.url(), first.contentType());
        if (downloaded.isEmpty()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "No pude descargar la imagen (formato o tamaño no permitido). " +
                    "Intenta con una foto JPG/PNG o un PDF menor a 5MB.");
            return;
        }

        Map<String, Object> ctx = sessions.getContext(session);
        String invoiceId = (String) ctx.get("invoiceId");
        if (invoiceId == null) {
            logger.warn("[BOT-PROOF] missing invoiceId in context for user={}", user.getId());
            twilio.sendFreeformWhatsApp(fromE164, "Sesión inválida. Escribe MENU para reiniciar.");
            return;
        }

        // Delegamos al flujo de comprobante: OCR + creación del proof + CEP.
        proofFlow.processIncomingProof(fromE164, user, session, invoiceId, downloaded.get());
    }

    public void handleProofConfirmingData(String fromE164, UserEntity user,
                                           WhatsappConversationStateEntity session, String body) {
        // Delegamos a proofFlow que conoce el contexto de confirmación.
        proofFlow.processConfirmation(fromE164, user, session, body);
    }

    public void handleProofManualEntry(String fromE164, UserEntity user,
                                        WhatsappConversationStateEntity session, String body) {
        proofFlow.handleManualStep(fromE164, user, session, body);
    }

    // ─────────────────────────────────────────────────────────────────────
    // FLOW 2 — TICKET DE MANTENIMIENTO
    // ─────────────────────────────────────────────────────────────────────

    public void startTicketFlow(String fromE164, UserEntity user,
                                 WhatsappConversationStateEntity session) {
        if (propertyContext.needsSelection(user, session)) {
            propertyContext.promptSelection(fromE164, user, session);
            return;
        }

        Optional<TenantProfileEntity> selected = propertyContext.selectedProfile(session, user);
        List<TenantProfileEntity> profiles = propertyContext.activeProfiles(user);
        if (profiles.isEmpty()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "No tienes un inmueble vigente registrado. Contacta a tu arrendador.");
            return;
        }

        sessions.transition(session, WhatsAppBotState.TICKET_WAITING_DESC, Map.of(
                "ticketPhotoFileIds", new ArrayList<String>()));

        if (selected.isPresent()) {
            TenantProfileEntity profile = selected.get();
            sessions.putContext(session, "tenantProfileId", profile.getId());
            sessions.putContext(session, "propertyId", profile.getPropertyId());
            sessions.putContext(session, "ownerId", profile.getOwnerId());
            sessions.save(session);
            PropertyEntity prop = propertyRepo.findById(profile.getPropertyId()).orElse(null);
            String name = prop != null && prop.getName() != null ? prop.getName() : "tu inmueble";
            twilio.sendFreeformWhatsApp(fromE164,
                    "Cuéntame qué necesitas reportar en *" + name + "* "
                            + "(ej: 'fuga en la llave del baño principal'). "
                            + "El dueño revisará y decidirá el proveedor.");
        } else {
            // Múltiples inmuebles: primero pide elegir cuál.
            sessions.transition(session, WhatsAppBotState.TICKET_WAITING_PROPERTY, Map.of());
            StringBuilder sb = new StringBuilder();
            sb.append("Tienes varios inmuebles registrados. ¿Para cuál es el reporte?\n\n");
            for (int i = 0; i < profiles.size(); i++) {
                TenantProfileEntity p = profiles.get(i);
                PropertyEntity prop = propertyRepo.findById(p.getPropertyId()).orElse(null);
                sb.append(i + 1).append(") ")
                  .append(prop != null ? prop.getName() : "(inmueble " + (i + 1) + ")")
                  .append("\n");
            }
            sb.append("\nResponde con el número.");
            // Guardamos la lista de IDs en orden para poder resolver el número.
            List<String> profileIds = profiles.stream().map(TenantProfileEntity::getId).toList();
            sessions.putContext(session, "propertyChoiceProfileIds", profileIds);
            sessions.save(session);
            twilio.sendFreeformWhatsApp(fromE164, sb.toString());
        }
    }

    public void handleTicketWaitingProperty(String fromE164, UserEntity user,
                                             WhatsappConversationStateEntity session, String body) {
        Map<String, Object> ctx = sessions.getContext(session);
        Object raw = ctx.get("propertyChoiceProfileIds");
        List<?> choices = raw instanceof List ? (List<?>) raw : List.of();
        int idx = parseOption(body) - 1;
        if (idx < 0 || idx >= choices.size()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "No reconozco esa opción. Por favor responde con el número del inmueble.");
            return;
        }
        String profileId = (String) choices.get(idx);
        TenantProfileEntity profile = tenantProfileRepo.findById(profileId).orElse(null);
        if (profile == null || !user.getId().equals(profile.getUserId())) {
            twilio.sendFreeformWhatsApp(fromE164, "Selección inválida. Escribe MENU.");
            return;
        }

        sessions.putContext(session, "tenantProfileId", profile.getId());
        sessions.putContext(session, "propertyId", profile.getPropertyId());
        sessions.putContext(session, "ownerId", profile.getOwnerId());
        sessions.transition(session, WhatsAppBotState.TICKET_WAITING_DESC, Map.of());

        twilio.sendFreeformWhatsApp(fromE164,
                "Listo. Cuéntame brevemente qué necesitas reportar de mantenimiento.");
    }

    public void handleTicketWaitingDesc(String fromE164, UserEntity user,
                                         WhatsappConversationStateEntity session, String body) {
        if (body == null || body.trim().length() < 8) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Por favor dame un poco más de detalle (mínimo 8 caracteres). " +
                    "Ejemplo: 'fuga en la llave del baño principal desde ayer'.");
            return;
        }

        sessions.putContext(session, "ticketDescription", body.trim());
        sessions.transition(session, WhatsAppBotState.TICKET_WAITING_URGENCY, Map.of());
        twilio.sendFreeformWhatsApp(fromE164,
                "Entendido. ¿Qué tan urgente es?\n\n" +
                "1) Baja (puede esperar días)\n" +
                "2) Normal\n" +
                "3) Alta (mismo día)\n" +
                "4) Urgente (fuga, sin luz, riesgo)\n\n" +
                "Responde con el número.");
    }

    public void handleTicketWaitingUrgency(String fromE164, UserEntity user,
                                            WhatsappConversationStateEntity session, String body) {
        String urgency = switch (parseOption(body)) {
            case 1 -> "LOW";
            case 2 -> "NORMAL";
            case 3 -> "HIGH";
            case 4 -> "URGENT";
            default -> null;
        };
        if (urgency == null) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "No reconozco esa opción. Responde 1, 2, 3 o 4.");
            return;
        }

        sessions.putContext(session, "ticketUrgency", urgency);
        sessions.transition(session, WhatsAppBotState.TICKET_WAITING_PHOTOS, Map.of());
        twilio.sendFreeformWhatsApp(fromE164,
                "Ahora puedes enviarme hasta 5 fotos del problema. " +
                "Cuando termines (o si no tienes fotos), escribe LISTO.");
    }

    public void handleTicketWaitingPhotos(String fromE164, UserEntity user,
                                           WhatsappConversationStateEntity session,
                                           String body, List<WhatsAppBotOrchestrator.IncomingMedia> media) {
        Map<String, Object> ctx = sessions.getContext(session);
        @SuppressWarnings("unchecked")
        List<String> photoIds = ctx.get("ticketPhotoFileIds") instanceof List
                ? new ArrayList<>((List<String>) ctx.get("ticketPhotoFileIds"))
                : new ArrayList<>();
        String propertyId = (String) ctx.get("propertyId");
        String ownerId = (String) ctx.get("ownerId");

        // Procesa fotos que vinieron en ESTE mensaje (el user puede mandarlas
        // en varios mensajes; cada vez que llega un batch lo acumulamos).
        int added = 0;
        for (WhatsAppBotOrchestrator.IncomingMedia m : media) {
            if (photoIds.size() >= 5) break;
            Optional<WhatsAppMediaDownloader.Media> down =
                    mediaDownloader.download(m.url(), m.contentType());
            if (down.isEmpty()) continue;
            try {
                String fileId = saveTicketPhoto(user, propertyId, ownerId, down.get());
                if (fileId != null) {
                    photoIds.add(fileId);
                    added++;
                }
            } catch (Exception ex) {
                logger.warn("[BOT-TICKET] failed to save photo for user={}: {}",
                        user.getId(), ex.getClass().getSimpleName());
            }
        }

        sessions.putContext(session, "ticketPhotoFileIds", photoIds);
        sessions.save(session);

        boolean done = body != null && body.trim().equalsIgnoreCase("listo");
        if (added > 0 && !done) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Recibí " + added + " foto(s). Llevas " + photoIds.size() + "/5. " +
                    "Envía más o escribe LISTO para terminar.");
            return;
        }
        if (!done) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Envíame las fotos o escribe LISTO cuando estés listo.");
            return;
        }

        // Pasa a confirmación.
        sessions.transition(session, WhatsAppBotState.TICKET_CONFIRMING, Map.of());
        String desc = (String) ctx.get("ticketDescription");
        String urgency = (String) ctx.get("ticketUrgency");
        PropertyEntity prop = propertyId != null
                ? propertyRepo.findById(propertyId).orElse(null) : null;
        twilio.sendFreeformWhatsApp(fromE164,
                "Voy a registrar este ticket:\n\n" +
                "Inmueble: " + (prop != null ? prop.getName() : "(sin nombre)") + "\n" +
                "Descripción: " + desc + "\n" +
                "Urgencia: " + prettyUrgency(urgency) + "\n" +
                "Fotos: " + photoIds.size() + "\n\n" +
                "Escribe SI para confirmar o NO para cancelar.");
    }

    public void handleTicketConfirming(String fromE164, UserEntity user,
                                        WhatsappConversationStateEntity session, String body) {
        String yesno = body == null ? "" : body.trim().toLowerCase(Locale.ROOT);
        if (yesno.startsWith("n")) {
            sessions.transition(session, WhatsAppBotState.MENU, Map.of());
            twilio.sendFreeformWhatsApp(fromE164,
                    "Ticket cancelado. Escribe MENU para ver las opciones.");
            return;
        }
        if (!yesno.startsWith("s") && !yesno.startsWith("y")) {
            twilio.sendFreeformWhatsApp(fromE164, "Responde SI para confirmar o NO para cancelar.");
            return;
        }

        Map<String, Object> ctx = sessions.getContext(session);
        String ownerId = (String) ctx.get("ownerId");
        String propertyId = (String) ctx.get("propertyId");
        String tenantProfileId = (String) ctx.get("tenantProfileId");
        String desc = (String) ctx.get("ticketDescription");
        String urgency = (String) ctx.get("ticketUrgency");
        @SuppressWarnings("unchecked")
        List<String> photoIds = ctx.get("ticketPhotoFileIds") instanceof List
                ? (List<String>) ctx.get("ticketPhotoFileIds")
                : List.of();

        String title = summarizeToTitle(desc);

        try {
            MaintenanceTicketEntity ticket = securityBridge.runAs(user, () ->
                    maintenanceWorkflow.createTicketWithOwnerAuth(
                            ownerId, propertyId, tenantProfileId,
                            title, desc, urgency, photoIds));
            sessions.transition(session, WhatsAppBotState.MENU, Map.of());
            twilio.sendFreeformWhatsApp(fromE164,
                    "Listo. Tu ticket quedó registrado (" + ticket.getId().substring(0, 8) + "). " +
                    "El dueño ya recibió la notificación y decidirá al proveedor. " +
                    "Te avisaré cuando haya novedades.\n\nEscribe MENU para volver.");
        } catch (Exception ex) {
            logger.warn("[BOT-TICKET] createTicketWithOwnerAuth failed for user={}: {}",
                    user.getId(), ex.getClass().getSimpleName());
            sessions.transition(session, WhatsAppBotState.MENU, Map.of());
            twilio.sendFreeformWhatsApp(fromE164,
                    "No pude crear el ticket (" + safeBrief(ex.getMessage()) + "). " +
                    "Intenta de nuevo desde MENU o reporta directamente al dueño.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FLOW 3 — CONSULTA DE SALDO Y PAGOS
    // ─────────────────────────────────────────────────────────────────────

    public void showBalance(String fromE164, UserEntity user,
                             WhatsappConversationStateEntity session) {
        if (propertyContext.needsSelection(user, session)) {
            propertyContext.promptSelection(fromE164, user, session);
            return;
        }

        List<InvoiceEntity> all = new ArrayList<>();
        Optional<TenantProfileEntity> selected = propertyContext.selectedProfile(session, user);
        if (selected.isPresent()) {
            all.addAll(invoiceRepo.findByTenantProfileId(selected.get().getId()));
        } else {
            for (TenantProfileEntity p : propertyContext.activeProfiles(user)) {
                all.addAll(invoiceRepo.findByTenantProfileId(p.getId()));
            }
        }
        all.sort(Comparator.comparing(InvoiceEntity::getMonthYear).reversed());

        if (all.isEmpty()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Todavía no tienes facturas en tu expediente.\n\nEscribe MENU para volver.");
            sessions.transition(session, WhatsAppBotState.QUERY_VIEWING, Map.of());
            return;
        }

        BigDecimal owed = all.stream()
                .filter(inv -> !"PAID".equalsIgnoreCase(inv.getSettlementStatus()))
                .filter(inv -> !"VOID".equalsIgnoreCase(inv.getStatus()))
                .filter(inv -> !"CANCELLED".equalsIgnoreCase(inv.getSettlementStatus()))
                .map(this::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder();
        String propLabel = propertyContext.selectedPropertyLabel(session);
        sb.append("Tu estado de cuenta");
        if (propLabel != null) {
            sb.append(" — *").append(propLabel).append("*");
        }
        sb.append(":\n\n");
        sb.append("Por cobrar: $").append(owed.toPlainString()).append(" MXN\n\n");
        sb.append("Últimas facturas:\n");
        int shown = 0;
        for (InvoiceEntity inv : all) {
            if (shown >= 5) break;
            sb.append("  • ").append(prettyMonth(inv.getMonthYear())).append(" — ");
            String status = inv.getSettlementStatus() != null ? inv.getSettlementStatus() : inv.getStatus();
            sb.append(prettyStatus(status));
            if (!"PAID".equalsIgnoreCase(status) && !"CANCELLED".equalsIgnoreCase(status)) {
                sb.append(" (pendiente $").append(outstanding(inv).toPlainString()).append(")");
            }
            sb.append("\n");
            shown++;
        }
        sb.append("\nPara pagar, sube tu comprobante SPEI desde el menú. Escribe MENU para volver.");
        twilio.sendFreeformWhatsApp(fromE164, sb.toString());
        sessions.transition(session, WhatsAppBotState.QUERY_VIEWING, Map.of());
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private List<InvoiceEntity> findPendingInvoices(UserEntity user,
                                                     WhatsappConversationStateEntity session) {
        List<InvoiceEntity> out = new ArrayList<>();
        Optional<TenantProfileEntity> selected = propertyContext.selectedProfile(session, user);
        List<TenantProfileEntity> profiles = selected
                .map(List::of)
                .orElseGet(() -> propertyContext.activeProfiles(user));
        for (TenantProfileEntity p : profiles) {
            for (InvoiceEntity inv : invoiceRepo.findByTenantProfileId(p.getId())) {
                String s = inv.getSettlementStatus() != null ? inv.getSettlementStatus() : inv.getStatus();
                if (s == null) continue;
                if ("PENDING".equalsIgnoreCase(inv.getStatus())
                        || "LATE".equalsIgnoreCase(inv.getStatus())
                        || "PARTIALLY_PAID".equalsIgnoreCase(s)) {
                    out.add(inv);
                }
            }
        }
        out.sort(Comparator.comparing(InvoiceEntity::getMonthYear));
        return out;
    }

    private BigDecimal outstanding(InvoiceEntity inv) {
        if (inv.getOutstandingAmount() != null) return inv.getOutstandingAmount();
        BigDecimal total = inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal paid = inv.getPaidAmount() != null ? inv.getPaidAmount() : BigDecimal.ZERO;
        return total.subtract(paid).max(BigDecimal.ZERO);
    }

    /**
     * Guarda una foto del ticket en {@code property_files} para que el dueño
     * pueda verla desde el expediente del inmueble al autorizar.
     */
    private String saveTicketPhoto(UserEntity uploader, String propertyId, String ownerId,
                                    WhatsAppMediaDownloader.Media media) {
        String path = storageService.storeBytes(media.bytes(), null, media.mimeType(),
                "properties/" + propertyId + "/maintenance");

        PropertyFileEntity entity = new PropertyFileEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setPropertyId(propertyId);
        entity.setCategory("PHOTO");
        entity.setFileName("bot-ticket-" + LocalDateTime.now() + extFor(media.mimeType()));
        entity.setFilePath(path);
        entity.setContentType(media.mimeType());
        entity.setSizeBytes((long) media.bytes().length);
        entity.setUploadedBy(uploader.getUsername());
        entity.setUploaderRole(uploader.getRole() == null ? null : uploader.getRole().name());
        entity.setLabel("MAINTENANCE");
        entity.setNote("Subida automática vía WhatsApp bot");
        return fileRepo.save(entity).getId();
    }

    private String extFor(String mime) {
        if (mime == null) return "";
        return switch (mime.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }

    private int parseOption(String s) {
        if (s == null) return -1;
        String d = s.replaceAll("\\D", "");
        if (d.isBlank()) return -1;
        try { return Integer.parseInt(d); }
        catch (Exception ex) { return -1; }
    }

    private String summarizeToTitle(String desc) {
        if (desc == null) return "Reporte de mantenimiento";
        String trimmed = desc.trim();
        if (trimmed.length() <= 60) return trimmed;
        return trimmed.substring(0, 57) + "...";
    }

    private String prettyUrgency(String u) {
        if (u == null) return "Normal";
        return switch (u.toUpperCase(Locale.ROOT)) {
            case "LOW" -> "Baja";
            case "NORMAL" -> "Normal";
            case "HIGH" -> "Alta";
            case "URGENT" -> "Urgente";
            default -> u;
        };
    }

    private String prettyStatus(String s) {
        if (s == null) return "pendiente";
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "PAID" -> "pagada";
            case "PARTIALLY_PAID" -> "pago parcial";
            case "PENDING" -> "pendiente";
            case "LATE" -> "vencida";
            case "VOID", "CANCELLED" -> "cancelada";
            case "OVERPAID" -> "pagada (sobrepago)";
            default -> s.toLowerCase(Locale.ROOT);
        };
    }

    private String prettyMonth(String monthYear) {
        if (monthYear == null || !monthYear.matches("\\d{4}-\\d{2}")) return monthYear;
        int year = Integer.parseInt(monthYear.substring(0, 4));
        int month = Integer.parseInt(monthYear.substring(5, 7));
        String[] months = {"enero","febrero","marzo","abril","mayo","junio",
                "julio","agosto","septiembre","octubre","noviembre","diciembre"};
        if (month < 1 || month > 12) return monthYear;
        return months[month - 1] + " " + year;
    }

    private String safeBrief(String msg) {
        if (msg == null) return "error";
        return msg.length() > 100 ? msg.substring(0, 100) : msg;
    }
}
