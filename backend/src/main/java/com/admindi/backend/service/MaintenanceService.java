package com.admindi.backend.service;

import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.model.MaintenanceQuoteEntity;
import com.admindi.backend.model.MaintenanceTicketEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.PropertyMovementEventType;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.ActionTaskRepository;
import com.admindi.backend.repository.ExpenseRepository;
import com.admindi.backend.repository.MaintenanceQuoteRepository;
import com.admindi.backend.repository.MaintenanceTicketRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MaintenanceService {

    private final MaintenanceTicketRepository ticketRepository;
    private final MaintenanceQuoteRepository quoteRepository;
    private final ExpenseRepository expenseRepository;
    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final PropertyMovementService propertyMovementService;
    private final ProviderAgentRoutingService providerAgentRoutingService;
    private final ActionTaskRepository actionTaskRepository;
    private final DomainEventDispatcher domainEventDispatcher;
    private final PropertyRepository propertyRepository;
    private final PermissionService permissionService;

    @Value("${app.url:https://app.admindi.com}")
    private String appUrl;

    @Autowired
    public MaintenanceService(MaintenanceTicketRepository ticketRepository,
                              MaintenanceQuoteRepository quoteRepository,
                              ExpenseRepository expenseRepository,
                              UserRepository userRepository,
                              TenantProfileRepository tenantProfileRepository,
                              PropertyMovementService propertyMovementService,
                              ProviderAgentRoutingService providerAgentRoutingService,
                              ActionTaskRepository actionTaskRepository,
                              DomainEventDispatcher domainEventDispatcher,
                              PropertyRepository propertyRepository,
                              PermissionService permissionService) {
        this.ticketRepository = ticketRepository;
        this.quoteRepository = quoteRepository;
        this.expenseRepository = expenseRepository;
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.propertyMovementService = propertyMovementService;
        this.providerAgentRoutingService = providerAgentRoutingService;
        this.actionTaskRepository = actionTaskRepository;
        this.domainEventDispatcher = domainEventDispatcher;
        this.propertyRepository = propertyRepository;
        this.permissionService = permissionService;
    }

    private UserEntity current() {
        return userRepository.findByLoginIdentifier(SecurityContextHolder.getContext().getAuthentication().getName()).orElseThrow();
    }

    /**
     * Owner de organización activo para APIs de mantenimiento.
     * SUPER_ADMIN debe operar con {@code ownerId} del JWT (contexto), nunca con su propio user id.
     */
    private String maintenanceOrganizationOwnerId(UserEntity u) {
        if (u.getRole() == Role.SUPER_ADMIN) {
            String ctx = TenantContext.getCurrentOwner();
            if (ctx == null || ctx.isBlank()) {
                throw new RuntimeException(
                        "Seleccione contexto de organización (ownerId en el token) para operar mantenimiento.");
            }
            return ctx;
        }
        return TenantContext.resolveOwnerId(userRepository);
    }

    /** Evita que SUPER_ADMIN apruebe/rechace/pague fuera del owner activo en el token. */
    private void assertSuperAdminContextMatchesOrganization(UserEntity u, String organizationOwnerId) {
        if (u.getRole() != Role.SUPER_ADMIN) {
            return;
        }
        String ctx = TenantContext.getCurrentOwner();
        if (ctx == null || ctx.isBlank() || !ctx.equals(organizationOwnerId)) {
            throw new RuntimeException("IDOR o contexto de organización inválido para SUPER_ADMIN.");
        }
    }

    /**
     * SUPER_ADMIN: el proveedor efectivo debe existir, ser MAINTENANCE_PROVIDER activo, y coincidir con el
     * asignado al ticket o estar vinculado a la organización del ticket (platform_provider_assignments).
     */
    private void validateSuperAdminQuoteProvider(MaintenanceTicketEntity ticket, String resolvedProviderId) {
        UserEntity providerUser = userRepository.findById(resolvedProviderId).orElseThrow(() ->
                new RuntimeException("providerId no existe o no es un usuario válido."));
        if (!providerUser.isActive()) {
            throw new RuntimeException("El proveedor indicado (providerId) está inactivo.");
        }
        if (providerUser.getRole() != Role.MAINTENANCE_PROVIDER) {
            throw new RuntimeException("providerId debe referir a un usuario con rol MAINTENANCE_PROVIDER.");
        }
        String assigned = ticket.getAssignedProviderId();
        boolean sameAsAssigned = assigned != null && !assigned.isBlank() && assigned.equals(resolvedProviderId);
        boolean linkedToOwner = providerAgentRoutingService.isLinkedMaintenanceProviderForOwner(
                ticket.getOwnerId(), resolvedProviderId);
        if (!sameAsAssigned && !linkedToOwner) {
            throw new RuntimeException(
                    "providerId debe ser el proveedor asignado al ticket o un proveedor de mantenimiento "
                            + "vinculado (activo) a la organización del ticket.");
        }
    }

    @Transactional
    public MaintenanceTicketEntity createTicket(String propertyId, String title, String description, String urgency,
                                                String tenantProfileId) {
        UserEntity u = current();
        if (u.getRole() != Role.TENANT) {
            throw new RuntimeException("Only tenants open tickets.");
        }
        String oid = TenantContext.resolveOwnerId(userRepository);
        List<TenantProfileEntity> matches = tenantProfileRepository.findByUserIdAndOwnerIdAndArchivedAtIsNull(u.getId(), oid).stream()
                .filter(p -> propertyId.equals(p.getPropertyId()))
                .collect(Collectors.toList());
        TenantProfileEntity profile;
        if (tenantProfileId != null && !tenantProfileId.isBlank()) {
            profile = matches.stream().filter(p -> p.getId().equals(tenantProfileId)).findFirst()
                    .orElseThrow(() -> new RuntimeException("Expediente no válido para este inmueble y organización."));
        } else if (matches.size() == 1) {
            profile = matches.get(0);
        } else if (matches.size() > 1) {
            throw new RuntimeException("Indique tenantProfileId: hay varios expedientes en este inmueble.");
        } else {
            throw new RuntimeException(
                    "No tiene expediente en este inmueble para la organización seleccionada.");
        }
        MaintenanceTicketEntity t = new MaintenanceTicketEntity();
        t.setOwnerId(profile.getOwnerId());
        t.setPropertyId(propertyId);
        t.setTenantProfileId(profile.getId());
        t.setTitle(title);
        t.setDescription(description);
        t.setUrgency(urgency != null ? urgency : "NORMAL");
        t.setStatus("OPEN");
        MaintenanceTicketEntity saved = ticketRepository.save(t);
        propertyMovementService.record(profile.getOwnerId(), propertyId, "MAINTENANCE_TICKET", saved.getId(),
                u.getId(), u.getRole().name(), PropertyMovementEventType.MAINTENANCE_TICKET,
                "Maintenance ticket", title,
                LocalDateTime.now(), null, null);
        routeMaintenanceTicketAfterCreation(saved, u);
        return ticketRepository.findById(saved.getId()).orElse(saved);
    }

    private void routeMaintenanceTicketAfterCreation(MaintenanceTicketEntity ticket, UserEntity actor) {
        Optional<String> providerId = providerAgentRoutingService.resolveMaintenanceProviderId(ticket.getOwnerId());
        String propLabel = propertyRepository.findById(ticket.getPropertyId()).map(PropertyEntity::getName).orElse(ticket.getPropertyId());
        if (providerId.isPresent()) {
            ticket.setAssignedProviderId(providerId.get());
            ticket.setStatus("AWAITING_PROVIDER_ACCEPT");
            ticketRepository.save(ticket);
            UserEntity provider = userRepository.findById(providerId.get()).orElseThrow();
            propertyMovementService.record(ticket.getOwnerId(), ticket.getPropertyId(), "MAINTENANCE_TICKET", ticket.getId(),
                    actor.getId(), actor.getRole().name(), PropertyMovementEventType.MAINTENANCE_PROVIDER_ASSIGNED,
                    "Proveedor de mantenimiento asignado", provider.getName(),
                    LocalDateTime.now(), "{\"providerId\":\"" + provider.getId() + "\"}", null);
            createActionTask(provider.getId(), ticket.getOwnerId(), "MAINTENANCE_TICKET_ASSIGNED",
                    "Ticket de mantenimiento: " + ticket.getTitle(),
                    "Inmueble: " + propLabel + ". " + (ticket.getDescription() != null ? ticket.getDescription() : ""),
                    "MAINTENANCE_TICKET", ticket.getId());
            // Plantilla admindi_maintenance_ticket_assigned_v1 (destinatario proveedor):
            //   {{1}}proveedor {{2}}inmueble {{3}}asunto {{4}}urgencia {{5}}URL portal proveedor.
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("1", firstName(provider));
            vars.put("2", propLabel);
            vars.put("3", nullSafe(ticket.getTitle()));
            vars.put("4", nullSafe(ticket.getUrgency()));
            vars.put("5", appUrl + "/dashboard?panel=maintenance&ticket=" + ticket.getId());
            domainEventDispatcher.dispatch("MAINTENANCE_TICKET_ASSIGNED",
                    "Nuevo ticket de mantenimiento",
                    "Propiedad: " + propLabel + ". " + ticket.getTitle(),
                    ticket.getOwnerId(), actor.getUsername(), List.of(provider.getId()), vars, null);
        } else {
            propertyMovementService.record(ticket.getOwnerId(), ticket.getPropertyId(), "MAINTENANCE_TICKET", ticket.getId(),
                    actor.getId(), actor.getRole().name(), PropertyMovementEventType.MAINTENANCE_PROVIDER_ASSIGNMENT_PENDING,
                    "Sin proveedor de mantenimiento configurado",
                    "Asigne un proveedor ligado a su cuenta o contrate uno en la plataforma.",
                    LocalDateTime.now(), "{\"ticketId\":\"" + ticket.getId() + "\"}", null);
            createActionTask(ticket.getOwnerId(), ticket.getOwnerId(), "MAINTENANCE_PROVIDER_NEEDED",
                    "Asignar proveedor de mantenimiento",
                    "Hay un ticket sin proveedor: " + ticket.getTitle() + " (" + propLabel + ").",
                    "MAINTENANCE_TICKET", ticket.getId());
            domainEventDispatcher.dispatch("MAINTENANCE_PROVIDER_NEEDED",
                    "Mantenimiento: falta proveedor",
                    "Ticket: " + ticket.getTitle() + " en " + propLabel,
                    ticket.getOwnerId(), actor.getUsername(), ownerAndAdminRecipientIds(ticket.getOwnerId()), null);
        }
    }

    private void createActionTask(String assigneeUserId, String ownerId, String eventType, String title,
                                  String description, String resourceType, String resourceId) {
        ActionTaskEntity task = new ActionTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setUserId(assigneeUserId);
        task.setOwnerId(ownerId);
        task.setEventType(eventType);
        task.setTitle(title);
        task.setDescription(description);
        task.setResourceType(resourceType);
        task.setResourceId(resourceId);
        task.setStatus("OPEN");
        task.setCreatedAt(LocalDateTime.now());
        actionTaskRepository.save(task);
    }

    private List<String> ownerAndAdminRecipientIds(String ownerId) {
        List<String> out = new ArrayList<>();
        out.add(ownerId);
        userRepository.findByOwnerId(ownerId).stream()
                .filter(u -> u.getRole() == Role.PROPERTY_ADMIN && u.isActive())
                .map(UserEntity::getId)
                .distinct()
                .filter(id -> !out.contains(id))
                .forEach(out::add);
        return out;
    }

    private boolean hasPerm(UserEntity u, String ownerId, String perm) {
        if (u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.OWNER) {
            return true;
        }
        return permissionService.resolvePermissions(u.getId(), ownerId).contains(perm);
    }

    private boolean canReadMaintenance(UserEntity u, String ownerId) {
        if (u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.OWNER) {
            return true;
        }
        if (u.getRole() == Role.ACCOUNTANT || u.getRole() == Role.PROPERTY_ADMIN) {
            return hasPerm(u, ownerId, "maintenance:tickets:read");
        }
        return false;
    }

    @Transactional
    public MaintenanceTicketEntity acceptTicket(String ticketId) {
        UserEntity u = current();
        if (u.getRole() != Role.MAINTENANCE_PROVIDER) {
            throw new RuntimeException("Only the maintenance provider can accept the ticket.");
        }
        MaintenanceTicketEntity ticket = ticketRepository.findById(ticketId).orElseThrow();
        if (ticket.getAssignedProviderId() == null || !ticket.getAssignedProviderId().equals(u.getId())) {
            throw new RuntimeException("Ticket not assigned to this provider.");
        }
        if (!"AWAITING_PROVIDER_ACCEPT".equals(ticket.getStatus())) {
            throw new RuntimeException("Ticket is not awaiting provider acceptance.");
        }
        ticket.setStatus("ACCEPTED");
        ticket.setProviderAcceptedAt(LocalDateTime.now());
        ticketRepository.save(ticket);
        propertyMovementService.record(ticket.getOwnerId(), ticket.getPropertyId(), "MAINTENANCE_TICKET", ticket.getId(),
                u.getId(), u.getRole().name(), PropertyMovementEventType.MAINTENANCE_TICKET_ACCEPTED,
                "Proveedor acepto el ticket", ticket.getTitle(),
                LocalDateTime.now(), "{\"ticketId\":\"" + ticket.getId() + "\"}", null);
        domainEventDispatcher.dispatch("MAINTENANCE_TICKET_ACCEPTED",
                "Proveedor acepto mantenimiento",
                ticket.getTitle(),
                ticket.getOwnerId(), u.getUsername(), List.of(ticket.getOwnerId()), null);
        for (ActionTaskEntity t : actionTaskRepository.findByResourceTypeAndResourceId("MAINTENANCE_TICKET", ticket.getId())) {
            if ("OPEN".equals(t.getStatus())) {
                t.setStatus("DONE");
                t.setResolvedAt(LocalDateTime.now());
                actionTaskRepository.save(t);
            }
        }
        return ticketRepository.findById(ticket.getId()).orElse(ticket);
    }

    public List<MaintenanceTicketEntity> listTicketsForCurrentProvider() {
        UserEntity u = current();
        if (u.getRole() != Role.MAINTENANCE_PROVIDER) {
            throw new RuntimeException("Only maintenance providers can list assigned tickets.");
        }
        return ticketRepository.findByAssignedProviderIdOrderByCreatedAtDesc(u.getId());
    }

    public List<MaintenanceTicketEntity> listTicketsForOrganization(String propertyId) {
        UserEntity u = current();
        String oid;
        oid = maintenanceOrganizationOwnerId(u);
        if (!canReadMaintenance(u, oid)) {
            throw new RuntimeException("No permission to list maintenance tickets.");
        }
        if (propertyId != null && !propertyId.isBlank()) {
            return ticketRepository.findByOwnerIdAndPropertyIdOrderByCreatedAtDesc(oid, propertyId.trim());
        }
        return ticketRepository.findByOwnerIdOrderByCreatedAtDesc(oid);
    }

    /**
     * Legacy submit-quote (endpoint {@code /api/maintenance/quotes}). Soporta
     * ambos roles (MAINTENANCE_PROVIDER y SUPER_ADMIN) y acepta
     * {@code visitNotes} opcional con el mismo contrato que
     * {@link MaintenanceWorkflowService#providerSubmitQuote}: si el proveedor
     * aclara algo de la visita, se guarda en la cotización y aparece en el
     * panel del dueño. Se mantiene la variante de 5 parámetros para
     * compatibilidad con cualquier caller legacy.
     */
    @Transactional
    public MaintenanceQuoteEntity submitQuote(String ticketId, BigDecimal amount, String description,
                                              String evidenceFileId, String providerId) {
        return submitQuote(ticketId, amount, description, evidenceFileId, providerId, null);
    }

    @Transactional
    public MaintenanceQuoteEntity submitQuote(String ticketId, BigDecimal amount, String description,
                                              String evidenceFileId, String providerId,
                                              String visitNotes) {
        UserEntity u = current();
        if (u.getRole() != Role.MAINTENANCE_PROVIDER && u.getRole() != Role.SUPER_ADMIN) {
            throw new RuntimeException("Only maintenance providers submit quotes.");
        }
        MaintenanceTicketEntity ticket = ticketRepository.findById(ticketId).orElseThrow();
        if (u.getRole() == Role.MAINTENANCE_PROVIDER) {
            if (ticket.getAssignedProviderId() == null || !ticket.getAssignedProviderId().equals(u.getId())) {
                throw new RuntimeException("Ticket not assigned to this provider.");
            }
        } else if (!ticket.getOwnerId().equals(maintenanceOrganizationOwnerId(u))) {
            throw new RuntimeException("IDOR: el ticket no pertenece al contexto de organización activo.");
        }
        if (!"ACCEPTED".equals(ticket.getStatus())) {
            throw new RuntimeException(
                    "Solo se puede cotizar con ticket en estado ACCEPTED (ticket actual: " + ticket.getStatus() + ").");
        }
        String resolvedProviderId;
        if (u.getRole() == Role.MAINTENANCE_PROVIDER) {
            // Nunca confiar en providerId del body: siempre el actor autenticado.
            resolvedProviderId = u.getId();
        } else {
            // SUPER_ADMIN: opcionalmente puede fijar proveedor (soporte); si no, el asignado al ticket.
            if (providerId != null && !providerId.isBlank()) {
                resolvedProviderId = providerId.trim();
            } else if (ticket.getAssignedProviderId() != null && !ticket.getAssignedProviderId().isBlank()) {
                resolvedProviderId = ticket.getAssignedProviderId();
            } else {
                throw new RuntimeException(
                        "Indique providerId en el cuerpo o asegure un proveedor asignado al ticket.");
            }
            validateSuperAdminQuoteProvider(ticket, resolvedProviderId);
        }
        MaintenanceQuoteEntity q = new MaintenanceQuoteEntity();
        q.setTicketId(ticketId);
        q.setProviderId(resolvedProviderId);
        q.setAmount(amount);
        q.setDescription(description);
        q.setVisitNotes(visitNotes != null && !visitNotes.isBlank() ? visitNotes.trim() : null);
        q.setEvidenceFileId(evidenceFileId);
        q.setStatus("SUBMITTED");
        MaintenanceQuoteEntity saved = quoteRepository.save(q);
        ticket.setStatus("QUOTED");
        ticketRepository.save(ticket);
        propertyMovementService.record(ticket.getOwnerId(), ticket.getPropertyId(), "MAINTENANCE_QUOTE", saved.getId(),
                u.getId(), u.getRole().name(), PropertyMovementEventType.MAINTENANCE_QUOTE,
                "Maintenance quote submitted", amount.toPlainString(),
                LocalDateTime.now(), null, evidenceFileId);
        return saved;
    }

    @Transactional
    public void approveQuote(String quoteId) {
        UserEntity u = current();
        MaintenanceQuoteEntity q = quoteRepository.findById(quoteId).orElseThrow();
        MaintenanceTicketEntity ticket = ticketRepository.findById(q.getTicketId()).orElseThrow();
        String oid = ticket.getOwnerId();
        assertSuperAdminContextMatchesOrganization(u, oid);
        if (!hasPerm(u, oid, "QUOTE_APPROVE")) {
            throw new RuntimeException("No permission to approve maintenance quotes.");
        }
        if (!"SUBMITTED".equals(q.getStatus())) {
            throw new RuntimeException(
                    "Solo se puede aprobar una cotización en estado SUBMITTED (estado actual: " + q.getStatus() + ").");
        }
        if (!"QUOTED".equals(ticket.getStatus())) {
            throw new RuntimeException(
                    "El ticket debe estar en QUOTED para aprobar (estado actual: " + ticket.getStatus() + ").");
        }
        if (expenseRepository.existsByLinkedResourceTypeAndLinkedResourceId("MAINTENANCE_QUOTE", quoteId)) {
            throw new RuntimeException("Ya existe un egreso ligado a esta cotización; no se puede aprobar de nuevo.");
        }
        q.setStatus("APPROVED");
        q.setApprovedAt(LocalDateTime.now());
        quoteRepository.save(q);
        ExpenseEntity exp = new ExpenseEntity();
        exp.setOwnerId(oid);
        exp.setPropertyId(ticket.getPropertyId());
        exp.setType("MAINTENANCE");
        exp.setDescription(q.getDescription());
        exp.setAmount(q.getAmount());
        exp.setStatus("APPROVED");
        exp.setLinkedResourceType("MAINTENANCE_QUOTE");
        exp.setLinkedResourceId(q.getId());
        exp.setEvidenceFileId(q.getEvidenceFileId());
        exp.setApprovedBy(u.getUsername());
        exp.setApprovedAt(LocalDateTime.now());
        exp.setApprovedAmount(q.getAmount());
        exp.setPaidAmount(BigDecimal.ZERO);
        exp.setOutstandingAmount(q.getAmount());
        exp.setPaymentSettlementStatus("UNPAID");
        exp.setOwnerConfirmationStatus("PENDING");
        exp.setProviderConfirmationStatus("PENDING");
        // Proveedor del egreso = quien cotizó (siempre en la cotización tras submitQuote).
        exp.setProviderUserId(q.getProviderId() != null ? q.getProviderId() : ticket.getAssignedProviderId());
        expenseRepository.save(exp);
        ticket.setStatus("APPROVED");
        ticketRepository.save(ticket);
        propertyMovementService.record(oid, ticket.getPropertyId(), "MAINTENANCE", quoteId,
                u.getId(), u.getRole().name(), PropertyMovementEventType.MAINTENANCE_APPROVED,
                "Maintenance approved", q.getId(),
                LocalDateTime.now(), "{\"expenseId\":\"" + exp.getId() + "\"}", q.getEvidenceFileId());
        domainEventDispatcher.dispatch("MAINTENANCE_QUOTE_APPROVED",
                "Cotizacion de mantenimiento aprobada",
                ticket.getTitle(),
                oid, u.getUsername(), ownerAndAdminRecipientIds(oid), null);
    }

    @Transactional
    public void rejectQuote(String quoteId) {
        UserEntity u = current();
        MaintenanceQuoteEntity q = quoteRepository.findById(quoteId).orElseThrow();
        MaintenanceTicketEntity ticket = ticketRepository.findById(q.getTicketId()).orElseThrow();
        String oid = ticket.getOwnerId();
        assertSuperAdminContextMatchesOrganization(u, oid);
        if (!hasPerm(u, oid, "QUOTE_REJECT")) {
            throw new RuntimeException("No permission to reject maintenance quotes.");
        }
        if (!"SUBMITTED".equals(q.getStatus())) {
            throw new RuntimeException(
                    "Solo se puede rechazar una cotización en estado SUBMITTED (estado actual: " + q.getStatus() + ").");
        }
        if (!"QUOTED".equals(ticket.getStatus())) {
            throw new RuntimeException(
                    "El ticket debe estar en QUOTED para rechazar (estado actual: " + ticket.getStatus() + ").");
        }
        q.setStatus("REJECTED");
        quoteRepository.save(q);
        ticket.setStatus("ACCEPTED");
        ticketRepository.save(ticket);
        propertyMovementService.record(oid, ticket.getPropertyId(), "MAINTENANCE", quoteId,
                u.getId(), u.getRole().name(), PropertyMovementEventType.MAINTENANCE_REJECTED,
                "Maintenance quote rejected", "",
                LocalDateTime.now(), null, null);
        domainEventDispatcher.dispatch("MAINTENANCE_QUOTE_REJECTED",
                "Cotizacion de mantenimiento rechazada",
                ticket.getTitle(),
                oid, u.getUsername(), ownerAndAdminRecipientIds(oid), null);
    }

    @Transactional
    public ExpenseEntity recordExpensePayment(String expenseId, BigDecimal amount, String paymentMethod) {
        UserEntity u = current();
        ExpenseEntity exp = expenseRepository.findById(expenseId).orElseThrow();
        String oid = exp.getOwnerId();
        assertSuperAdminContextMatchesOrganization(u, oid);
        if (!hasPerm(u, oid, "EXPENSE_PAY")) {
            throw new RuntimeException("No permission to register maintenance payments.");
        }
        if (!"MAINTENANCE".equals(exp.getType())) {
            throw new RuntimeException("Only maintenance expenses use this flow.");
        }
        if ("PAID".equals(exp.getStatus())) {
            throw new RuntimeException("Expense already fully paid.");
        }
        if ("DISPUTED".equals(exp.getPaymentSettlementStatus())) {
            throw new RuntimeException("Cannot record payments on a disputed expense.");
        }
        if (!"APPROVED".equals(exp.getStatus())) {
            throw new RuntimeException("Expense must be approved before payment.");
        }
        BigDecimal approved = exp.getApprovedAmount() != null ? exp.getApprovedAmount() : exp.getAmount();
        if (approved == null) {
            throw new RuntimeException("Missing approved amount.");
        }
        BigDecimal pay = amount.setScale(2, RoundingMode.HALF_UP);
        if (pay.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Payment amount must be positive.");
        }
        BigDecimal paidSoFar = exp.getPaidAmount() != null ? exp.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal newPaid = paidSoFar.add(pay);
        if (newPaid.compareTo(approved) > 0) {
            throw new RuntimeException("Payment exceeds approved amount.");
        }
        exp.setPaidAmount(newPaid);
        exp.setOutstandingAmount(approved.subtract(newPaid).max(BigDecimal.ZERO));
        exp.setPaymentMethod(paymentMethod != null ? paymentMethod : "OTHER");
        exp.setOwnerConfirmationStatus("CONFIRMED");
        if (newPaid.compareTo(approved) < 0) {
            exp.setPaymentSettlementStatus("PARTIALLY_PAID");
        } else {
            exp.setPaymentSettlementStatus("PAID_IN_FULL");
            exp.setStatus("PAID");
            exp.setPaidAt(LocalDateTime.now());
        }
        expenseRepository.save(exp);
        propertyMovementService.record(oid, exp.getPropertyId(), "EXPENSE", exp.getId(),
                u.getId(), u.getRole().name(), PropertyMovementEventType.MAINTENANCE_EXPENSE_PAYMENT_RECORDED,
                "Pago registrado (mantenimiento)", pay.toPlainString(),
                LocalDateTime.now(),
                "{\"expenseId\":\"" + exp.getId() + "\",\"paidTotal\":\"" + newPaid.toPlainString() + "\"}",
                exp.getEvidenceFileId());
        if (exp.getProviderUserId() != null && !exp.getProviderUserId().isBlank()) {
            createActionTask(exp.getProviderUserId(), oid, "MAINTENANCE_PAYMENT_CONFIRM",
                    "Confirmar pago de mantenimiento",
                    "El dueño registró un pago de " + pay.toPlainString() + " sobre el egreso aprobado.",
                    "EXPENSE", exp.getId());
            domainEventDispatcher.dispatch("MAINTENANCE_PAYMENT_RECORDED",
                    "Pago a proveedor de mantenimiento",
                    pay.toPlainString(),
                    oid, u.getUsername(), List.of(exp.getProviderUserId()), null);
        }
        completeMaintenanceTicketIfExpenseSettled(exp);
        return expenseRepository.findById(exp.getId()).orElse(exp);
    }

    /**
     * Proveedor confirma recepción / saldo. FULL = reconoce lo pagado; DISPUTE = desacuerdo (abre tarea a dueño).
     */
    @Transactional
    public ExpenseEntity providerConfirmExpense(String expenseId, String outcome, String note) {
        UserEntity u = current();
        if (u.getRole() != Role.MAINTENANCE_PROVIDER) {
            throw new RuntimeException("Only the maintenance provider can confirm.");
        }
        ExpenseEntity exp = expenseRepository.findById(expenseId).orElseThrow();
        if (exp.getProviderUserId() == null || !exp.getProviderUserId().equals(u.getId())) {
            throw new RuntimeException("Expense not linked to this provider.");
        }
        if ("DISPUTE".equalsIgnoreCase(outcome)) {
            exp.setProviderConfirmationStatus("DISPUTED");
            exp.setPaymentSettlementStatus("DISPUTED");
            expenseRepository.save(exp);
            propertyMovementService.record(exp.getOwnerId(), exp.getPropertyId(), "EXPENSE", exp.getId(),
                    u.getId(), u.getRole().name(), PropertyMovementEventType.MAINTENANCE_SETTLEMENT_DISPUTE,
                    "Disputa en pago de mantenimiento", note != null ? note : "",
                    LocalDateTime.now(), "{\"expenseId\":\"" + exp.getId() + "\"}", null);
            createActionTask(exp.getOwnerId(), exp.getOwnerId(), "MAINTENANCE_SETTLEMENT_DISPUTE",
                    "Disputa: pago a proveedor de mantenimiento",
                    note != null ? note : "El proveedor reportó un desacuerdo con el pago registrado.",
                    "EXPENSE", exp.getId());
            domainEventDispatcher.dispatch("MAINTENANCE_SETTLEMENT_DISPUTE",
                    "Disputa de settlement (mantenimiento)",
                    note,
                    exp.getOwnerId(), u.getUsername(), ownerAndAdminRecipientIds(exp.getOwnerId()), null);
            return expenseRepository.findById(exp.getId()).orElse(exp);
        }
        exp.setProviderConfirmationStatus("CONFIRMED");
        expenseRepository.save(exp);
        domainEventDispatcher.dispatch("MAINTENANCE_PROVIDER_CONFIRMED",
                "Proveedor confirmó pago de mantenimiento",
                outcome,
                exp.getOwnerId(), u.getUsername(), ownerAndAdminRecipientIds(exp.getOwnerId()), null);
        return expenseRepository.findById(exp.getId()).orElse(exp);
    }

    private void completeMaintenanceTicketIfExpenseSettled(ExpenseEntity exp) {
        if (!"MAINTENANCE_QUOTE".equals(exp.getLinkedResourceType()) || exp.getLinkedResourceId() == null) {
            return;
        }
        if (!"PAID_IN_FULL".equals(exp.getPaymentSettlementStatus())) {
            return;
        }
        quoteRepository.findById(exp.getLinkedResourceId()).ifPresent(q -> {
            ticketRepository.findById(q.getTicketId()).ifPresent(ticket -> {
                ticket.setStatus("COMPLETED");
                ticket.setResolvedAt(LocalDateTime.now());
                ticketRepository.save(ticket);
                propertyMovementService.record(ticket.getOwnerId(), ticket.getPropertyId(), "MAINTENANCE_TICKET", ticket.getId(),
                        null, "SYSTEM", PropertyMovementEventType.MAINTENANCE_PAID,
                        "Ticket de mantenimiento cerrado", "Egreso liquidado",
                        LocalDateTime.now(), "{\"expenseId\":\"" + exp.getId() + "\"}", null);
            });
        });
    }

    // ─── Helpers de plantilla ─────────────────────────────────────────────────────

    private String firstName(UserEntity u) {
        if (u == null || u.getName() == null || u.getName().isBlank()) return "";
        return u.getName().trim().split("\\s+")[0];
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    /** @deprecated Usar {@link #recordExpensePayment(String, BigDecimal, String)}. */
    @Transactional
    public void payExpense(String expenseId) {
        ExpenseEntity exp = expenseRepository.findById(expenseId).orElseThrow();
        BigDecimal total = exp.getApprovedAmount() != null ? exp.getApprovedAmount() : exp.getAmount();
        BigDecimal paid = exp.getPaidAmount() != null ? exp.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal remainder = total.subtract(paid);
        if (remainder.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Expense already fully paid.");
        }
        recordExpensePayment(expenseId, remainder, "OTHER");
    }
}