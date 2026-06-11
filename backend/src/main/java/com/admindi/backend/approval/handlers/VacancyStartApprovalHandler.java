package com.admindi.backend.approval.handlers;

import com.admindi.backend.approval.ApprovalPayloadReader;
import com.admindi.backend.approval.ApprovalTaskHandler;
import com.admindi.backend.constants.ActionTaskEventType;
import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.repository.ActionTaskRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.AuditService;
import com.admindi.backend.service.DomainEventDispatcher;
import com.admindi.backend.service.ReauthService;
import com.admindi.backend.service.VacancyAgentOrchestrationService;
import com.admindi.backend.service.VacancyService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Approval handler for {@link ActionTaskEventType#VACANCY_START_REQUESTED}.
 *
 * <p>Flujo "Poner en renta" iniciado por staff:
 * <ol>
 *   <li>PROPERTY_ADMIN con {@code VACANCY_START_CHAIN} pide arrancar la cadena de
 *       agentes inmobiliarios sobre un inmueble. Se persiste un {@link ActionTaskEntity}
 *       con {@code resourceId = propertyId}.</li>
 *   <li>El dueño aprueba desde su bandeja (reauth password + MFA). Este handler
 *       busca o abre una vacancia y delega en
 *       {@link VacancyAgentOrchestrationService#startChainIfApplicable(VacancyEntity)}.</li>
 *   <li>Si la guardia {@code MIN_RENTAL_HISTORY_DAYS} rechaza (primera colocación del
 *       inmueble), se devuelve 409 {@code NO_RENTAL_HISTORY} al dueño para que lo
 *       derive manualmente.</li>
 * </ol>
 *
 * <p>Cuando el dueño lo dispara directamente, no pasa por aquí: usa
 * {@code POST /api/owner/workflow/vacancies/start-agent-chain}.
 */
@Component
public class VacancyStartApprovalHandler implements ApprovalTaskHandler {

    private final VacancyService vacancyService;
    private final VacancyAgentOrchestrationService vacancyOrchestrator;
    private final ReauthService reauthService;
    private final ActionTaskRepository taskRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;
    private final DomainEventDispatcher dispatcher;
    private final ApprovalPayloadReader payloadReader;

    public VacancyStartApprovalHandler(VacancyService vacancyService,
                                       VacancyAgentOrchestrationService vacancyOrchestrator,
                                       ReauthService reauthService,
                                       ActionTaskRepository taskRepo,
                                       UserRepository userRepo,
                                       AuditService auditService,
                                       DomainEventDispatcher dispatcher,
                                       ApprovalPayloadReader payloadReader) {
        this.vacancyService = vacancyService;
        this.vacancyOrchestrator = vacancyOrchestrator;
        this.reauthService = reauthService;
        this.taskRepo = taskRepo;
        this.userRepo = userRepo;
        this.auditService = auditService;
        this.dispatcher = dispatcher;
        this.payloadReader = payloadReader;
    }

    @Override
    public String getEventType() {
        return ActionTaskEventType.VACANCY_START_REQUESTED;
    }

    @Override
    public void onApprove(ActionTaskEntity task, String ownerPassword, String ownerMfaCode) {
        UserEntity approver = resolveAuthenticatedUser();
        assertTaskOwnedByApprover(task, approver);

        String propertyId = task.getResourceId();
        if (propertyId == null || propertyId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "La tarea no tiene resourceId de inmueble.");
        }

        // Owner reauth (password + MFA). Audit trail keeps context of the operation.
        reauthService.verifyReauthForUser(approver, ownerPassword, ownerMfaCode, "VACANCY_START_APPROVE");

        Optional<VacancyEntity> existing = vacancyService.findOpenVacancyForProperty(propertyId);
        VacancyEntity vacancy = existing.orElseGet(() -> vacancyService.createVacancyManual(propertyId));

        try {
            vacancyOrchestrator.startChainIfApplicable(vacancy);
        } catch (IllegalStateException ex) {
            // NO_RENTAL_HISTORY u otras guardas: no resolver la tarea, propagar como 409
            // para que el frontend renderice el mensaje al dueño y pueda derivar manual.
            String msg = ex.getMessage() != null ? ex.getMessage() : "No se pudo iniciar la cadena.";
            throw new ResponseStatusException(HttpStatus.CONFLICT, msg, ex);
        }

        task.setStatus("RESOLVED");
        task.setResolvedAt(LocalDateTime.now());
        taskRepo.save(task);

        auditService.logEvent(
                approver.getLoginUsername(),
                approver.getRole() != null ? approver.getRole().name() : null,
                "VACANCY_START_APPROVED",
                "ActionTask",
                task.getId(),
                task.getOwnerId(),
                null,
                String.format("{\"propertyId\":\"%s\",\"vacancyId\":\"%s\"}", propertyId, vacancy.getId()),
                null,
                null);

        dispatcher.dispatch(
                "VACANCY_START_APPROVED",
                "Solicitud de puesta en renta aprobada",
                "El dueño autorizó arrancar la cadena de agentes inmobiliarios.",
                task.getOwnerId(),
                approver.getLoginUsername(),
                resolveRecipients(task),
                null);
    }

    @Override
    public void onReject(ActionTaskEntity task, String rejectionReason) {
        UserEntity approver = resolveAuthenticatedUser();
        assertTaskOwnedByApprover(task, approver);

        task.setStatus("DISMISSED");
        task.setResolvedAt(LocalDateTime.now());
        taskRepo.save(task);

        String reason = rejectionReason != null ? rejectionReason.trim() : null;
        auditService.logEvent(
                approver.getLoginUsername(),
                approver.getRole() != null ? approver.getRole().name() : null,
                "VACANCY_START_REJECTED",
                "ActionTask",
                task.getId(),
                task.getOwnerId(),
                null,
                reason != null && !reason.isBlank()
                        ? String.format("{\"reason\":%s}", jsonString(reason))
                        : null,
                null,
                null);

        dispatcher.dispatch(
                "VACANCY_START_REJECTED",
                "Solicitud de puesta en renta rechazada",
                reason != null && !reason.isBlank() ? "Motivo: " + reason : null,
                task.getOwnerId(),
                approver.getLoginUsername(),
                resolveRecipients(task),
                null);
    }

    private List<String> resolveRecipients(ActionTaskEntity task) {
        return payloadReader.initiatorUserId(task)
                .map(List::of)
                .orElseGet(() -> List.of(task.getOwnerId()));
    }

    private UserEntity resolveAuthenticatedUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepo.findByLoginIdentifier(email)
                .orElseThrow(() -> new RuntimeException("Usuario autenticado no existe."));
    }

    private void assertTaskOwnedByApprover(ActionTaskEntity task, UserEntity approver) {
        if (!approver.getId().equals(task.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el titular de la organización puede aprobar o rechazar esta solicitud.");
        }
    }

    private static String jsonString(String raw) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
