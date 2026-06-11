package com.admindi.backend.service;

import com.admindi.backend.model.AgentCommissionInvoiceEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.ProspectSubmissionEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.repository.AgentCommissionInvoiceRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.ProspectSubmissionRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.repository.VacancyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Servicio encargado del <strong>cierre de contrato reportado por el agente</strong>.
 *
 * <p>Ruta A (decisión del negocio):
 * <ol>
 *   <li>El agente reporta cierre: sube PDF firmado, datos del prospecto aceptado,
 *       renta mensual, meses del contrato, depósito y % de comisión (solo si es
 *       agente privado; platform agent usa default config).</li>
 *   <li>Se guarda evidencia + datos contractuales en la {@code VacancyEntity} y se
 *       crea el {@code AgentCommissionInvoiceEntity} en estado {@code PENDING} (la
 *       comisión existe desde ya; sólo falta que el lease quede formalmente creado
 *       por el dueño).</li>
 *   <li>Se notifica al dueño con call-to-action: "Confirmar cierre y crear contrato"
 *       (el frontend abre el formulario de alta de tenant del flujo legacy con los
 *       datos precargados desde la vacancia + el prospecto).</li>
 *   <li>Cuando el dueño completa el alta del tenant vía {@code TenantService.createTenant},
 *       se detecta que existe una vacancia AWAITING_CONTRACT para ese property +
 *       tenant email y se invoca {@link #onLeaseConfirmedForVacancy} desde ese hook
 *       para amarrar la comisión al lease creado y disparar la notificación al
 *       agente.</li>
 * </ol>
 *
 * <p>Esta ruta respeta la autoridad del dueño sobre su cartera sin duplicar la
 * lógica de creación de tenant/lease/contabilidad que ya vive en TenantService.
 */
@Service
public class ContractClosureService {

    private static final Logger logger = LoggerFactory.getLogger(ContractClosureService.class);

    private final VacancyRepository vacancyRepository;
    private final ProspectSubmissionRepository prospectRepository;
    private final AgentCommissionInvoiceRepository commissionRepository;
    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;
    private final VacancyAgentOrchestrationService vacancyOrchestrator;
    private final DomainEventDispatcher domainEventDispatcher;

    @Value("${admindi.commission.platform-agent-pct:0.03}")
    private BigDecimal platformAgentPct;

    @Value("${app.url:https://app.admindi.com}")
    private String appUrl;

    public ContractClosureService(VacancyRepository vacancyRepository,
                                  ProspectSubmissionRepository prospectRepository,
                                  AgentCommissionInvoiceRepository commissionRepository,
                                  UserRepository userRepository,
                                  PropertyRepository propertyRepository,
                                  VacancyAgentOrchestrationService vacancyOrchestrator,
                                  DomainEventDispatcher domainEventDispatcher) {
        this.vacancyRepository = vacancyRepository;
        this.prospectRepository = prospectRepository;
        this.commissionRepository = commissionRepository;
        this.userRepository = userRepository;
        this.propertyRepository = propertyRepository;
        this.vacancyOrchestrator = vacancyOrchestrator;
        this.domainEventDispatcher = domainEventDispatcher;
    }

    /**
     * Cierra reporte por parte del agente: valida estado AWAITING_CONTRACT, crea
     * commission invoice PENDING, marca la vacancia como CLOSED/CONTRACT_SIGNED.
     *
     * @param overridePct null para agente platform (usa default); obligatorio para agente privado.
     */
    @Transactional
    public ContractClosureResult reportClosure(String vacancyId, String evidenceFileId,
                                               Integer months, BigDecimal monthlyRent, BigDecimal deposit,
                                               String agentSource, BigDecimal overridePct) {
        UserEntity agent = currentAgent();
        VacancyEntity vacancy = vacancyRepository.findById(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancia no existe: " + vacancyId));

        if (vacancy.getAssignedAgentId() == null || !vacancy.getAssignedAgentId().equals(agent.getId())) {
            throw new SecurityException("Esta vacancia no está asignada a ti.");
        }
        if (!VacancyEntity.CHAIN_AWAITING_CONTRACT.equals(vacancy.getChainState())) {
            throw new IllegalStateException("La vacancia no está en estado AWAITING_CONTRACT (actual: "
                    + vacancy.getChainState() + "). El dueño debe aceptar un prospecto antes de cerrar contrato.");
        }
        if (evidenceFileId == null || evidenceFileId.isBlank()) {
            throw new IllegalArgumentException("Evidencia del contrato firmado obligatoria.");
        }
        if (months == null || months < 1 || months > 60) {
            throw new IllegalArgumentException("Meses del contrato deben ser entre 1 y 60.");
        }
        if (monthlyRent == null || monthlyRent.signum() <= 0) {
            throw new IllegalArgumentException("Renta mensual debe ser > 0.");
        }
        if (deposit == null) deposit = BigDecimal.ZERO;

        if (!AgentCommissionInvoiceEntity.SOURCE_PLATFORM.equals(agentSource)
                && !AgentCommissionInvoiceEntity.SOURCE_PRIVATE.equals(agentSource)) {
            throw new IllegalArgumentException("agentSource debe ser PLATFORM o PRIVATE.");
        }

        BigDecimal pct;
        if (AgentCommissionInvoiceEntity.SOURCE_PLATFORM.equals(agentSource)) {
            pct = platformAgentPct;
        } else {
            if (overridePct == null || overridePct.signum() <= 0 || overridePct.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Para agente privado, el owner debe indicar un % válido (0, 1).");
            }
            pct = overridePct;
        }

        ProspectSubmissionEntity prospect = prospectRepository
                .findFirstByVacancyIdAndOwnerDecisionOrderBySubmittedAtDesc(vacancyId,
                        ProspectSubmissionEntity.DECISION_ACCEPTED)
                .orElseThrow(() -> new IllegalStateException(
                        "No hay prospecto ACCEPTED para esta vacancia; no puedes cerrar contrato."));

        BigDecimal commissionAmount = monthlyRent
                .multiply(BigDecimal.valueOf(months))
                .multiply(pct)
                .setScale(2, RoundingMode.HALF_UP);

        AgentCommissionInvoiceEntity invoice = new AgentCommissionInvoiceEntity();
        invoice.setOwnerId(vacancy.getOwnerId());
        invoice.setAgentUserId(agent.getId());
        invoice.setAgentSource(agentSource);
        invoice.setPropertyId(vacancy.getPropertyId());
        invoice.setVacancyId(vacancy.getId());
        invoice.setMonthlyRent(monthlyRent);
        invoice.setContractMonths(months);
        invoice.setCommissionPct(pct);
        invoice.setCommissionAmount(commissionAmount);
        invoice.setStatus(AgentCommissionInvoiceEntity.STATUS_PENDING);
        AgentCommissionInvoiceEntity savedInvoice = commissionRepository.save(invoice);

        vacancyOrchestrator.markContractSigned(vacancy, evidenceFileId, months, monthlyRent, deposit);

        UserEntity owner = userRepository.findById(vacancy.getOwnerId()).orElse(null);
        String propLabel = propLabel(vacancy.getPropertyId());
        // Plantilla admindi_contract_signed_commission_due_v1:
        //   {{1}}dueño {{2}}inmueble {{3}}agente {{4}}monto comisión {{5}}URL.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(owner));
        vars.put("2", propLabel);
        vars.put("3", nullSafe(agent.getName()));
        vars.put("4", formatAmount(commissionAmount));
        vars.put("5", appUrl + "/dashboard?panel=owner&vacancy=" + vacancy.getId());
        domainEventDispatcher.dispatch("CONTRACT_SIGNED_COMMISSION_DUE",
                "Contrato firmado — comisión pendiente de pago",
                "Tu agente " + agent.getName() + " cerró contrato con " + prospect.getProspectName()
                        + ". Confirma la creación del tenant y paga la comisión de $"
                        + formatAmount(commissionAmount) + " (" + pct.multiply(BigDecimal.valueOf(100)) + "%). "
                        + "Panel: " + vars.get("5"),
                vacancy.getOwnerId(), agent.getUsername(), List.of(vacancy.getOwnerId()), vars, null);

        return new ContractClosureResult(savedInvoice, vacancy, prospect);
    }

    /**
     * Hook llamado por {@code TenantService.createTenant} cuando el owner crea el
     * tenant formalmente para un property que tenía una vacancia con contrato
     * firmado. Amarra la comisión al lease creado.
     *
     * <p>Si no encuentra match, simplemente no hace nada (flujo antiguo sin agente
     * no se altera).
     */
    @Transactional
    public void onLeaseConfirmedForVacancy(String ownerId, String propertyId, String leaseId, String tenantEmail) {
        Optional<VacancyEntity> vacancyOpt = vacancyRepository
                .findFirstByPropertyIdAndClosedAtIsNullOrderByOpenedAtDesc(propertyId);
        if (vacancyOpt.isEmpty()) {
            // También buscar una cerrada reciente con contrato firmado (porque markContractSigned pudo cerrarla).
            List<VacancyEntity> recent = vacancyRepository.findByPropertyId(propertyId);
            vacancyOpt = recent.stream()
                    .filter(v -> VacancyEntity.CHAIN_CONTRACT_SIGNED.equals(v.getChainState()))
                    .filter(v -> ownerId.equals(v.getOwnerId()))
                    .findFirst();
        }
        if (vacancyOpt.isEmpty()) return;
        VacancyEntity vacancy = vacancyOpt.get();
        if (!ownerId.equals(vacancy.getOwnerId())) return;

        // Solo amarramos si hay comisión pendiente sin lease asignado.
        List<AgentCommissionInvoiceEntity> pending = commissionRepository
                .findByOwnerIdAndStatus(ownerId, AgentCommissionInvoiceEntity.STATUS_PENDING);
        AgentCommissionInvoiceEntity invoice = pending.stream()
                .filter(i -> vacancy.getId().equals(i.getVacancyId()))
                .filter(i -> i.getLeaseId() == null)
                .findFirst()
                .orElse(null);
        if (invoice == null) return;

        invoice.setLeaseId(leaseId);
        commissionRepository.save(invoice);

        UserEntity agent = userRepository.findById(invoice.getAgentUserId()).orElse(null);
        if (agent != null) {
            String propLabel = propLabel(invoice.getPropertyId());
            // Plantilla admindi_commission_approved_v1 (destinatario agente):
            //   {{1}}agente {{2}}inmueble {{3}}monto {{4}}URL portal agente.
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("1", firstName(agent));
            vars.put("2", propLabel);
            vars.put("3", formatAmount(invoice.getCommissionAmount()));
            vars.put("4", appUrl + "/dashboard?panel=commissions&invoice=" + invoice.getId());
            domainEventDispatcher.dispatch("COMMISSION_APPROVED",
                    "Comisión confirmada por el dueño",
                    "El dueño confirmó el contrato y generó el expediente del tenant. "
                            + "Tu comisión ($" + formatAmount(invoice.getCommissionAmount())
                            + ") está pendiente de cobro. Configura tu CLABE si aún no lo hiciste: "
                            + vars.get("4"),
                    ownerId, null, List.of(invoice.getAgentUserId()), vars, null);
        }
        logger.info("Comisión {} amarrada al lease {} para agente {}", invoice.getId(), leaseId, invoice.getAgentUserId());
    }

    // ─── Helpers de plantilla ─────────────────────────────────────────────────────

    private String firstName(UserEntity u) {
        if (u == null || u.getName() == null || u.getName().isBlank()) return "";
        return u.getName().trim().split("\\s+")[0];
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    private String propLabel(String propertyId) {
        if (propertyId == null) return "Inmueble";
        return propertyRepository.findById(propertyId).map(PropertyEntity::getName).orElse("Inmueble");
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private UserEntity currentAgent() {
        UserEntity u = userRepository.findByLoginIdentifier(
                SecurityContextHolder.getContext().getAuthentication().getName()).orElseThrow();
        if (u.getRole() != Role.REAL_ESTATE_AGENT) {
            throw new SecurityException("Acción reservada al agente inmobiliario.");
        }
        return u;
    }

    /** DTO de resultado para el controller. */
    public record ContractClosureResult(AgentCommissionInvoiceEntity invoice, VacancyEntity vacancy,
                                        ProspectSubmissionEntity prospect) {}
}
