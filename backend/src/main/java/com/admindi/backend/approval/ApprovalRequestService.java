package com.admindi.backend.approval;

import com.admindi.backend.constants.ActionTaskEventType;
import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.ActionTaskRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.AuditService;
import com.admindi.backend.service.DomainEventDispatcher;
import com.admindi.backend.service.ReauthService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service that encapsulates the "staff initiates a sensitive action request" half of the
 * approval workflow. The three side effects — staff reauth, task creation, owner
 * notification — were duplicated across every sensitive-action endpoint prior to Fase 2.
 * Centralizing them here guarantees:
 *
 * <ul>
 *   <li>Every approval request enforces the <b>double reauth policy</b>: staff must supply
 *       password and MFA to even request the action. The owner later supplies their own
 *       reauth at {@code /api/tasks/{id}/approve}.</li>
 *   <li>Audit and notification semantics are consistent across property delete, tenant
 *       archive, lease terminate, etc.</li>
 *   <li>Task payload always embeds initiator identity so the owner's inbox can show
 *       "Solicitado por {staffEmail}".</li>
 * </ul>
 *
 * <p>Callers (domain services like PropertyService, TenantExpedienteService, …) remain
 * responsible for their own domain-level validations (IDOR checks, state preconditions)
 * before handing off here.
 */
@Service
public class ApprovalRequestService {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalRequestService.class);

    private final UserRepository userRepository;
    private final ActionTaskRepository actionTaskRepository;
    private final ReauthService reauthService;
    private final AuditService auditService;
    private final DomainEventDispatcher dispatcher;
    private final ApprovalTaskRegistry approvalRegistry;
    private final ObjectMapper objectMapper;

    public ApprovalRequestService(UserRepository userRepository,
                                  ActionTaskRepository actionTaskRepository,
                                  ReauthService reauthService,
                                  AuditService auditService,
                                  DomainEventDispatcher dispatcher,
                                  ApprovalTaskRegistry approvalRegistry,
                                  ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.actionTaskRepository = actionTaskRepository;
        this.reauthService = reauthService;
        this.auditService = auditService;
        this.dispatcher = dispatcher;
        this.approvalRegistry = approvalRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Immutable input describing a staff-initiated approval request. Using a record keeps
     * call sites expressive and avoids a long positional parameter list.
     */
    public record ApprovalRequestInput(
            String eventType,
            String ownerId,
            String resourceType,
            String resourceId,
            String title,
            String description,
            String reason,
            Map<String, Object> contextPayload,
            String staffPassword,
            String staffMfaCode) {}

    /**
     * Verifies the staff member's password and MFA, persists a new {@link ActionTaskEntity}
     * targeting the owner, writes an audit event, and dispatches the pending-task
     * notification. Returns the created task (OPEN) so callers can embed its id in the
     * HTTP response if desired.
     *
     * @throws IllegalArgumentException  if the eventType is not registered in {@link ApprovalTaskRegistry}.
     * @throws RuntimeException          from {@link ReauthService#verifyReauthForUser} on bad password / MFA.
     */
    public ActionTaskEntity submit(ApprovalRequestInput in) {
        if (!approvalRegistry.supports(in.eventType())) {
            throw new IllegalArgumentException(
                    "eventType '" + in.eventType() + "' no tiene un ApprovalTaskHandler registrado. "
                            + "Tipos soportados: " + approvalRegistry.supportedEventTypes());
        }

        String actorEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity staff = userRepository.findByLoginIdentifier(actorEmail)
                .orElseThrow(() -> new RuntimeException("Usuario autenticado no existe."));

        String operation = in.eventType() + "_REQUEST";
        reauthService.verifyReauthForUser(staff, in.staffPassword(), in.staffMfaCode(), operation);

        ActionTaskEntity task = new ActionTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setUserId(in.ownerId());
        task.setOwnerId(in.ownerId());
        task.setEventType(in.eventType());
        task.setTitle(in.title());
        task.setDescription(in.description());
        task.setResourceType(in.resourceType());
        task.setResourceId(in.resourceId());
        task.setStatus("OPEN");
        task.setCreatedAt(LocalDateTime.now());
        task.setPayload(buildPayload(staff, in));
        actionTaskRepository.save(task);

        auditService.logEvent(
                staff.getLoginUsername(),
                staff.getRole() != null ? staff.getRole().name() : null,
                "APPROVAL_REQUEST_SUBMITTED",
                "ActionTask",
                task.getId(),
                in.ownerId(),
                null,
                task.getPayload(),
                null,
                null);

        dispatcher.dispatch(
                in.eventType(),
                in.title(),
                "Solicitado por: " + staff.getLoginUsername(),
                in.ownerId(),
                actorEmail,
                List.of(in.ownerId()));

        return task;
    }

    /**
     * Serialize the task payload as JSON. The payload always embeds initiator identity
     * (the owner's inbox reads these to show "Solicitado por …") plus any domain-specific
     * context the caller provides.
     */
    private String buildPayload(UserEntity staff, ApprovalRequestInput in) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (in.contextPayload() != null) payload.putAll(in.contextPayload());
        payload.put("initiatedByUserId", staff.getId());
        // V54: identificador autoritativo del actor = username (V48). El antiguo
        // `initiatedByEmail` pasaba el ya-removido users.email; mantenemos la key
        // por compat del cliente pero ahora transporta el username real.
        payload.put("initiatedByEmail", staff.getLoginUsername());
        payload.put("initiatedByRole", staff.getRole() != null ? staff.getRole().name() : null);
        payload.put("initiatedAt", LocalDateTime.now().toString());
        if (in.reason() != null && !in.reason().isBlank()) payload.put("reason", in.reason().trim());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize approval-request payload for event {}: {}",
                    in.eventType(), e.getMessage());
            return "{}";
        }
    }

    /** Quick-access helpers re-exposing the constants so callers only import one type. */
    public static final class EventTypes {
        public static final String PROPERTY_DELETE = ActionTaskEventType.PROPERTY_DELETE_REQUESTED;
        public static final String TENANT_ARCHIVE = ActionTaskEventType.TENANT_ARCHIVE_REQUESTED;
        public static final String LEASE_TERMINATE = ActionTaskEventType.LEASE_TERMINATE_REQUESTED;
        public static final String VACANCY_START = ActionTaskEventType.VACANCY_START_REQUESTED;

        private EventTypes() {}
    }
}
