package com.admindi.backend.service;

import com.admindi.backend.model.AgentNotificationChainEntity;
import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.model.MaintenanceQuoteEntity;
import com.admindi.backend.model.MaintenanceTicketEntity;
import com.admindi.backend.model.OwnerAgentPriorityEntity;
import com.admindi.backend.model.OwnerMembershipEntity;
import com.admindi.backend.model.PlatformProviderAssignmentEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.ExpenseRepository;
import com.admindi.backend.repository.MaintenanceQuoteRepository;
import com.admindi.backend.repository.MaintenanceTicketRepository;
import com.admindi.backend.repository.OwnerMembershipRepository;
import com.admindi.backend.repository.PlatformProviderAssignmentRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Flujo Fase 2 de mantenimiento: autorización del dueño → cadena de provider →
 * cotización → aprobación → ejecución → pago → cierre + descuento plataforma.
 *
 * <p>Este servicio <strong>no reemplaza</strong> al {@code MaintenanceService}
 * legacy; añade una capa encima con semántica nueva. El punto de entrada es
 * {@link #createTicketWithOwnerAuth}: el tenant abre el ticket pero éste queda en
 * {@code AWAITING_OWNER_AUTH} y se notifica al dueño para que autorice.
 *
 * <h3>Ciclo de vida</h3>
 * <ol>
 *   <li><strong>Tenant abre ticket</strong>: se guarda con fotos y status
 *       {@code AWAITING_OWNER_AUTH}; dueño recibe notificación.</li>
 *   <li><strong>Owner autoriza</strong>: elige provider directo o arranca cadena
 *       según prioridades. Ticket pasa a {@code AWAITING_PROVIDER_ACCEPT}.</li>
 *   <li><strong>Provider acepta</strong>: ticket {@code ACCEPTED}, provider ejecuta
 *       trabajo y sube cotización con evidencias (fotos + monto).</li>
 *   <li><strong>Owner aprueba cotización</strong>: ticket {@code APPROVED}, si el
 *       provider es PLATFORM se congela el descuento 15% en la ticket (
 *       {@link MaintenanceTicketEntity#setPlatformDiscountPct}).</li>
 *   <li><strong>Owner paga</strong> al provider (SPEI). Sistema crea un expense
 *       contable con el monto neto (monto_cotización − descuento si aplica).</li>
 *   <li><strong>Ticket CLOSED</strong>.</li>
 * </ol>
 *
 * <p><strong>Descuento 15% plataforma:</strong> si el provider elegido está
 * vinculado a la plataforma (registro en {@code PlatformProviderAssignmentEntity}
 * con flag PLATFORM), el dueño paga el monto total al provider pero el sistema
 * registra un <em>crédito</em> a favor del owner por el 15% (campo
 * {@code platform_discount_amount}). La reconciliación contable (creación de una
 * ExpenseEntity con neto, o un IncomeEntity con crédito) queda delegada al
 * módulo contable; este servicio sólo anota los campos y dispara evento.
 */
@Service
public class MaintenanceWorkflowService {

    private static final Logger logger = LoggerFactory.getLogger(MaintenanceWorkflowService.class);

    private final MaintenanceTicketRepository ticketRepository;
    private final MaintenanceQuoteRepository quoteRepository;
    private final ExpenseRepository expenseRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final PlatformProviderAssignmentRepository platformProviderRepository;
    private final OwnerMembershipRepository ownerMembershipRepository;
    private final AgentChainOrchestrationService chainOrchestrator;
    private final DomainEventDispatcher domainEventDispatcher;
    private final FileOwnershipService fileOwnership;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${admindi.maintenance.platform-maintenance-discount-pct:0.15}")
    private BigDecimal platformDiscountPct;

    @Value("${app.url:https://app.admindi.com}")
    private String appUrl;

    public MaintenanceWorkflowService(MaintenanceTicketRepository ticketRepository,
                                      MaintenanceQuoteRepository quoteRepository,
                                      ExpenseRepository expenseRepository,
                                      PropertyRepository propertyRepository,
                                      UserRepository userRepository,
                                      TenantProfileRepository tenantProfileRepository,
                                      PlatformProviderAssignmentRepository platformProviderRepository,
                                      OwnerMembershipRepository ownerMembershipRepository,
                                      AgentChainOrchestrationService chainOrchestrator,
                                      DomainEventDispatcher domainEventDispatcher,
                                      FileOwnershipService fileOwnership) {
        this.ticketRepository = ticketRepository;
        this.quoteRepository = quoteRepository;
        this.expenseRepository = expenseRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.platformProviderRepository = platformProviderRepository;
        this.ownerMembershipRepository = ownerMembershipRepository;
        this.chainOrchestrator = chainOrchestrator;
        this.domainEventDispatcher = domainEventDispatcher;
        this.fileOwnership = fileOwnership;
    }

    private UserEntity currentUser() {
        return userRepository.findByLoginIdentifier(
                SecurityContextHolder.getContext().getAuthentication().getName()).orElseThrow();
    }

    // ─── 0. Queries para la bandeja del dueño ─────────────────────────────────────

    /** Tickets que esperan que el dueño autorice/rechace. */
    public List<MaintenanceTicketEntity> listPendingAuthForOwner() {
        String ownerId = currentUser().getId();
        List<MaintenanceTicketEntity> res = ticketRepository.findByOwnerIdAndStatus(ownerId, MaintenanceTicketEntity.STATUS_AWAITING_OWNER_AUTH);
        // #region agent log
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Paths.get("C:\\Users\\Arfax\\Desktop\\plataforma inmubles\\debug-93290f.log"),
                "{\"sessionId\":\"93290f\",\"hypothesisId\":\"H4\",\"location\":\"MaintenanceWorkflowService.listPendingAuthForOwner\",\"message\":\"Owner workflow inbox queried pending-auth tickets\",\"data\":{\"ownerId\":\"" + dbgSafeWf(ownerId) + "\",\"pendingCount\":" + res.size() + "},\"timestamp\":" + System.currentTimeMillis() + "}\n",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND,
                java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception _dbgIgnored) {}
        // #endregion
        return res;
    }

    // #region agent log
    private static String dbgSafeWf(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "'");
    }
    // #endregion

    /** Cotizaciones {@code SUBMITTED} pendientes de aprobación del dueño. */
    public List<MaintenanceQuoteEntity> listPendingQuotesForOwner() {
        String ownerId = currentUser().getId();
        // Recorre quoted tickets del owner y junta las quotes en estado SUBMITTED.
        List<MaintenanceTicketEntity> quoted = ticketRepository.findByOwnerIdAndStatus(
                ownerId, MaintenanceTicketEntity.STATUS_QUOTED);
        return quoted.stream()
                .flatMap(t -> quoteRepository.findByTicketId(t.getId()).stream())
                .filter(q -> "SUBMITTED".equalsIgnoreCase(q.getStatus()))
                .toList();
    }

    /** Tickets {@code APPROVED} listos para que el dueño pague al provider. */
    public List<MaintenanceTicketEntity> listReadyToPayForOwner() {
        String ownerId = currentUser().getId();
        return ticketRepository.findByOwnerIdAndStatus(ownerId, MaintenanceTicketEntity.STATUS_APPROVED);
    }

    /** Últimos tickets del dueño, sin filtro. Para un resumen general. */
    public List<MaintenanceTicketEntity> listAllForOwner() {
        String ownerId = currentUser().getId();
        return ticketRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    /** Cotizaciones de un ticket (ambos roles: dueño o provider del ticket). */
    public List<MaintenanceQuoteEntity> listQuotesByTicket(String ticketId) {
        return quoteRepository.findByTicketId(ticketId);
    }

    // ─── 1. Tenant abre ticket ────────────────────────────────────────────────────

    /**
     * El tenant abre el ticket con descripción y fotos. Ticket queda en
     * AWAITING_OWNER_AUTH hasta que el dueño autorice.
     *
     * <p>V59 — defensa IDOR sobre fotos: cada {@code photoFileId} debe tener
     * un {@code FileUploadClaim} a nombre del tenant autenticado. Si alguno no,
     * se rechaza con 403. Al guardar el ticket se marcan como consumidos.
     * <br>Notificación — se emite a la organización completa del dueño: al
     * dueño y a todos los PROPERTY_ADMIN vinculados vía {@code owner_memberships}.
     */
    @Transactional
    public MaintenanceTicketEntity createTicketWithOwnerAuth(String ownerId, String propertyId,
                                                              String tenantProfileId, String title,
                                                              String description, String urgency,
                                                              List<String> photoFileIds) {
        UserEntity tenant = currentUser();
        if (tenant.getRole() != Role.TENANT) {
            throw new SecurityException("Solo el tenant puede abrir un ticket por esta vía.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Título del ticket obligatorio.");
        }

        // V59 — IDOR guard: el inquilino autenticado debe ser el dueño del
        // tenantProfile y éste debe pertenecer al ownerId indicado. Sin esto,
        // un TENANT podría abrir un ticket a nombre de otra organización.
        if (tenantProfileId != null && !tenantProfileId.isBlank()) {
            TenantProfileEntity profile = tenantProfileRepository.findById(tenantProfileId)
                    .orElseThrow(() -> new SecurityException("Expediente no encontrado."));
            if (!tenant.getId().equals(profile.getUserId())) {
                throw new SecurityException("Expediente no pertenece al inquilino autenticado.");
            }
            if (ownerId == null || ownerId.isBlank() || !ownerId.equals(profile.getOwnerId())) {
                throw new SecurityException("Expediente no pertenece al dueño indicado.");
            }
            if (profile.getPropertyId() != null && propertyId != null
                    && !profile.getPropertyId().equals(propertyId)) {
                throw new SecurityException("El inmueble no coincide con el expediente.");
            }
            // Si el front no mandó propertyId, lo tomamos del profile (evita NPE).
            if (propertyId == null || propertyId.isBlank()) {
                propertyId = profile.getPropertyId();
            }
        }
        if (propertyId == null || propertyId.isBlank()) {
            throw new IllegalArgumentException("propertyId obligatorio (o expediente con inmueble).");
        }

        // V59 — validar que cada foto haya sido subida por este inquilino. El
        // assertUploader tira 403 si la claim no existe o pertenece a otro user.
        if (photoFileIds != null) {
            for (String fid : photoFileIds) {
                fileOwnership.assertUploader(fid, tenant.getId());
            }
        }

        MaintenanceTicketEntity t = new MaintenanceTicketEntity();
        t.setOwnerId(ownerId);
        t.setPropertyId(propertyId);
        t.setTenantProfileId(tenantProfileId);
        t.setTitle(title);
        t.setDescription(description);
        t.setUrgency(urgency != null ? urgency : "NORMAL");
        t.setStatus(MaintenanceTicketEntity.STATUS_AWAITING_OWNER_AUTH);
        t.setAwaitingOwnerAuth(Boolean.TRUE);
        if (photoFileIds != null && !photoFileIds.isEmpty()) {
            t.setPhotoFileIdsJson(serializeFileIds(photoFileIds));
        }
        MaintenanceTicketEntity saved = ticketRepository.save(t);

        // V59 — marcar fotos como consumidas por este ticket (idempotente en
        // el ownership service). Debe ir tras el save para que haya un id.
        if (photoFileIds != null) {
            for (String fid : photoFileIds) {
                fileOwnership.markConsumed(fid, "MAINTENANCE_TICKET", saved.getId());
            }
        }

        String propLabel = propLabel(propertyId);
        UserEntity ownerUser = userRepository.findById(ownerId).orElse(null);
        // Plantilla admindi_maintenance_ticket_awaiting_owner_auth_v1:
        //   {{1}}dueño {{2}}inmueble {{3}}título {{4}}urgencia {{5}}URL.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(ownerUser));
        vars.put("2", propLabel);
        vars.put("3", title);
        vars.put("4", t.getUrgency());
        vars.put("5", appUrl + "/dashboard?panel=owner&ticket=" + saved.getId());

        // V59 — recipients incluye al dueño y a los PROPERTY_ADMIN vinculados.
        List<String> recipients = collectOwnerAndAdminsRecipients(ownerId);
        domainEventDispatcher.dispatch("MAINTENANCE_TICKET_AWAITING_OWNER_AUTH",
                "Ticket de mantenimiento pendiente de autorización",
                "Tu inquilino reportó: " + title + " (" + propLabel + "). "
                        + "Autoriza y elige proveedor en: " + vars.get("5"),
                ownerId, tenant.getUsername(), recipients, vars, null);

        // V59 — confirmación al propio inquilino ("recibimos tu reporte, el
        // dueño lo revisará en 72h"). Va por free-body; no consume plantilla.
        notifyTenantOfTicketProgress(saved, "MAINTENANCE_TICKET_TENANT_CONFIRMED",
                "Reporte de mantenimiento recibido",
                "Recibimos tu reporte \"" + title + "\" (" + propLabel + "). "
                        + "Tu arrendador tiene 72 horas para autorizarlo y asignar un proveedor. "
                        + "Te avisaremos en cada paso.");

        return saved;
    }

    // ─── 1.1. Queries para el panel del inquilino ────────────────────────────────

    /**
     * Lista los tickets abiertos por un inquilino específico (todos sus
     * expedientes activos en la organización indicada por {@link com.admindi.backend.security.TenantContext}).
     * Si {@code tenantProfileIdFilter} viene, solo devuelve los de ese expediente.
     */
    @Transactional(readOnly = true)
    public List<MaintenanceTicketEntity> listTicketsForTenantUser(String tenantProfileIdFilter) {
        UserEntity tenant = currentUser();
        if (tenant.getRole() != Role.TENANT) {
            throw new SecurityException("Consulta reservada al inquilino autenticado.");
        }
        List<TenantProfileEntity> profiles = tenantProfileRepository
                .findAll().stream()
                .filter(p -> tenant.getId().equals(p.getUserId()))
                .filter(p -> p.getArchivedAt() == null)
                .toList();
        if (tenantProfileIdFilter != null && !tenantProfileIdFilter.isBlank()) {
            profiles = profiles.stream()
                    .filter(p -> tenantProfileIdFilter.equals(p.getId()))
                    .toList();
            if (profiles.isEmpty()) {
                throw new SecurityException("Expediente no pertenece al inquilino.");
            }
        }
        List<MaintenanceTicketEntity> out = new java.util.ArrayList<>();
        for (TenantProfileEntity p : profiles) {
            out.addAll(ticketRepository.findByTenantProfileIdOrderByCreatedAtDesc(p.getId()));
        }
        // Orden global por createdAt descendente (los más nuevos arriba).
        out.sort((a, b) -> {
            LocalDateTime ca = a.getCreatedAt();
            LocalDateTime cb = b.getCreatedAt();
            if (ca == null && cb == null) return 0;
            if (ca == null) return 1;
            if (cb == null) return -1;
            return cb.compareTo(ca);
        });
        return out;
    }

    /**
     * Obtiene un ticket específico del inquilino autenticado. Tira 403 si el
     * ticket pertenece a otro tenantProfile / user.
     */
    @Transactional(readOnly = true)
    public MaintenanceTicketEntity getTicketForTenantUser(String ticketId) {
        UserEntity tenant = currentUser();
        if (tenant.getRole() != Role.TENANT) {
            throw new SecurityException("Consulta reservada al inquilino autenticado.");
        }
        MaintenanceTicketEntity t = requireTicket(ticketId);
        if (t.getTenantProfileId() == null) {
            throw new SecurityException("Ticket sin expediente — no puede ser del inquilino.");
        }
        TenantProfileEntity profile = tenantProfileRepository.findById(t.getTenantProfileId())
                .orElseThrow(() -> new SecurityException("Expediente del ticket no encontrado."));
        if (!tenant.getId().equals(profile.getUserId())) {
            throw new SecurityException("Este ticket no pertenece al inquilino autenticado.");
        }
        return t;
    }

    // ─── 2. Owner autoriza/rechaza ────────────────────────────────────────────────

    /**
     * Owner autoriza el ticket. Dos modos:
     * <ul>
     *   <li>{@code providerUserId != null}: asigna directamente al provider elegido
     *       (puede ser privado o platform). El ticket pasa a AWAITING_PROVIDER_ACCEPT.</li>
     *   <li>{@code providerUserId == null}: arranca la cadena con las prioridades
     *       MAINTENANCE configuradas. Si no hay prioridades, lanza excepción.</li>
     * </ul>
     */
    @Transactional
    public MaintenanceTicketEntity ownerAuthorize(String ticketId, String providerUserId) {
        UserEntity owner = currentUser();
        MaintenanceTicketEntity t = requireTicket(ticketId);
        assertOwner(t, owner);
        if (!MaintenanceTicketEntity.STATUS_AWAITING_OWNER_AUTH.equals(t.getStatus())) {
            throw new IllegalStateException("El ticket no está esperando autorización (estado: " + t.getStatus() + ").");
        }

        t.setAuthorizedAt(LocalDateTime.now());
        t.setAuthorizedBy(owner.getId());
        t.setAwaitingOwnerAuth(Boolean.FALSE);

        if (providerUserId != null && !providerUserId.isBlank()) {
            UserEntity provider = userRepository.findById(providerUserId).orElseThrow();
            if (provider.getRole() != Role.MAINTENANCE_PROVIDER) {
                throw new IllegalArgumentException("El usuario elegido no es MAINTENANCE_PROVIDER.");
            }
            t.setOwnerChosenProviderId(providerUserId);
            t.setAssignedProviderId(providerUserId);
            t.setStatus(MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_ACCEPT);
            ticketRepository.save(t);
            notifyProviderOfTicket(t, provider);
            // V59 — aviso al inquilino de que su reporte avanzó.
            notifyTenantOfTicketProgress(t, "MAINTENANCE_TICKET_AUTHORIZED",
                    "Tu reporte fue autorizado",
                    "Tu arrendador autorizó \"" + t.getTitle() + "\" y asignó a "
                            + nullSafe(provider.getName()) + ". El proveedor tiene 72 horas para aceptar.");
            return t;
        }

        // Modo cadena: arrancar con prioridades del owner.
        Optional<AgentNotificationChainEntity> firstLinkOpt = chainOrchestrator.startChain(
                OwnerAgentPriorityEntity.FLOW_MAINTENANCE,
                AgentNotificationChainEntity.RESOURCE_MAINTENANCE_TICKET,
                t.getId(), t.getOwnerId());

        if (firstLinkOpt.isEmpty()) {
            // No hay prioridades — revertir al modo legacy: notificar al owner que
            // debe elegir un provider específico.
            t.setStatus(MaintenanceTicketEntity.STATUS_AWAITING_OWNER_AUTH);
            t.setAwaitingOwnerAuth(Boolean.TRUE);
            t.setAuthorizedAt(null);
            t.setAuthorizedBy(null);
            ticketRepository.save(t);
            throw new IllegalStateException("No tienes prioridades MAINTENANCE configuradas ni elegiste un proveedor. "
                    + "Configura prioridades o indica providerUserId.");
        }

        AgentNotificationChainEntity firstLink = firstLinkOpt.get();
        t.setAssignedProviderId(firstLink.getAgentUserId());
        t.setStatus(MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_ACCEPT);
        ticketRepository.save(t);

        UserEntity provider = userRepository.findById(firstLink.getAgentUserId()).orElseThrow();
        notifyProviderOfTicket(t, provider);
        // V59 — aviso al inquilino: el dueño autorizó y se arrancó la cadena
        // de proveedores (el assigned puede rotar; avisamos al tenant del actual).
        notifyTenantOfTicketProgress(t, "MAINTENANCE_TICKET_AUTHORIZED",
                "Tu reporte fue autorizado",
                "Tu arrendador autorizó \"" + t.getTitle() + "\". El proveedor "
                        + nullSafe(provider.getName()) + " recibió la notificación y tiene 72h para aceptar.");
        return t;
    }

    /** Owner rechaza el ticket (no autoriza reparación). */
    @Transactional
    public MaintenanceTicketEntity ownerReject(String ticketId, String reason) {
        UserEntity owner = currentUser();
        MaintenanceTicketEntity t = requireTicket(ticketId);
        assertOwner(t, owner);
        if (!MaintenanceTicketEntity.STATUS_AWAITING_OWNER_AUTH.equals(t.getStatus())) {
            throw new IllegalStateException("Solo se pueden rechazar tickets AWAITING_OWNER_AUTH.");
        }
        t.setStatus(MaintenanceTicketEntity.STATUS_REJECTED_BY_OWNER);
        t.setRejectionReason(reason);
        t.setAwaitingOwnerAuth(Boolean.FALSE);
        ticketRepository.save(t);

        List<String> tenants = tenantRecipients(t);
        UserEntity tenantUser = tenants.isEmpty() ? null : userRepository.findById(tenants.get(0)).orElse(null);
        String propLabel = propLabel(t.getPropertyId());
        // Plantilla admindi_maintenance_ticket_rejected_by_owner_v1:
        //   {{1}}tenant {{2}}título {{3}}inmueble {{4}}motivo {{5}}URL.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(tenantUser));
        vars.put("2", t.getTitle());
        vars.put("3", propLabel);
        vars.put("4", (reason != null && !reason.isBlank()) ? reason : "Sin motivo especificado");
        vars.put("5", appUrl + "/dashboard?panel=tenant");
        domainEventDispatcher.dispatch("MAINTENANCE_TICKET_REJECTED_BY_OWNER",
                "El dueño declinó la reparación",
                "El dueño no autorizó tu ticket \"" + t.getTitle() + "\""
                        + (reason != null && !reason.isBlank() ? " — Motivo: " + reason : "") + ".",
                t.getOwnerId(), owner.getUsername(), tenants, vars, null);
        return t;
    }

    // ─── 3. Provider acepta/rechaza ───────────────────────────────────────────────

    @Transactional
    public MaintenanceTicketEntity providerAccept(String ticketId) {
        UserEntity provider = currentProvider();
        MaintenanceTicketEntity t = requireTicket(ticketId);
        if (t.getAssignedProviderId() == null || !t.getAssignedProviderId().equals(provider.getId())) {
            throw new SecurityException("Este ticket no está asignado a ti.");
        }
        if (!MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_ACCEPT.equals(t.getStatus())) {
            throw new IllegalStateException("El ticket no está en estado AWAITING_PROVIDER_ACCEPT.");
        }

        chainOrchestrator.findActiveLink(AgentNotificationChainEntity.RESOURCE_MAINTENANCE_TICKET, t.getId())
                .ifPresent(chainOrchestrator::markAccepted);
        t.setProviderAcceptedAt(LocalDateTime.now());
        t.setStatus(MaintenanceTicketEntity.STATUS_ACCEPTED);
        MaintenanceTicketEntity saved = ticketRepository.save(t);
        // V59 — aviso al inquilino: el proveedor aceptó y vendrá a diagnosticar.
        notifyTenantOfTicketProgress(saved, "MAINTENANCE_TICKET_PROVIDER_ACCEPTED",
                "Proveedor asignado a tu reporte",
                nullSafe(provider.getName()) + " aceptó atender \"" + saved.getTitle()
                        + "\". Se pondrá en contacto contigo para agendar la visita.");
        return saved;
    }

    @Transactional
    public MaintenanceTicketEntity providerReject(String ticketId, String reason) {
        UserEntity provider = currentProvider();
        MaintenanceTicketEntity t = requireTicket(ticketId);
        if (t.getAssignedProviderId() == null || !t.getAssignedProviderId().equals(provider.getId())) {
            throw new SecurityException("Este ticket no está asignado a ti.");
        }
        if (!MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_ACCEPT.equals(t.getStatus())) {
            throw new IllegalStateException("Solo se puede rechazar mientras AWAITING_PROVIDER_ACCEPT.");
        }

        Optional<AgentNotificationChainEntity> activeLink = chainOrchestrator.findActiveLink(
                AgentNotificationChainEntity.RESOURCE_MAINTENANCE_TICKET, t.getId());

        // Notificar al owner del rechazo (audit).
        String propLabel = propLabel(t.getPropertyId());
        dispatchProviderRejected(t, provider, propLabel,
                "Notificamos al siguiente proveedor de tu lista si existe, o deberás elegir manualmente.");

        if (activeLink.isEmpty()) {
            // No hay cadena: pasa a AWAITING_OWNER_AUTH para que owner elija otro.
            t.setStatus(MaintenanceTicketEntity.STATUS_AWAITING_OWNER_AUTH);
            t.setAwaitingOwnerAuth(Boolean.TRUE);
            t.setAssignedProviderId(null);
            return ticketRepository.save(t);
        }

        chainOrchestrator.markRejected(activeLink.get(), reason);
        Optional<AgentNotificationChainEntity> next = chainOrchestrator.advanceChain(activeLink.get(),
                (resType, resId, ownerId) -> {
                    t.setStatus(MaintenanceTicketEntity.STATUS_AWAITING_OWNER_AUTH);
                    t.setAwaitingOwnerAuth(Boolean.TRUE);
                    t.setAssignedProviderId(null);
                    ticketRepository.save(t);
                    dispatchProviderRejected(t, provider, propLabel,
                            "Ya no hay más proveedores en tu cadena. Debes elegir uno manualmente.");
                },
                newLink -> {
                    t.setAssignedProviderId(newLink.getAgentUserId());
                    t.setStatus(MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_ACCEPT);
                    ticketRepository.save(t);
                    UserEntity nextProvider = userRepository.findById(newLink.getAgentUserId()).orElseThrow();
                    notifyProviderOfTicket(t, nextProvider);
                });

        return ticketRepository.findById(t.getId()).orElse(t);
    }

    /**
     * V67 — El proveedor cancela el ticket después de haberlo aceptado
     * (p. ej. detecta que es duplicado, que el inquilino ya lo resolvió por
     * su cuenta, o que el caso no corresponde a su oficio).
     *
     * <p>Estados cancelables (sin dinero en movimiento):</p>
     * <ul>
     *   <li>{@code AWAITING_PROVIDER_ACCEPT} — aún no aceptaba; pero para este
     *       estado el flujo canónico es {@link #providerReject}. Permitimos
     *       cancel también por robustez.</li>
     *   <li>{@code ACCEPTED} — aceptado sin cotización enviada.</li>
     *   <li>{@code QUOTED} — cotización enviada, dueño no la ha aprobado.</li>
     * </ul>
     *
     * <p>No se permite cancelar cuando ya hay compromiso económico:
     * {@code APPROVED} (dueño autorizó pago), {@code AWAITING_PROVIDER_CONFIRM}
     * (dueño ya pagó), {@code COMPLETED}, {@code CANCELLED},
     * {@code REJECTED_BY_OWNER}.</p>
     *
     * <p>Si existe un expense ligado al ticket y no se ha pagado (UNPAID), lo
     * marcamos {@code VOID} para contabilidad. Si ya está pagado, el cancel
     * no debió permitirse (status=APPROVED+ ya bloquea).</p>
     *
     * <p>Notifica a: owner + admins (para audit operativo), y tenant autor
     * del reporte (para que sepa que tiene que reportar de nuevo si persiste
     * el problema).</p>
     */
    @Transactional
    public MaintenanceTicketEntity providerCancel(String ticketId, String reason) {
        UserEntity provider = currentProvider();
        MaintenanceTicketEntity t = requireTicket(ticketId);
        if (t.getAssignedProviderId() == null
                || !t.getAssignedProviderId().equals(provider.getId())) {
            throw new SecurityException("Este ticket no está asignado a ti.");
        }
        String status = t.getStatus();
        boolean cancelable =
                MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_ACCEPT.equals(status)
                || MaintenanceTicketEntity.STATUS_ACCEPTED.equals(status)
                || MaintenanceTicketEntity.STATUS_QUOTED.equals(status);
        if (!cancelable) {
            throw new IllegalStateException(
                    "No puedes cancelar este ticket en el estado actual. "
                    + "Si ya hay pago en curso, comunícalo al dueño directamente.");
        }

        String safeReason = (reason != null && !reason.isBlank())
                ? reason.trim()
                : "Cancelado por el proveedor sin motivo especificado.";

        // Marcar expense asociado como VOID (si existe y no está pagado).
        expenseRepository
                .findFirstByLinkedResourceTypeAndLinkedResourceIdOrderByCreatedAtDesc(
                        "MAINTENANCE_TICKET", t.getId())
                .ifPresent(expense -> {
                    if (expense.getPaidAmount() == null
                            || expense.getPaidAmount().signum() == 0) {
                        expense.setStatus("VOID");
                        expense.setOutstandingAmount(java.math.BigDecimal.ZERO);
                        expense.setPaymentSettlementStatus("VOIDED");
                        expenseRepository.save(expense);
                    }
                });

        t.setStatus(MaintenanceTicketEntity.STATUS_CANCELLED);
        t.setRejectionReason("[CANCELADO POR PROVEEDOR] " + safeReason);
        MaintenanceTicketEntity saved = ticketRepository.save(t);

        String propLabel = propLabel(t.getPropertyId());

        // 1) Notificación al owner y admins.
        domainEventDispatcher.dispatch("MAINTENANCE_TICKET_CANCELLED_BY_PROVIDER",
                "Proveedor canceló el ticket",
                nullSafe(provider.getName()) + " canceló \"" + t.getTitle() + "\" en "
                        + propLabel + ". Motivo: " + safeReason,
                t.getOwnerId(), provider.getUsername(),
                collectOwnerAndAdminsRecipients(t.getOwnerId()), null);

        // 2) Notificación al inquilino que reportó.
        notifyTenantOfTicketProgress(saved, "MAINTENANCE_TICKET_CANCELLED_BY_PROVIDER",
                "Tu reporte fue cancelado",
                nullSafe(provider.getName()) + " canceló tu reporte \"" + t.getTitle()
                        + "\". Motivo: " + safeReason
                        + ". Si el problema persiste, reporta un nuevo ticket desde tu panel.");

        return saved;
    }

    /** Timeout callback desde scheduler. */
    @Transactional
    public void handleProviderChainTimeout(AgentNotificationChainEntity expiredLink) {
        MaintenanceTicketEntity t = ticketRepository.findById(expiredLink.getResourceId()).orElse(null);
        if (t == null) return;
        UserEntity expiredProvider = userRepository.findById(expiredLink.getAgentUserId()).orElse(null);
        String propLabel = propLabel(t.getPropertyId());
        chainOrchestrator.advanceChain(expiredLink,
                (resType, resId, ownerId) -> {
                    t.setStatus(MaintenanceTicketEntity.STATUS_AWAITING_OWNER_AUTH);
                    t.setAwaitingOwnerAuth(Boolean.TRUE);
                    t.setAssignedProviderId(null);
                    ticketRepository.save(t);
                    dispatchProviderRejected(t, expiredProvider, propLabel,
                            "Nadie respondió dentro del plazo. Debes elegir un proveedor manualmente.");
                },
                newLink -> {
                    t.setAssignedProviderId(newLink.getAgentUserId());
                    t.setStatus(MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_ACCEPT);
                    ticketRepository.save(t);
                    UserEntity nextProvider = userRepository.findById(newLink.getAgentUserId()).orElseThrow();
                    notifyProviderOfTicket(t, nextProvider);
                });
    }

    /** Notifica al owner que un proveedor rechazó (y qué pasa después). */
    private void dispatchProviderRejected(MaintenanceTicketEntity t, UserEntity rejectingProvider,
                                          String propLabel, String nextStep) {
        UserEntity owner = userRepository.findById(t.getOwnerId()).orElse(null);
        // Plantilla admindi_maintenance_provider_rejected_v1:
        //   {{1}}dueño {{2}}proveedor {{3}}título {{4}}inmueble {{5}}siguiente paso {{6}}URL.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(owner));
        vars.put("2", rejectingProvider != null ? nullSafe(rejectingProvider.getName()) : "El proveedor");
        vars.put("3", t.getTitle());
        vars.put("4", propLabel);
        vars.put("5", nextStep);
        vars.put("6", appUrl + "/dashboard?panel=owner&ticket=" + t.getId());
        domainEventDispatcher.dispatch("MAINTENANCE_PROVIDER_REJECTED",
                "Proveedor rechazó el ticket",
                (rejectingProvider != null ? rejectingProvider.getName() : "El proveedor")
                        + " no pudo atender \"" + t.getTitle() + "\" en " + propLabel + ". " + nextStep,
                t.getOwnerId(),
                rejectingProvider != null ? rejectingProvider.getUsername() : null,
                List.of(t.getOwnerId()), vars, null);
    }

    // ─── 4. Provider cotiza ───────────────────────────────────────────────────────

    /**
     * Envía la cotización del proveedor. {@code visitNotes} es opcional y
     * se usa para comunicar al dueño hallazgos de la visita que no caben
     * en los conceptos técnicos del {@code description} (p.ej. "agregué
     * pintura porque el rodapié también se dañó", "el tenant no estaba,
     * reagendé para mañana"). Se guarda en la cotización y se anexa al
     * cuerpo del email / notificación in-app para que el dueño lo vea sin
     * tener que entrar al detalle. La plantilla WhatsApp aprobada mantiene
     * sus 6 slots fijos; el detalle vive en la URL del panel.
     */
    @Transactional
    public MaintenanceQuoteEntity providerSubmitQuote(String ticketId, BigDecimal amount,
                                                      String description, String evidenceFileId,
                                                      String visitNotes) {
        UserEntity provider = currentProvider();
        MaintenanceTicketEntity t = requireTicket(ticketId);
        if (t.getAssignedProviderId() == null || !t.getAssignedProviderId().equals(provider.getId())) {
            throw new SecurityException("Este ticket no está asignado a ti.");
        }
        if (!MaintenanceTicketEntity.STATUS_ACCEPTED.equals(t.getStatus())
                && !MaintenanceTicketEntity.STATUS_QUOTED.equals(t.getStatus())) {
            throw new IllegalStateException("Solo puedes cotizar un ticket que aceptaste.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Monto de la cotización obligatorio y mayor a 0.");
        }

        String trimmedNotes = (visitNotes != null && !visitNotes.isBlank())
                ? visitNotes.trim() : null;

        // V61 — IDOR guard: si el proveedor pasa un evidenceFileId, debe ser
        // un archivo que él mismo subió. Sin esto, un provider malicioso podría
        // referenciar el path de un archivo ajeno y adoptarlo como su evidencia.
        if (evidenceFileId != null && !evidenceFileId.isBlank()) {
            fileOwnership.assertUploader(evidenceFileId, provider.getId());
        }

        MaintenanceQuoteEntity q = new MaintenanceQuoteEntity();
        q.setTicketId(ticketId);
        q.setProviderId(provider.getId());
        q.setAmount(amount);
        q.setDescription(description);
        q.setVisitNotes(trimmedNotes);
        q.setEvidenceFileId(evidenceFileId);
        q.setStatus("SUBMITTED");
        MaintenanceQuoteEntity saved = quoteRepository.save(q);

        // V61 — consumir el claim del PDF/imagen de cotización y asociarlo al
        // quote. Esto permite que el SecureFileController autorice la descarga
        // contra el quote/ticket más tarde (dueño y proveedor pueden verlo).
        if (evidenceFileId != null && !evidenceFileId.isBlank()) {
            fileOwnership.markConsumed(evidenceFileId, "MAINTENANCE_QUOTE", saved.getId());
        }

        t.setStatus(MaintenanceTicketEntity.STATUS_QUOTED);
        ticketRepository.save(t);

        String propLabel = propLabel(t.getPropertyId());
        UserEntity owner = userRepository.findById(t.getOwnerId()).orElse(null);
        // Plantilla admindi_maintenance_quote_uploaded_v1:
        //   {{1}}dueño {{2}}proveedor {{3}}título {{4}}inmueble {{5}}monto {{6}}URL.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(owner));
        vars.put("2", nullSafe(provider.getName()));
        vars.put("3", t.getTitle());
        vars.put("4", propLabel);
        vars.put("5", formatAmount(amount));
        vars.put("6", appUrl + "/dashboard?panel=owner&ticket=" + ticketId);

        // El email/in-app llevan el cuerpo completo, incluyendo las notas si
        // las hay. WhatsApp sigue usando la plantilla aprobada (6 slots); el
        // dueño hace click en la URL para leer notas largas.
        StringBuilder body = new StringBuilder();
        body.append("Tu proveedor ").append(provider.getName())
                .append(" cotizó $").append(formatAmount(amount))
                .append(" para \"").append(t.getTitle()).append("\" (")
                .append(propLabel).append(").");
        if (trimmedNotes != null) {
            body.append(" Notas de visita: ").append(trimmedNotes);
        }
        body.append(" Revísalo en: ").append(vars.get("6"));

        domainEventDispatcher.dispatch("MAINTENANCE_QUOTE_UPLOADED",
                trimmedNotes != null
                        ? "Cotización subida (con notas del proveedor)"
                        : "Cotización subida por el proveedor",
                body.toString(),
                t.getOwnerId(), provider.getUsername(), List.of(t.getOwnerId()), vars, null);
        return saved;
    }

    // ─── 5. Owner aprueba cotización ──────────────────────────────────────────────

    @Transactional
    public MaintenanceTicketEntity ownerApproveQuote(String quoteId) {
        UserEntity owner = currentUser();
        MaintenanceQuoteEntity q = quoteRepository.findById(quoteId).orElseThrow();
        MaintenanceTicketEntity t = requireTicket(q.getTicketId());
        assertOwner(t, owner);
        if (!"SUBMITTED".equals(q.getStatus())) {
            throw new IllegalStateException("Esta cotización ya fue decidida.");
        }

        q.setStatus("APPROVED");
        q.setApprovedAt(LocalDateTime.now());
        quoteRepository.save(q);

        t.setStatus(MaintenanceTicketEntity.STATUS_APPROVED);

        // Si el provider es PLATFORM, congelamos el descuento 15%.
        if (isPlatformProvider(t.getOwnerId(), t.getAssignedProviderId())) {
            BigDecimal pct = platformDiscountPct;
            BigDecimal discount = q.getAmount().multiply(pct).setScale(2, RoundingMode.HALF_UP);
            t.setPlatformDiscountPct(pct);
            t.setPlatformDiscountAmount(discount);
        }
        ticketRepository.save(t);

        // Bloque 4 / Gap E: el expense se materializa YA al aprobar la cotización
        // (estado APPROVED, paidAmount=0) y queda ligado al ticket. Antes el
        // expense sólo nacía en ownerPayAndClose, con dos problemas:
        //   1) el expediente del inmueble no mostraba egresos "en curso de pago";
        //   2) al pagar, todas las columnas monto/fechas/adjunto se setteaban en
        //      un solo paso dejando huecos de auditoría (se sobrescribía el
        //      budget original). Ahora separamos creación (budget) y pago (proof).
        ExpenseEntity existing = expenseRepository
                .findFirstByLinkedResourceTypeAndLinkedResourceIdOrderByCreatedAtDesc(
                        "MAINTENANCE_TICKET", t.getId())
                .orElse(null);
        if (existing == null) {
            ExpenseEntity expense = new ExpenseEntity();
            expense.setOwnerId(t.getOwnerId());
            expense.setPropertyId(t.getPropertyId());
            expense.setType("MAINTENANCE");
            expense.setStatus("APPROVED");
            expense.setAmount(q.getAmount());
            expense.setApprovedAmount(q.getAmount());
            expense.setOutstandingAmount(q.getAmount());
            expense.setPaidAmount(BigDecimal.ZERO);
            expense.setPaymentSettlementStatus("UNPAID");
            expense.setApprovedBy(owner.getId());
            expense.setApprovedAt(LocalDateTime.now());
            // V64 — registramos descuento plataforma como crédito + neto real
            // que saldrá del bolsillo del dueño. Si no es PLATFORM, credit=0
            // y net=amount (back-compat con reportes).
            BigDecimal credit = t.getPlatformDiscountAmount() != null
                    && t.getPlatformDiscountAmount().signum() > 0
                    ? t.getPlatformDiscountAmount()
                    : BigDecimal.ZERO;
            BigDecimal net = q.getAmount().subtract(credit);
            if (net.signum() < 0) net = BigDecimal.ZERO; // defensa por si el descuento llega inflado
            expense.setPlatformCreditAmount(credit);
            expense.setNetExpenseAmount(net);
            expense.setDescription("Mantenimiento: " + t.getTitle()
                    + (credit.signum() > 0
                            ? " (descuento plataforma $" + formatAmount(credit)
                              + " → neto $" + formatAmount(net) + ")"
                            : ""));
            expense.setLinkedResourceType("MAINTENANCE_TICKET");
            expense.setLinkedResourceId(t.getId());
            expense.setProviderUserId(t.getAssignedProviderId());
            // Bloque 4 / Gap A: guardamos el PDF/JPG del presupuesto del proveedor
            // en el egreso. Sin esto el dueño no podía descargar el documento del
            // expediente sin saltar al módulo de cotizaciones.
            expense.setBudgetFileId(q.getEvidenceFileId());
            expenseRepository.save(expense);
        }

        // El template MAINTENANCE_PAYMENT_REQUIRED está dirigido al DUEÑO (plantilla 16).
        // Cuando se aprueba la cotización, el dueño necesita registrar el pago SPEI.
        UserEntity provider = userRepository.findById(t.getAssignedProviderId()).orElse(null);
        String propLabel = propLabel(t.getPropertyId());
        String discountNote = t.getPlatformDiscountAmount() != null && t.getPlatformDiscountAmount().signum() > 0
                ? "La plataforma absorbe 15% (crédito: $" + formatAmount(t.getPlatformDiscountAmount()) + ")."
                : "Sin descuento aplicable.";
        // Plantilla admindi_maintenance_payment_required_v1 (6 slots):
        //   {{1}}dueño {{2}}proveedor {{3}}monto {{4}}título {{5}}nota descuento {{6}}URL.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(owner));
        vars.put("2", provider != null ? nullSafe(provider.getName()) : "");
        vars.put("3", formatAmount(q.getAmount()));
        vars.put("4", t.getTitle());
        vars.put("5", discountNote);
        vars.put("6", appUrl + "/dashboard?panel=owner&ticket=" + t.getId());
        domainEventDispatcher.dispatch("MAINTENANCE_PAYMENT_REQUIRED",
                "Cotización aprobada — registra el pago SPEI",
                "Aprobaste la cotización de " + (provider != null ? provider.getName() : "el proveedor")
                        + " por $" + formatAmount(q.getAmount()) + ". " + discountNote
                        + " Sube el comprobante en: " + vars.get("6"),
                t.getOwnerId(), owner.getUsername(), collectOwnerAndAdminsRecipients(t.getOwnerId()), vars, null);

        // V59 — aviso al inquilino: la cotización fue aprobada, la reparación
        // está por proceder (no se comparte el monto para no exponer info
        // financiera del contrato con el proveedor).
        notifyTenantOfTicketProgress(t, "MAINTENANCE_QUOTE_APPROVED_TENANT",
                "Reparación aprobada",
                "Tu arrendador aprobó la cotización para \"" + t.getTitle()
                        + "\". El proveedor procederá con la reparación.");
        return t;
    }

    @Transactional
    public MaintenanceQuoteEntity ownerRejectQuote(String quoteId, String reason) {
        UserEntity owner = currentUser();
        MaintenanceQuoteEntity q = quoteRepository.findById(quoteId).orElseThrow();
        MaintenanceTicketEntity t = requireTicket(q.getTicketId());
        assertOwner(t, owner);
        if (!"SUBMITTED".equals(q.getStatus())) {
            throw new IllegalStateException("Esta cotización ya fue decidida.");
        }
        q.setStatus("REJECTED");
        quoteRepository.save(q);
        t.setStatus(MaintenanceTicketEntity.STATUS_ACCEPTED); // vuelve a aceptado para que provider cotice otra vez
        t.setRejectionReason(reason);
        ticketRepository.save(t);
        return q;
    }

    // ─── 6. Owner registra pago y espera confirmacion del proveedor ──────────

    /**
     * V63 — Ciclo de pago al proveedor con confirmación bidireccional.
     *
     * <p>Antes este método cerraba el ticket como COMPLETED de inmediato al
     * subir el SPEI. El proveedor podía NO recibir el dinero y enterarse sólo
     * por notificación, sin manera limpia de disputar. Ahora el flujo es:</p>
     * <ol>
     *   <li>Owner registra pago + comprobante → ticket pasa a
     *       {@code AWAITING_PROVIDER_CONFIRMATION}, expense queda
     *       {@code PENDING_PROVIDER_CONFIRMATION}.</li>
     *   <li>Provider ve el SPEI en su panel y confirma
     *       ({@link #providerConfirmPayment}) → ticket COMPLETED, expense PAID.
     *       O disputa ({@link #providerDisputePayment}) → ticket vuelve a
     *       APPROVED para que el dueño reintente, expense VOID con motivo.</li>
     * </ol>
     *
     * <p>Esto evita el escenario "contabilidad marcada como pagada cuando en
     * realidad el SPEI no llegó".</p>
     */
    @Transactional
    public MaintenanceTicketEntity ownerRecordPayment(String ticketId, BigDecimal paidAmount,
                                                       String speiProofFileId) {
        UserEntity owner = currentUser();
        MaintenanceTicketEntity t = requireTicket(ticketId);
        assertOwner(t, owner);
        if (!MaintenanceTicketEntity.STATUS_APPROVED.equals(t.getStatus())) {
            throw new IllegalStateException("El ticket no está APROBADO para pago.");
        }
        if (paidAmount == null || paidAmount.signum() <= 0) {
            throw new IllegalArgumentException("Monto pagado obligatorio.");
        }
        // Defensa IDOR del comprobante SPEI.
        if (speiProofFileId != null && !speiProofFileId.isBlank()) {
            fileOwnership.assertUploader(speiProofFileId, owner.getId());
            fileOwnership.markConsumed(speiProofFileId, "MAINTENANCE_TICKET", t.getId());
        }

        // Expense preexistente (creado en ownerApproveQuote); fallback legacy.
        ExpenseEntity expense = expenseRepository
                .findFirstByLinkedResourceTypeAndLinkedResourceIdOrderByCreatedAtDesc(
                        "MAINTENANCE_TICKET", t.getId())
                .orElse(null);
        if (expense == null) {
            expense = new ExpenseEntity();
            expense.setOwnerId(t.getOwnerId());
            expense.setPropertyId(t.getPropertyId());
            expense.setType("MAINTENANCE");
            expense.setAmount(paidAmount);
            expense.setApprovedAmount(paidAmount);
            expense.setDescription("Mantenimiento: " + t.getTitle()
                    + (t.getPlatformDiscountAmount() != null && t.getPlatformDiscountAmount().signum() > 0
                            ? " (descuento plataforma $" + t.getPlatformDiscountAmount() + ")"
                            : ""));
            expense.setLinkedResourceType("MAINTENANCE_TICKET");
            expense.setLinkedResourceId(t.getId());
            expense.setProviderUserId(t.getAssignedProviderId());
        }
        // V63 — NO marcamos PAID aún. Dejamos el comprobante y monto pero el
        // settlement queda PENDING_PROVIDER_CONFIRMATION hasta que el proveedor
        // confirme recepción. No seteamos paidAt tampoco (sería engañoso).
        expense.setStatus("APPROVED");
        expense.setPaidAmount(paidAmount); // registramos el monto enviado
        expense.setPaymentSettlementStatus("PENDING_PROVIDER_CONFIRMATION");
        expense.setPaymentProofFileId(speiProofFileId);
        if (expense.getEvidenceFileId() == null) {
            expense.setEvidenceFileId(speiProofFileId);
        }
        expenseRepository.save(expense);

        t.setStatus(MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_CONFIRMATION);
        // limpiamos rejectionReason heredado de disputas previas para no confundir
        t.setRejectionReason(null);
        ticketRepository.save(t);

        // Notificación al proveedor: debe confirmar (o disputar) que recibió.
        domainEventDispatcher.dispatch("MAINTENANCE_PAYMENT_AWAITING_CONFIRMATION",
                "Confirma recepción de pago",
                "El dueño registró un SPEI por $" + formatAmount(paidAmount)
                        + " para el ticket \"" + t.getTitle() + "\". "
                        + "Revisa tu cuenta y confirma o disputa desde tu panel.",
                t.getOwnerId(), owner.getUsername(),
                List.of(t.getAssignedProviderId()), null);
        return t;
    }

    /**
     * Alias back-compat para callers anteriores que invocaban el nombre viejo
     * {@code ownerPayAndClose}. El cierre real del ticket ahora depende de la
     * confirmación del proveedor en {@link #providerConfirmPayment}.
     */
    @Deprecated
    @Transactional
    public MaintenanceTicketEntity ownerPayAndClose(String ticketId, BigDecimal paidAmount,
                                                    String speiProofFileId) {
        return ownerRecordPayment(ticketId, paidAmount, speiProofFileId);
    }

    /**
     * V63 — El proveedor confirma que recibió el SPEI. Cierra el ciclo:
     * ticket COMPLETED, expense PAID, notificación al dueño y al inquilino.
     */
    @Transactional
    public MaintenanceTicketEntity providerConfirmPayment(String ticketId) {
        UserEntity provider = currentProvider();
        MaintenanceTicketEntity t = requireTicket(ticketId);
        if (t.getAssignedProviderId() == null
                || !t.getAssignedProviderId().equals(provider.getId())) {
            throw new SecurityException("Este ticket no está asignado a ti.");
        }
        if (!MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_CONFIRMATION.equals(t.getStatus())) {
            throw new IllegalStateException("El ticket no está esperando tu confirmación de pago.");
        }

        ExpenseEntity expense = expenseRepository
                .findFirstByLinkedResourceTypeAndLinkedResourceIdOrderByCreatedAtDesc(
                        "MAINTENANCE_TICKET", t.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "No hay egreso asociado al ticket — no se puede confirmar pago."));

        expense.setStatus("PAID");
        expense.setPaidAt(LocalDateTime.now());
        expense.setOutstandingAmount(BigDecimal.ZERO);
        expense.setPaymentSettlementStatus("PAID");
        expense.setProviderConfirmationStatus("CONFIRMED");
        expenseRepository.save(expense);

        t.setStatus(MaintenanceTicketEntity.STATUS_COMPLETED);
        t.setResolvedAt(LocalDateTime.now());
        ticketRepository.save(t);

        BigDecimal amount = expense.getPaidAmount() != null
                ? expense.getPaidAmount() : BigDecimal.ZERO;

        // Notificación al dueño/admins con el cierre confirmado por el proveedor.
        domainEventDispatcher.dispatch("MAINTENANCE_PAYMENT_CONFIRMED_BY_PROVIDER",
                "Pago confirmado por el proveedor",
                nullSafe(provider.getName()) + " confirmó recibir el pago de $"
                        + formatAmount(amount) + " por el ticket \"" + t.getTitle() + "\". "
                        + "Expediente contable cerrado.",
                t.getOwnerId(), provider.getUsername(),
                collectOwnerAndAdminsRecipients(t.getOwnerId()), null);

        // Aviso al inquilino del cierre del ticket (reparación completada).
        notifyTenantOfTicketProgress(t, "MAINTENANCE_TICKET_CLOSED_TENANT",
                "Reporte resuelto",
                "Tu reporte \"" + t.getTitle() + "\" fue resuelto y pagado. "
                        + "Si algo no quedó como esperabas, contacta a tu arrendador.");
        return t;
    }

    /**
     * V63 — El proveedor disputa el pago (no lo vio, monto incorrecto, etc.).
     * El ticket regresa a APPROVED para que el dueño reintente el pago, y el
     * expense queda VOID con el motivo. Se notifica al dueño con el detalle.
     */
    @Transactional
    public MaintenanceTicketEntity providerDisputePayment(String ticketId, String reason) {
        UserEntity provider = currentProvider();
        MaintenanceTicketEntity t = requireTicket(ticketId);
        if (t.getAssignedProviderId() == null
                || !t.getAssignedProviderId().equals(provider.getId())) {
            throw new SecurityException("Este ticket no está asignado a ti.");
        }
        if (!MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_CONFIRMATION.equals(t.getStatus())) {
            throw new IllegalStateException("El ticket no está esperando tu confirmación de pago.");
        }
        String safeReason = (reason != null && !reason.isBlank())
                ? reason.trim()
                : "El proveedor no vio el pago en su cuenta.";

        ExpenseEntity expense = expenseRepository
                .findFirstByLinkedResourceTypeAndLinkedResourceIdOrderByCreatedAtDesc(
                        "MAINTENANCE_TICKET", t.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "No hay egreso asociado al ticket — no se puede disputar pago."));

        // V63 — marcamos el expense como VOID pero conservamos el registro
        // (incluyendo el comprobante SPEI del intento fallido) para auditoría.
        expense.setStatus("VOID");
        expense.setPaidAmount(BigDecimal.ZERO); // el pago "no ocurrió" desde contabilidad
        expense.setPaidAt(null);
        expense.setOutstandingAmount(expense.getApprovedAmount() != null
                ? expense.getApprovedAmount() : expense.getAmount());
        expense.setPaymentSettlementStatus("DISPUTED");
        expense.setProviderConfirmationStatus("DISPUTED");
        expenseRepository.save(expense);

        t.setStatus(MaintenanceTicketEntity.STATUS_APPROVED);
        t.setRejectionReason("[DISPUTA PAGO] " + safeReason);
        ticketRepository.save(t);

        domainEventDispatcher.dispatch("MAINTENANCE_PAYMENT_DISPUTED_BY_PROVIDER",
                "El proveedor disputó el pago",
                nullSafe(provider.getName()) + " reportó que el pago del ticket \""
                        + t.getTitle() + "\" no llegó correctamente. Motivo: " + safeReason
                        + ". Revisa tu estado de cuenta y registra de nuevo el pago.",
                t.getOwnerId(), provider.getUsername(),
                collectOwnerAndAdminsRecipients(t.getOwnerId()), null);
        return t;
    }

    /**
     * V63 — Lista tickets en {@code AWAITING_PROVIDER_CONFIRMATION} para el
     * proveedor autenticado. El provider panel los muestra en un tab dedicado
     * "Confirmar pagos recibidos".
     */
    @Transactional(readOnly = true)
    public List<MaintenanceTicketEntity> listAwaitingPaymentConfirmationForProvider() {
        UserEntity provider = currentProvider();
        return ticketRepository
                .findByAssignedProviderIdOrderByCreatedAtDesc(provider.getId()).stream()
                .filter(t -> MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_CONFIRMATION
                        .equals(t.getStatus()))
                .toList();
    }

    /**
     * V63 — Igual que el anterior pero para el dueño: tickets cuyos pagos
     * registró y que esperan confirmación del proveedor. Se usa en la nueva
     * tab "Pagos esperando confirmación" del Owner Workflow Inbox.
     */
    @Transactional(readOnly = true)
    public List<MaintenanceTicketEntity> listAwaitingPaymentConfirmationForOwner() {
        String ownerId = currentUser().getId();
        return ticketRepository.findByOwnerIdAndStatus(
                ownerId, MaintenanceTicketEntity.STATUS_AWAITING_PROVIDER_CONFIRMATION);
    }

    /**
     * V63 — Devuelve el {@code payment_proof_file_id} (comprobante SPEI del
     * dueño) guardado en el expense asociado al ticket. Lo usa el panel del
     * proveedor para mostrar el comprobante al decidir si confirma o disputa.
     *
     * <p>Guard: solo el proveedor asignado al ticket o el owner pueden
     * obtenerlo, para no exponer el path a terceros. Devuelve {@code null}
     * si el expense aún no tiene comprobante.</p>
     */
    @Transactional(readOnly = true)
    public String getPaymentProofFileIdForParty(String ticketId) {
        UserEntity caller = currentUser();
        MaintenanceTicketEntity t = requireTicket(ticketId);
        boolean isProvider = caller.getRole() == Role.MAINTENANCE_PROVIDER
                && caller.getId().equals(t.getAssignedProviderId());
        boolean isOwner = caller.getRole() == Role.OWNER
                && caller.getId().equals(t.getOwnerId());
        boolean isSuper = caller.getRole() == Role.SUPER_ADMIN;
        if (!(isProvider || isOwner || isSuper)) {
            throw new SecurityException("No tienes acceso al comprobante de este ticket.");
        }
        return expenseRepository
                .findFirstByLinkedResourceTypeAndLinkedResourceIdOrderByCreatedAtDesc(
                        "MAINTENANCE_TICKET", ticketId)
                .map(ExpenseEntity::getPaymentProofFileId)
                .orElse(null);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * V59 — Recipients estándar para eventos dirigidos a la organización del
     * dueño: {@code [ownerId, ...propertyAdminUserIds]}. El property admin se
     * determina por {@code owner_memberships} + filtro {@code role=PROPERTY_ADMIN}
     * (memberships también existen para ACCOUNTANT pero no es relevante para
     * autorización operativa de mantenimiento).
     */
    private List<String> collectOwnerAndAdminsRecipients(String ownerId) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (ownerId != null && !ownerId.isBlank()) out.add(ownerId);
        try {
            List<OwnerMembershipEntity> memberships =
                    ownerMembershipRepository.findByOwnerId(ownerId);
            if (memberships != null) {
                for (OwnerMembershipEntity m : memberships) {
                    userRepository.findById(m.getUserId()).ifPresent(u -> {
                        if (u.getRole() == Role.PROPERTY_ADMIN && u.isActive()) {
                            out.add(u.getId());
                        }
                    });
                }
            }
        } catch (Exception ex) {
            logger.warn("[MAINTENANCE] No pude enumerar property admins del dueño {}: {}",
                    ownerId, ex.getClass().getSimpleName());
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * V59 — Notifica al inquilino del ticket usando body libre (sin plantilla
     * WhatsApp pre-aprobada; cae a email/in-app si WhatsApp no tiene plantilla
     * para el evento). Es una función best-effort: cualquier fallo se loggea
     * como debug y NO interrumpe el flujo principal del ticket.
     */
    private void notifyTenantOfTicketProgress(MaintenanceTicketEntity t, String eventKey,
                                               String title, String body) {
        try {
            if (t.getTenantProfileId() == null) return;
            String tenantUserId = tenantProfileRepository.findById(t.getTenantProfileId())
                    .map(TenantProfileEntity::getUserId)
                    .orElse(null);
            if (tenantUserId == null) return;
            domainEventDispatcher.dispatch(eventKey, title, body,
                    t.getOwnerId(), "SYSTEM", List.of(tenantUserId), null);
        } catch (Exception ex) {
            logger.debug("[MAINTENANCE] notify tenant failed eventKey={} ticket={}: {}",
                    eventKey, t.getId(), ex.getClass().getSimpleName());
        }
    }

    private void notifyProviderOfTicket(MaintenanceTicketEntity t, UserEntity provider) {
        String propLabel = propLabel(t.getPropertyId());
        // Plantilla admindi_maintenance_ticket_assigned_v1:
        //   {{1}}proveedor {{2}}inmueble {{3}}título {{4}}urgencia {{5}}URL portal provider.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(provider));
        vars.put("2", propLabel);
        vars.put("3", t.getTitle());
        vars.put("4", t.getUrgency() != null ? t.getUrgency() : "NORMAL");
        vars.put("5", appUrl + "/dashboard?ticket=" + t.getId());
        domainEventDispatcher.dispatch("MAINTENANCE_TICKET_ASSIGNED",
                "Nuevo ticket de mantenimiento",
                "Inmueble: " + propLabel + ". Reporte: " + t.getTitle() + ". "
                        + "Tienes 72h para aceptar o rechazar en: " + vars.get("5"),
                t.getOwnerId(), null, List.of(provider.getId()), vars, null);
    }

    /**
     * V67 — Estrictamente para proveedores <b>PLATFORM</b>: los que llegan del
     * catálogo oficial de la plataforma (assignment_source = 'PLATFORM'). Los
     * agentes/proveedores PRIVATE creados por el dueño <b>no</b> reciben el
     * descuento del 15% — el descuento es un beneficio que la plataforma absorbe
     * por usar sus agentes, no aplica a contratistas privados del dueño.
     *
     * <p>Regla de negocio del operador: "solo hay descuento por plataforma en
     * agentes inmobiliarios de plataforma y agentes de mantenimiento de
     * plataforma". Esto alinea contabilidad y reportes (expenses.platform_credit)
     * con la realidad económica — no se promete un crédito que la plataforma
     * nunca absorbió.</p>
     */
    private boolean isPlatformProvider(String ownerId, String providerUserId) {
        if (providerUserId == null) return false;
        List<PlatformProviderAssignmentEntity> list = platformProviderRepository.findByOwnerId(ownerId);
        return list.stream()
                .filter(PlatformProviderAssignmentEntity::isActive)
                .filter(a -> "PLATFORM".equalsIgnoreCase(a.getAssignmentSource()))
                .anyMatch(a -> providerUserId.equals(a.getProviderId()));
    }

    private void assertOwner(MaintenanceTicketEntity t, UserEntity user) {
        if (user.getRole() == Role.SUPER_ADMIN) return;
        if (user.getRole() != Role.OWNER && user.getRole() != Role.PROPERTY_ADMIN) {
            throw new SecurityException("Acción reservada al dueño o admin.");
        }
        if (user.getRole() == Role.OWNER && !t.getOwnerId().equals(user.getId())) {
            throw new SecurityException("Este ticket no es tuyo.");
        }
    }

    private UserEntity currentProvider() {
        UserEntity u = currentUser();
        if (u.getRole() != Role.MAINTENANCE_PROVIDER) {
            throw new SecurityException("Acción reservada al proveedor de mantenimiento.");
        }
        return u;
    }

    private MaintenanceTicketEntity requireTicket(String id) {
        return ticketRepository.findById(id).orElseThrow(() ->
                new IllegalArgumentException("Ticket no encontrado: " + id));
    }

    private List<String> tenantRecipients(MaintenanceTicketEntity t) {
        if (t.getTenantProfileId() == null) return List.of(t.getOwnerId());
        return tenantProfileRepository.findById(t.getTenantProfileId())
                .map(tp -> List.of(tp.getUserId()))
                .orElseGet(() -> List.of(t.getOwnerId()));
    }

    // ─── Helpers de plantilla ─────────────────────────────────────────────────────

    private String firstName(UserEntity u) {
        if (u == null || u.getName() == null || u.getName().isBlank()) return "";
        return u.getName().trim().split("\\s+")[0];
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String propLabel(String propertyId) {
        if (propertyId == null) return "Inmueble";
        return propertyRepository.findById(propertyId).map(PropertyEntity::getName).orElse("Inmueble");
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String serializeFileIds(List<String> fileIds) {
        try {
            return objectMapper.writeValueAsString(fileIds);
        } catch (JsonProcessingException e) {
            logger.warn("No pude serializar photoFileIds {}", fileIds, e);
            return null;
        }
    }
}
