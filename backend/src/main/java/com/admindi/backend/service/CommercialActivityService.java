package com.admindi.backend.service;

import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.model.CommercialActivityEntity;
import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.model.PropertyMovementEventType;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.repository.ActionTaskRepository;
import com.admindi.backend.repository.CommercialActivityRepository;
import com.admindi.backend.repository.ExpenseRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.repository.VacancyRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CommercialActivityService {

    private final CommercialActivityRepository activityRepository;
    private final VacancyRepository vacancyRepository;
    private final ExpenseRepository expenseRepository;
    private final UserRepository userRepository;
    private final PropertyMovementService propertyMovementService;
    private final VacancyService vacancyService;
    private final PermissionService permissionService;
    private final DomainEventDispatcher domainEventDispatcher;
    private final ActionTaskRepository actionTaskRepository;

    @Autowired
    public CommercialActivityService(CommercialActivityRepository activityRepository,
                                   VacancyRepository vacancyRepository,
                                   ExpenseRepository expenseRepository,
                                   UserRepository userRepository,
                                   PropertyMovementService propertyMovementService,
                                   VacancyService vacancyService,
                                   PermissionService permissionService,
                                   DomainEventDispatcher domainEventDispatcher,
                                   ActionTaskRepository actionTaskRepository) {
        this.activityRepository = activityRepository;
        this.vacancyRepository = vacancyRepository;
        this.expenseRepository = expenseRepository;
        this.userRepository = userRepository;
        this.propertyMovementService = propertyMovementService;
        this.vacancyService = vacancyService;
        this.permissionService = permissionService;
        this.domainEventDispatcher = domainEventDispatcher;
        this.actionTaskRepository = actionTaskRepository;
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

    private UserEntity currentUser() {
        return userRepository.findByLoginIdentifier(SecurityContextHolder.getContext().getAuthentication().getName()).orElseThrow();
    }

    private boolean hasPerm(UserEntity u, String ownerId, String perm) {
        if (u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.OWNER) {
            return true;
        }
        return permissionService.resolvePermissions(u.getId(), ownerId).contains(perm);
    }

    private String movementEventForActivityType(String activityType) {
        if (activityType == null) {
            return PropertyMovementEventType.COMMERCIAL_ACTIVITY;
        }
        return switch (activityType.toUpperCase()) {
            case "VISIT" -> PropertyMovementEventType.VISIT_RECORDED;
            case "PHOTOS" -> PropertyMovementEventType.PHOTOS_UPLOADED;
            case "OBSERVATION" -> PropertyMovementEventType.COMMERCIAL_OBSERVATION;
            case "LISTING" -> PropertyMovementEventType.LISTING_ACTIVE;
            case "FOLLOW_UP", "FOLLOWUP" -> PropertyMovementEventType.COMMERCIAL_FOLLOW_UP;
            default -> PropertyMovementEventType.COMMERCIAL_ACTIVITY;
        };
    }

    private void bumpVacancyStatusAfterActivity(VacancyEntity vac, String activityType) {
        if (vac.getClosedAt() != null) {
            return;
        }
        String t = activityType != null ? activityType.toUpperCase() : "";
        switch (t) {
            case "VISIT" -> vac.setStatus("VISIT_RECORDED");
            case "PHOTOS" -> vac.setStatus("LISTING_ACTIVE");
            case "LISTING" -> vac.setStatus("LISTING_ACTIVE");
            default -> { }
        }
        vacancyRepository.save(vac);
    }

    @Transactional
    public CommercialActivityEntity logActivity(String vacancyId, String activityType, String notes,
                                                BigDecimal commissionAmount, String evidenceFileId) {
        UserEntity u = currentUser();
        VacancyEntity vac = vacancyRepository.findById(vacancyId).orElseThrow();
        if (u.getRole() != Role.SUPER_ADMIN) {
            String org = TenantContext.resolveOwnerId(userRepository);
            if (!vac.getOwnerId().equals(org)) {
                throw new RuntimeException("IDOR");
            }
            if (u.getRole() == Role.REAL_ESTATE_AGENT) {
                if (vac.getAssignedAgentId() == null || !vac.getAssignedAgentId().equals(u.getId())) {
                    throw new RuntimeException("Esta vacancia no está asignada a usted.");
                }
            }
        }
        if (vac.getClosedAt() != null) {
            throw new RuntimeException("La vacancia está cerrada; no se registran actividades.");
        }

        if (commissionAmount != null && commissionAmount.compareTo(BigDecimal.ZERO) > 0) {
            vacancyService.assertPrivateRoutingAllowsCommercialStep(vac);
        }

        CommercialActivityEntity a = new CommercialActivityEntity();
        a.setVacancyId(vacancyId);
        a.setAgentUserId(u.getId());
        a.setActivityType(activityType != null ? activityType : "VISIT");
        a.setNotes(notes);
        a.setCommissionAmount(commissionAmount);
        if (commissionAmount != null && commissionAmount.compareTo(BigDecimal.ZERO) > 0) {
            a.setCommissionStatus("PENDING");
            vac.setStatus("COMMISSION_QUOTED");
            vacancyRepository.save(vac);
        }
        a.setEvidenceFileId(evidenceFileId);
        CommercialActivityEntity saved = activityRepository.save(a);

        String moveType = movementEventForActivityType(saved.getActivityType());
        if (commissionAmount != null && commissionAmount.compareTo(BigDecimal.ZERO) > 0) {
            moveType = PropertyMovementEventType.COMMISSION_QUOTED;
        } else {
            bumpVacancyStatusAfterActivity(vac, saved.getActivityType());
        }
        propertyMovementService.record(vac.getOwnerId(), vac.getPropertyId(), "COMMERCIAL", saved.getId(),
                u.getId(), u.getRole().name(), moveType,
                "Actividad comercial", notes != null ? notes : saved.getActivityType(),
                LocalDateTime.now(),
                "{\"activityId\":\"" + saved.getId() + "\",\"vacancyId\":\"" + vacancyId + "\"}",
                evidenceFileId);

        domainEventDispatcher.dispatch("COMMERCIAL_ACTIVITY_LOGGED",
                "Actividad comercial registrada",
                saved.getActivityType(),
                vac.getOwnerId(), u.getUsername(), vac.getAssignedAgentId() != null ? List.of(vac.getAssignedAgentId()) : null,
                null);

        return saved;
    }

    @Transactional
    public CommercialActivityEntity decideCommission(String activityId, boolean approve) {
        UserEntity actor = currentUser();
        CommercialActivityEntity a = activityRepository.findById(activityId).orElseThrow();
        VacancyEntity vac = vacancyRepository.findById(a.getVacancyId()).orElseThrow();
        String ownerId = vac.getOwnerId();
        if (actor.getRole() != Role.SUPER_ADMIN) {
            String ctx = TenantContext.resolveOwnerId(userRepository);
            if (!ownerId.equals(ctx)) {
                throw new RuntimeException("IDOR");
            }
        }
        if (actor.getRole() != Role.OWNER && actor.getRole() != Role.SUPER_ADMIN) {
            if (!hasPerm(actor, ownerId, "QUOTE_APPROVE") && !hasPerm(actor, ownerId, "QUOTE_REJECT")) {
                throw new RuntimeException("Sin permiso para aprobar o rechazar cotización de comisión.");
            }
        }
        if (a.getCommissionAmount() == null || a.getCommissionAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("No hay comisión en esta actividad.");
        }

        if (vac.getClosedAt() != null) {
            throw new RuntimeException("La vacancia está cerrada; no se puede decidir la comisión.");
        }

        String cs = a.getCommissionStatus();
        if (cs == null || cs.isBlank()) {
            cs = "PENDING";
        }
        if ("APPROVED".equals(cs)) {
            if (approve) {
                return a;
            }
            throw new RuntimeException("La comisión ya fue aprobada.");
        }
        if ("REJECTED".equals(cs)) {
            if (!approve) {
                return a;
            }
            throw new RuntimeException("La comisión ya fue rechazada.");
        }
        if (!"PENDING".equals(cs)) {
            throw new RuntimeException("Estado de comisión no válido para decisión: " + cs);
        }

        if (approve) {
            if (!hasPerm(actor, ownerId, "QUOTE_APPROVE") && actor.getRole() != Role.OWNER && actor.getRole() != Role.SUPER_ADMIN) {
                throw new RuntimeException("Se requiere permiso QUOTE_APPROVE para aprobar.");
            }
            a.setCommissionStatus("APPROVED");
            vac.setStatus("COMMISSION_APPROVED");
            vacancyRepository.save(vac);

            Optional<ExpenseEntity> existing = expenseRepository
                    .findFirstByLinkedResourceTypeAndLinkedResourceIdOrderByCreatedAtDesc("COMMERCIAL_ACTIVITY", a.getId());
            if (existing.isEmpty()) {
                ExpenseEntity exp = new ExpenseEntity();
                exp.setOwnerId(ownerId);
                exp.setPropertyId(vac.getPropertyId());
                exp.setType("COMMERCIAL");
                exp.setDescription("Comisión comercial aprobada (actividad " + a.getId() + ")");
                exp.setAmount(a.getCommissionAmount());
                exp.setApprovedAmount(a.getCommissionAmount());
                exp.setPaidAmount(BigDecimal.ZERO);
                exp.setOutstandingAmount(a.getCommissionAmount());
                exp.setPaymentSettlementStatus("UNPAID");
                exp.setOwnerConfirmationStatus("PENDING");
                exp.setProviderConfirmationStatus("PENDING");
                exp.setStatus("APPROVED");
                exp.setLinkedResourceType("COMMERCIAL_ACTIVITY");
                exp.setLinkedResourceId(a.getId());
                exp.setApprovedBy(actor.getUsername());
                exp.setApprovedAt(LocalDateTime.now());
                if (vac.getAssignedAgentId() != null) {
                    exp.setProviderUserId(vac.getAssignedAgentId());
                }
                expenseRepository.save(exp);
                propertyMovementService.record(ownerId, vac.getPropertyId(), "EXPENSE", exp.getId(),
                        actor.getId(), actor.getRole().name(), PropertyMovementEventType.COMMISSION_APPROVED,
                        "Comisión aprobada", exp.getDescription(),
                        LocalDateTime.now(), "{\"expenseId\":\"" + exp.getId() + "\",\"activityId\":\"" + a.getId() + "\"}", null);
                domainEventDispatcher.dispatch("COMMISSION_APPROVED",
                        "Comisión comercial aprobada",
                        exp.getId(),
                        ownerId, actor.getUsername(), vac.getAssignedAgentId() != null ? List.of(vac.getAssignedAgentId()) : null,
                        null);
            }
        } else {
            if (actor.getRole() != Role.OWNER && actor.getRole() != Role.SUPER_ADMIN
                    && !hasPerm(actor, ownerId, "QUOTE_REJECT")) {
                throw new RuntimeException("Se requiere permiso QUOTE_REJECT para rechazar.");
            }
            a.setCommissionStatus("REJECTED");
            vac.setStatus("COMMISSION_REJECTED");
            vacancyRepository.save(vac);
            propertyMovementService.record(ownerId, vac.getPropertyId(), "COMMERCIAL", a.getId(),
                    actor.getId(), actor.getRole().name(), PropertyMovementEventType.COMMISSION_REJECTED,
                    "Comisión rechazada", "",
                    LocalDateTime.now(), null, null);
        }
        return activityRepository.save(a);
    }

    /**
     * Pago parcial/total al agente por egreso COMMERCIAL aprobado (misma idea que mantenimiento).
     */
    @Transactional
    public ExpenseEntity recordCommercialCommissionPayment(String expenseId, BigDecimal amount, String paymentMethod) {
        UserEntity u = currentUser();
        ExpenseEntity exp = expenseRepository.findById(expenseId).orElseThrow();
        String oid = exp.getOwnerId();
        if (!"COMMERCIAL".equals(exp.getType())) {
            throw new RuntimeException("Solo egresos COMMERCIAL usan este flujo.");
        }
        if (!hasPerm(u, oid, "EXPENSE_PAY")) {
            throw new RuntimeException("Sin permiso EXPENSE_PAY para registrar pagos.");
        }
        if ("PAID".equals(exp.getStatus())) {
            throw new RuntimeException("Egreso ya pagado.");
        }
        if ("DISPUTED".equals(exp.getPaymentSettlementStatus())) {
            throw new RuntimeException("No registrar pagos en egreso en disputa.");
        }
        if (!"APPROVED".equals(exp.getStatus())) {
            throw new RuntimeException("El egreso debe estar aprobado.");
        }
        BigDecimal approved = exp.getApprovedAmount() != null ? exp.getApprovedAmount() : exp.getAmount();
        BigDecimal pay = amount.setScale(2, RoundingMode.HALF_UP);
        if (pay.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("El monto debe ser positivo.");
        }
        BigDecimal paidSoFar = exp.getPaidAmount() != null ? exp.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal newPaid = paidSoFar.add(pay);
        if (newPaid.compareTo(approved) > 0) {
            throw new RuntimeException("El pago supera el monto aprobado.");
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
                u.getId(), u.getRole().name(), PropertyMovementEventType.COMMISSION_PAYMENT_RECORDED,
                "Pago comisión (comercial)", pay.toPlainString(),
                LocalDateTime.now(),
                "{\"expenseId\":\"" + exp.getId() + "\",\"paidTotal\":\"" + newPaid.toPlainString() + "\"}",
                exp.getEvidenceFileId());
        if (exp.getProviderUserId() != null && !exp.getProviderUserId().isBlank()) {
            createActionTask(exp.getProviderUserId(), oid,
                    "COMMERCIAL_PAYMENT_CONFIRM",
                    "Confirmar pago de comisión",
                    "El dueño registró un pago de " + pay + " sobre la comisión aprobada.",
                    "EXPENSE", exp.getId());
            domainEventDispatcher.dispatch("COMMERCIAL_PAYMENT_RECORDED",
                    "Pago a agente inmobiliario",
                    pay.toPlainString(),
                    oid, u.getUsername(), List.of(exp.getProviderUserId()), null);
        }
        return expenseRepository.findById(exp.getId()).orElse(exp);
    }

    @Transactional
    public ExpenseEntity agentConfirmCommercialPayment(String expenseId, String outcome, String note) {
        UserEntity u = currentUser();
        if (u.getRole() != Role.REAL_ESTATE_AGENT) {
            throw new RuntimeException("Solo el agente inmobiliario puede confirmar.");
        }
        ExpenseEntity exp = expenseRepository.findById(expenseId).orElseThrow();
        if (!"COMMERCIAL".equals(exp.getType())) {
            throw new RuntimeException("Egreso no es comercial.");
        }
        if (exp.getProviderUserId() == null || !exp.getProviderUserId().equals(u.getId())) {
            throw new RuntimeException("Este egreso no está vinculado a usted.");
        }
        if ("DISPUTE".equalsIgnoreCase(outcome)) {
            exp.setProviderConfirmationStatus("DISPUTED");
            exp.setPaymentSettlementStatus("DISPUTED");
            expenseRepository.save(exp);
            propertyMovementService.record(exp.getOwnerId(), exp.getPropertyId(), "EXPENSE", exp.getId(),
                    u.getId(), u.getRole().name(), PropertyMovementEventType.COMMERCIAL_SETTLEMENT_DISPUTE,
                    "Disputa en pago de comisión", note != null ? note : "",
                    LocalDateTime.now(), "{\"expenseId\":\"" + exp.getId() + "\"}", null);
            createActionTask(exp.getOwnerId(), exp.getOwnerId(),
                    "COMMERCIAL_SETTLEMENT_DISPUTE",
                    "Disputa: pago de comisión",
                    note != null ? note : "El agente reportó un desacuerdo.",
                    "EXPENSE", exp.getId());
            return expenseRepository.findById(exp.getId()).orElse(exp);
        }
        exp.setProviderConfirmationStatus("CONFIRMED");
        expenseRepository.save(exp);
        return expenseRepository.findById(exp.getId()).orElse(exp);
    }
}
