package com.admindi.backend.whatsapp;

import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.WhatsappConversationStateEntity;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.BanxicoInstitutionCatalogService;
import com.admindi.backend.service.TwilioWhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Flujo del comprobante SPEI recibido por WhatsApp.
 *
 * Dos caminos:
 * <ul>
 *   <li><b>Foto + OCR</b> — Claude Vision extrae los datos y el inquilino confirma.</li>
 *   <li><b>Captura manual</b> — si no puede enviar imagen, el bot pide paso a paso
 *       clave de rastreo, monto, fecha y banco emisor para validar con Banxico CEP.</li>
 * </ul>
 */
@Service
public class WhatsAppProofFlow {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppProofFlow.class);

    private static final Set<String> MANUAL_KEYWORDS = Set.of(
            "manual", "texto", "sin foto", "sinfoto", "escribir", "teclado", "datos", "captura");

    private static final String STEP_CLAVE = "clave";
    private static final String STEP_MONTO = "monto";
    private static final String STEP_FECHA = "fecha";
    private static final String STEP_BANCO = "banco";

    private final WhatsAppSessionService sessions;
    private final TwilioWhatsAppService twilio;
    private final InvoiceRepository invoiceRepo;
    private final UserRepository userRepo;
    private final BanxicoInstitutionCatalogService banxicoCatalog;
    private final ObjectProvider<ReceiptOcrPort> ocrPortProvider;
    private final ObjectProvider<ProofSubmissionPort> submissionPortProvider;

    @Autowired
    public WhatsAppProofFlow(WhatsAppSessionService sessions,
                              TwilioWhatsAppService twilio,
                              InvoiceRepository invoiceRepo,
                              UserRepository userRepo,
                              BanxicoInstitutionCatalogService banxicoCatalog,
                              @Qualifier("receiptOcrPort") ObjectProvider<ReceiptOcrPort> ocrPortProvider,
                              @Qualifier("proofSubmissionPort") ObjectProvider<ProofSubmissionPort> submissionPortProvider) {
        this.sessions = sessions;
        this.twilio = twilio;
        this.invoiceRepo = invoiceRepo;
        this.userRepo = userRepo;
        this.banxicoCatalog = banxicoCatalog;
        this.ocrPortProvider = ocrPortProvider;
        this.submissionPortProvider = submissionPortProvider;
    }

    public boolean isManualEntryKeyword(String body) {
        if (body == null || body.isBlank()) return false;
        String norm = body.trim().toLowerCase(Locale.ROOT);
        return MANUAL_KEYWORDS.stream().anyMatch(norm::contains);
    }

    public void processIncomingProof(String fromE164, UserEntity user,
                                      WhatsappConversationStateEntity session,
                                      String invoiceId,
                                      WhatsAppMediaDownloader.Media media) {
        ReceiptOcrPort ocr = ocrPortProvider.getIfAvailable();
        if (ocr == null) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Recibí tu imagen, pero el reconocimiento automático no está disponible. "
                            + "Escribe *MANUAL* y te pediré los datos de tu transferencia "
                            + "(clave de rastreo, monto, fecha y banco) para validar con Banxico.");
            return;
        }

        try {
            ReceiptOcrPort.ExtractedReceipt extracted = ocr.extract(media.bytes(),
                    media.mimeType(), user.getId(), user.getOwnerId());
            if (!extracted.ok()) {
                twilio.sendFreeformWhatsApp(fromE164,
                        "No logré leer el comprobante (" + safe(extracted.errorMessage()) + ").\n\n"
                                + "Puedes enviar otra foto con mejor luz, o escribe *MANUAL* "
                                + "para capturar los datos escribiendo.");
                return;
            }

            putExtractedData(session, extracted, storeProofBytes(media), "AI_OCR");
            sendConfirmationSummary(fromE164, session, true);
        } catch (Exception ex) {
            logger.warn("[BOT-PROOF] OCR failed for user={}: {}", user.getId(),
                    ex.getClass().getSimpleName());
            twilio.sendFreeformWhatsApp(fromE164,
                    "Tuvimos un problema procesando tu comprobante.\n\n"
                            + "Intenta enviar la foto de nuevo o escribe *MANUAL* "
                            + "para registrar los datos de tu transferencia a mano.");
        }
    }

    /**
     * Inicia captura manual paso a paso (sin foto).
     */
    public void startManualEntry(String fromE164, UserEntity user,
                                  WhatsappConversationStateEntity session) {
        Map<String, Object> ctx = sessions.getContext(session);
        String invoiceId = (String) ctx.get("invoiceId");
        if (invoiceId == null) {
            twilio.sendFreeformWhatsApp(fromE164, "Sesión inválida. Escribe MENU para reiniciar.");
            return;
        }

        String beneficiaryInfo = describeBeneficiaryAccount(invoiceId);
        sessions.transition(session, WhatsAppBotState.PROOF_MANUAL_ENTRY, Map.of(
                "manualStep", STEP_CLAVE,
                "captureMethod", "MANUAL"));

        twilio.sendFreeformWhatsApp(fromE164,
                "Perfecto, vamos paso a paso. Necesitamos los datos de tu transferencia SPEI "
                        + "para validarlos con Banxico.\n\n"
                        + beneficiaryInfo + "\n\n"
                        + "*Paso 1 de 4* — Escribe la *clave de rastreo* "
                        + "(aparece en tu app del banco o en el comprobante).\n\n"
                        + "Escribe MENU para cancelar.");
    }

    /**
     * Procesa cada paso de la captura manual.
     */
    public void handleManualStep(String fromE164, UserEntity user,
                                  WhatsappConversationStateEntity session, String body) {
        if (body == null || body.isBlank()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "No recibí texto. Por favor escribe la información solicitada o MENU para cancelar.");
            return;
        }

        Map<String, Object> ctx = sessions.getContext(session);
        String step = (String) ctx.getOrDefault("manualStep", STEP_CLAVE);
        String input = body.trim();

        switch (step) {
            case STEP_CLAVE -> {
                if (input.length() < 8) {
                    twilio.sendFreeformWhatsApp(fromE164,
                            "La clave de rastreo parece muy corta. Revísala en tu comprobante "
                                    + "y escríbela completa (suele tener entre 20 y 30 caracteres).");
                    return;
                }
                sessions.putContext(session, "manualClaveRastreo", input);
                sessions.putContext(session, "manualStep", STEP_MONTO);
                sessions.save(session);
                twilio.sendFreeformWhatsApp(fromE164,
                        "*Paso 2 de 4* — Escribe el *monto* que transferiste en pesos "
                                + "(solo el número, ej: 8500 o 8500.50).");
            }
            case STEP_MONTO -> {
                BigDecimal amount = parseAmount(input);
                if (amount == null || amount.signum() <= 0) {
                    twilio.sendFreeformWhatsApp(fromE164,
                            "No reconocí el monto. Escribe solo el número en pesos, "
                                    + "por ejemplo: 8500 o 12500.50");
                    return;
                }
                sessions.putContext(session, "manualAmount", amount.toPlainString());
                sessions.putContext(session, "manualStep", STEP_FECHA);
                sessions.save(session);
                twilio.sendFreeformWhatsApp(fromE164,
                        "*Paso 3 de 4* — Escribe la *fecha* de la transferencia "
                                + "(formato DD/MM/AAAA, ej: 09/06/2026).");
            }
            case STEP_FECHA -> {
                LocalDate date = parseTransferDate(input);
                if (date == null) {
                    twilio.sendFreeformWhatsApp(fromE164,
                            "No reconocí la fecha. Escríbela como DD/MM/AAAA "
                                    + "(ej: 09/06/2026) o AAAA-MM-DD (ej: 2026-06-09).");
                    return;
                }
                sessions.putContext(session, "manualDate", date.toString());
                sessions.putContext(session, "manualStep", STEP_BANCO);
                sessions.save(session);
                twilio.sendFreeformWhatsApp(fromE164,
                        "*Paso 4 de 4* — Escribe el *banco desde el que enviaste* el dinero.\n\n"
                                + "Puedes usar el nombre (BBVA, Banorte, Santander) "
                                + "o el código de 3 dígitos del banco emisor (ej: 012, 072).");
            }
            case STEP_BANCO -> {
                if (banxicoCatalog.resolveEmitter(input).isEmpty()) {
                    twilio.sendFreeformWhatsApp(fromE164,
                            "No encontré ese banco en el catálogo Banxico. "
                                    + "Intenta con el nombre completo (BBVA, Bancomer, Banorte, Santander) "
                                    + "o el código de 3 dígitos de tu banco emisor.");
                    return;
                }
                sessions.putContext(session, "manualBankEmitter", input);
                finalizeManualEntry(fromE164, session);
            }
            default -> {
                sessions.putContext(session, "manualStep", STEP_CLAVE);
                sessions.save(session);
                twilio.sendFreeformWhatsApp(fromE164,
                        "Reiniciemos. *Paso 1 de 4* — escribe la clave de rastreo de tu transferencia.");
            }
        }
    }

    private void finalizeManualEntry(String fromE164, WhatsappConversationStateEntity session) {
        Map<String, Object> ctx = sessions.getContext(session);
        sessions.putContext(session, "ocrClaveRastreo", ctx.get("manualClaveRastreo"));
        sessions.putContext(session, "ocrAmount", ctx.get("manualAmount"));
        sessions.putContext(session, "ocrDate", ctx.get("manualDate"));
        sessions.putContext(session, "ocrBankEmitter", ctx.get("manualBankEmitter"));
        sessions.putContext(session, "ocrImagePath", null);
        sessions.putContext(session, "captureMethod", "MANUAL");
        sessions.transition(session, WhatsAppBotState.PROOF_CONFIRMING_DATA, Map.of());
        sendConfirmationSummary(fromE164, session, false);
    }

    public void processConfirmation(String fromE164, UserEntity user,
                                     WhatsappConversationStateEntity session, String body) {
        String yesno = body == null ? "" : body.trim().toLowerCase(Locale.ROOT);
        if (yesno.startsWith("n")) {
            Map<String, Object> ctx = sessions.getContext(session);
            boolean manual = "MANUAL".equalsIgnoreCase((String) ctx.get("captureMethod"));
            if (manual) {
                sessions.transition(session, WhatsAppBotState.PROOF_MANUAL_ENTRY, Map.of(
                        "manualStep", STEP_CLAVE,
                        "captureMethod", "MANUAL"));
                twilio.sendFreeformWhatsApp(fromE164,
                        "Ok, empecemos de nuevo. *Paso 1 de 4* — escribe la clave de rastreo.");
            } else {
                sessions.transition(session, WhatsAppBotState.PROOF_WAITING_IMAGE, Map.of());
                twilio.sendFreeformWhatsApp(fromE164,
                        "Ok, envíame la foto de nuevo o escribe *MANUAL* para capturar los datos escribiendo.");
            }
            return;
        }
        if (!yesno.startsWith("s") && !yesno.startsWith("y") && !yesno.equals("si")) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Por favor responde *SI* si los datos son correctos o *NO* para corregirlos.");
            return;
        }

        ProofSubmissionPort submitter = submissionPortProvider.getIfAvailable();
        if (submitter == null) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "El procesador de pagos no está disponible ahora. Intenta más tarde o súbelo desde el portal.");
            sessions.transition(session, WhatsAppBotState.MENU, Map.of());
            return;
        }

        Map<String, Object> ctx = sessions.getContext(session);
        String invoiceId = (String) ctx.get("invoiceId");
        String captureMethod = (String) ctx.getOrDefault("captureMethod", "AI_OCR");
        ProofSubmissionPort.SubmissionResult result = submitter.submit(user, invoiceId,
                (String) ctx.get("ocrClaveRastreo"),
                (String) ctx.get("ocrBankEmitter"),
                (String) ctx.get("ocrAccountReceiver"),
                (String) ctx.get("ocrAmount"),
                (String) ctx.get("ocrDate"),
                (String) ctx.get("ocrImagePath"),
                captureMethod);

        String friendlyMessage = switch (result.status()) {
            case VALIDATED -> "✅ Pago validado automáticamente con Banxico. Tu renta quedó registrada.";
            case INCOMPLETE -> "Faltan datos (" + safe(result.detail()) + "). "
                    + "Escribe MANUAL para intentar de nuevo o contacta a tu arrendador.";
            case REJECTED_BY_CEP -> "Banxico no pudo validar el pago (" + safe(result.detail()) + "). "
                    + "Verifica que la clave de rastreo, monto, fecha y banco sean correctos "
                    + "y escribe MANUAL para reintentar.";
            case TEMP_ERROR -> "Registré tu comprobante; el dueño lo revisará manualmente porque "
                    + "la validación automática no está disponible ahora.";
            case FAILED -> "No pude procesar tu comprobante. Intenta de nuevo escribiendo MANUAL "
                    + "o súbelo desde el portal web.";
        };
        twilio.sendFreeformWhatsApp(fromE164, friendlyMessage + "\n\nEscribe MENU para volver.");
        sessions.transition(session, WhatsAppBotState.MENU, Map.of());
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private void putExtractedData(WhatsappConversationStateEntity session,
                                   ReceiptOcrPort.ExtractedReceipt extracted,
                                   String imagePath, String captureMethod) {
        sessions.putContext(session, "ocrClaveRastreo", extracted.claveRastreo());
        sessions.putContext(session, "ocrAmount", extracted.amount());
        sessions.putContext(session, "ocrDate", extracted.transferDate());
        sessions.putContext(session, "ocrBankEmitter", extracted.bankEmitter());
        sessions.putContext(session, "ocrAccountReceiver", extracted.accountReceiver());
        sessions.putContext(session, "ocrImagePath", imagePath);
        sessions.putContext(session, "captureMethod", captureMethod);
        sessions.transition(session, WhatsAppBotState.PROOF_CONFIRMING_DATA, Map.of());
    }

    private void sendConfirmationSummary(String fromE164,
                                          WhatsappConversationStateEntity session,
                                          boolean fromOcr) {
        Map<String, Object> ctx = sessions.getContext(session);
        String invoiceId = (String) ctx.get("invoiceId");
        String beneficiary = invoiceId != null ? describeBeneficiaryAccount(invoiceId) : "";

        StringBuilder sb = new StringBuilder();
        if (fromOcr) {
            sb.append("Esto fue lo que leí del comprobante:\n\n");
        } else {
            sb.append("Resumen de los datos que capturaste:\n\n");
        }
        sb.append("• Clave de rastreo: ").append(nz(ctx.get("ocrClaveRastreo"))).append("\n");
        sb.append("• Monto: $").append(nz(ctx.get("ocrAmount"))).append(" MXN\n");
        sb.append("• Fecha: ").append(nz(ctx.get("ocrDate"))).append("\n");
        sb.append("• Banco emisor: ").append(nz(ctx.get("ocrBankEmitter"))).append("\n");
        if (!beneficiary.isBlank()) {
            sb.append("• ").append(beneficiary).append("\n");
        }
        sb.append("\n¿Son correctos? Responde *SI* para validar con Banxico o *NO* para corregir.");
        twilio.sendFreeformWhatsApp(fromE164, sb.toString());
    }

    /**
     * Describe la CLABE beneficiaria del dueño (enmascarada) para que el inquilino
     * verifique que su transferencia fue a la cuenta correcta. La CLABE la toma el
     * sistema del perfil del arrendador — el inquilino no la escribe.
     */
    private String describeBeneficiaryAccount(String invoiceId) {
        return invoiceRepo.findById(invoiceId)
                .flatMap(inv -> userRepo.findById(inv.getOwnerId()))
                .map(owner -> {
                    String clabe = owner.getClabe();
                    if (clabe == null || clabe.isBlank()) {
                        return "Cuenta beneficiaria: tu arrendador aún no tiene CLABE registrada; "
                                + "el pago quedará en revisión manual.";
                    }
                    String digits = clabe.replaceAll("\\D", "");
                    String masked = digits.length() >= 4
                            ? "****" + digits.substring(digits.length() - 4)
                            : "****";
                    return "Cuenta beneficiaria (CLABE del arrendador): " + masked
                            + " — verifica que tu SPEI fue a esa cuenta.";
                })
                .orElse("Cuenta beneficiaria: se tomará del perfil de tu arrendador.");
    }

    private BigDecimal parseAmount(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            String clean = input.replaceAll("[,$\\sMXNmxn]", "");
            return new BigDecimal(clean);
        } catch (Exception ex) {
            return null;
        }
    }

    private LocalDate parseTransferDate(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.trim();
        for (DateTimeFormatter fmt : new DateTimeFormatter[]{
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("d/M/uuuu"),
                DateTimeFormatter.ofPattern("dd/MM/uuuu"),
                DateTimeFormatter.ofPattern("d-M-uuuu"),
                DateTimeFormatter.ofPattern("dd-MM-uuuu")}) {
            try {
                return LocalDate.parse(trimmed, fmt);
            } catch (DateTimeParseException ignored) { /* try next */ }
        }
        return null;
    }

    private String storeProofBytes(WhatsAppMediaDownloader.Media media) {
        try {
            return submissionPortProvider.getIfAvailable() != null
                    ? submissionPortProvider.getObject().persistBotProofImage(media.bytes(), media.mimeType())
                    : null;
        } catch (Exception ex) {
            logger.warn("[BOT-PROOF] store failed: {}", ex.getMessage());
            return null;
        }
    }

    private String nz(Object o) {
        return o == null || o.toString().isBlank() ? "(pendiente)" : o.toString();
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
