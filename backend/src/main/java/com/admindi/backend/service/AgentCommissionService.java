package com.admindi.backend.service;

import com.admindi.backend.model.AgentBankAccountEntity;
import com.admindi.backend.model.AgentCommissionInvoiceEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AgentBankAccountRepository;
import com.admindi.backend.repository.AgentCommissionInvoiceRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ciclo de vida del cobro de comisiones al agente inmobiliario.
 *
 * <ul>
 *   <li><strong>Owner sube SPEI proof</strong>: se intenta validar; hasta 3 intentos.
 *       Validación mock hoy: monto debe coincidir con {@code commission_amount}.
 *       Cuando Banxico CEP real esté integrado, aquí se llama al adaptador real.</li>
 *   <li><strong>Fallan 3 intentos</strong>: estado → PENDING_MANUAL_CONFIRM; el agente
 *       recibe aviso y confirma manualmente desde su panel.</li>
 *   <li><strong>Agente confirma manualmente</strong>: estado → PAID.</li>
 * </ul>
 *
 * <p>Nota: este servicio NO crea egresos contables del owner automáticamente; la
 * integración contable (crear una {@code ExpenseEntity} categoría COMMISSION) es
 * responsabilidad del owner en su panel cuando decide registrar el gasto. Se
 * expone {@code #linkExpense} para amarrar una fila contable a la factura.
 */
@Service
public class AgentCommissionService {

    private static final Logger logger = LoggerFactory.getLogger(AgentCommissionService.class);

    private final AgentCommissionInvoiceRepository commissionRepository;
    private final AgentBankAccountRepository bankAccountRepository;
    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;
    private final DomainEventDispatcher domainEventDispatcher;
    private final FileOwnershipService fileOwnership;
    private final BanxicoInstitutionCatalogService banxicoInstitutionCatalogService;

    @Value("${app.url:https://app.admindi.com}")
    private String appUrl;

    public AgentCommissionService(AgentCommissionInvoiceRepository commissionRepository,
                                  AgentBankAccountRepository bankAccountRepository,
                                  UserRepository userRepository,
                                  PropertyRepository propertyRepository,
                                  DomainEventDispatcher domainEventDispatcher,
                                  FileOwnershipService fileOwnership,
                                  BanxicoInstitutionCatalogService banxicoInstitutionCatalogService) {
        this.commissionRepository = commissionRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.userRepository = userRepository;
        this.propertyRepository = propertyRepository;
        this.domainEventDispatcher = domainEventDispatcher;
        this.fileOwnership = fileOwnership;
        this.banxicoInstitutionCatalogService = banxicoInstitutionCatalogService;
    }

    private UserEntity currentUser() {
        return userRepository.findByLoginIdentifier(
                SecurityContextHolder.getContext().getAuthentication().getName()).orElseThrow();
    }

    /** Listado del agente autenticado. */
    public List<AgentCommissionInvoiceEntity> listMineAsAgent() {
        UserEntity u = currentUser();
        if (u.getRole() != Role.REAL_ESTATE_AGENT) {
            throw new SecurityException("Solo el agente ve sus comisiones.");
        }
        return commissionRepository.findByAgentUserIdOrderByCreatedAtDesc(u.getId());
    }

    /** Listado del owner autenticado. */
    public List<AgentCommissionInvoiceEntity> listForOwner() {
        UserEntity u = currentUser();
        if (u.getRole() != Role.OWNER && u.getRole() != Role.SUPER_ADMIN && u.getRole() != Role.ACCOUNTANT) {
            throw new SecurityException("Solo el owner/admin/contador ve estas comisiones.");
        }
        return commissionRepository.findByOwnerIdOrderByCreatedAtDesc(u.getId());
    }

    /**
     * Owner sube el comprobante SPEI. Intenta validar inmediatamente (mock = monto
     * debe coincidir). Si falla, incrementa intentos; al 3er fallo marca manual.
     */
    @Transactional
    public AgentCommissionInvoiceEntity submitSpeiProof(String invoiceId, String proofFileId,
                                                        BigDecimal declaredAmount, String claveRastreo,
                                                        String bankEmitter) {
        UserEntity owner = currentUser();
        AgentCommissionInvoiceEntity invoice = commissionRepository.findById(invoiceId).orElseThrow();
        if (owner.getRole() == Role.OWNER && !invoice.getOwnerId().equals(owner.getId())) {
            throw new SecurityException("Esta comisión no es tuya.");
        }
        if (AgentCommissionInvoiceEntity.STATUS_PAID.equals(invoice.getStatus())) {
            throw new IllegalStateException("Esta comisión ya está pagada.");
        }
        if (AgentCommissionInvoiceEntity.STATUS_VOIDED.equals(invoice.getStatus())) {
            throw new IllegalStateException("Esta comisión fue anulada.");
        }
        if (proofFileId == null || proofFileId.isBlank()) {
            throw new IllegalArgumentException("File ID del comprobante obligatorio.");
        }
        // Defensa IDOR: el comprobante debe haber sido subido por el mismo
        // usuario que está cerrando el pago. Para SUPER_ADMIN se exige lo
        // mismo (si actúa en representación del owner, debe subir el archivo
        // él mismo durante la sesión) — así evitamos que un token robado use
        // un path ajeno filtrado por logs.
        fileOwnership.assertUploader(proofFileId, owner.getId());
        fileOwnership.markConsumed(proofFileId, "AGENT_COMMISSION_INVOICE", invoice.getId());

        String canonicalEmitter = bankEmitter == null || bankEmitter.isBlank()
                ? null
                : banxicoInstitutionCatalogService.resolveEmitter(bankEmitter)
                .map(BanxicoInstitutionCatalogService.ResolvedInstitution::name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Selecciona un banco emisor válido del catálogo Banxico."));

        invoice.setSpeiProofFileId(proofFileId);
        invoice.setStatus(AgentCommissionInvoiceEntity.STATUS_AWAITING_SPEI);
        invoice.setSpeiValidationAttempts(invoice.getSpeiValidationAttempts() + 1);
        invoice.setUpdatedAt(LocalDateTime.now());

        // Validación mock: monto declarado debe coincidir con commission_amount ± 1 peso.
        boolean valid = declaredAmount != null
                && declaredAmount.subtract(invoice.getCommissionAmount()).abs().compareTo(BigDecimal.ONE) <= 0
                && claveRastreo != null && !claveRastreo.isBlank()
                && canonicalEmitter != null && !canonicalEmitter.isBlank();

        if (valid) {
            invoice.setStatus(AgentCommissionInvoiceEntity.STATUS_PAID);
            invoice.setPaidAt(LocalDateTime.now());
            invoice.setSpeiLastError(null);
            AgentCommissionInvoiceEntity saved = commissionRepository.save(invoice);
            notifyCommissionPaid(saved);
            return saved;
        }

        String err = "Validación SPEI falló (intento " + invoice.getSpeiValidationAttempts() + "/"
                + AgentCommissionInvoiceEntity.MAX_SPEI_ATTEMPTS + "). Verifica monto, clave de rastreo y banco emisor.";
        invoice.setSpeiLastError(err);

        if (invoice.getSpeiValidationAttempts() >= AgentCommissionInvoiceEntity.MAX_SPEI_ATTEMPTS) {
            invoice.setStatus(AgentCommissionInvoiceEntity.STATUS_PENDING_MANUAL);
            AgentCommissionInvoiceEntity saved = commissionRepository.save(invoice);
            UserEntity agent = userRepository.findById(invoice.getAgentUserId()).orElse(null);
            // Plantilla admindi_commission_spei_pending_manual_v1:
            //   {{1}}agente {{2}}monto {{3}}clave rastreo {{4}}URL portal.
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("1", firstName(agent));
            vars.put("2", formatAmount(invoice.getCommissionAmount()));
            vars.put("3", claveRastreo != null ? claveRastreo : "(sin clave)");
            vars.put("4", appUrl + "/dashboard?panel=commissions&invoice=" + invoice.getId());
            domainEventDispatcher.dispatch("COMMISSION_SPEI_PENDING_MANUAL",
                    "SPEI falló 3 intentos — confirma manualmente",
                    "El dueño subió comprobante pero no se pudo validar automáticamente. "
                            + "Revisa tu cuenta bancaria y confirma si recibiste $" + invoice.getCommissionAmount()
                            + " (clave " + vars.get("3") + ").",
                    invoice.getOwnerId(), null, List.of(invoice.getAgentUserId()), vars, null);
            return saved;
        }
        return commissionRepository.save(invoice);
    }

    /** El agente confirma manualmente que recibió el pago (escape hatch). */
    @Transactional
    public AgentCommissionInvoiceEntity agentManualConfirm(String invoiceId) {
        UserEntity agent = currentUser();
        if (agent.getRole() != Role.REAL_ESTATE_AGENT) {
            throw new SecurityException("Solo el agente puede confirmar recepción.");
        }
        AgentCommissionInvoiceEntity invoice = commissionRepository.findById(invoiceId).orElseThrow();
        if (!invoice.getAgentUserId().equals(agent.getId())) {
            throw new SecurityException("Esta comisión no es tuya.");
        }
        if (!AgentCommissionInvoiceEntity.STATUS_PENDING_MANUAL.equals(invoice.getStatus())
                && !AgentCommissionInvoiceEntity.STATUS_AWAITING_SPEI.equals(invoice.getStatus())) {
            throw new IllegalStateException("Esta comisión no está pendiente de confirmación manual.");
        }
        invoice.setStatus(AgentCommissionInvoiceEntity.STATUS_PAID);
        invoice.setPaidAt(LocalDateTime.now());
        invoice.setSpeiLastError(null);
        AgentCommissionInvoiceEntity saved = commissionRepository.save(invoice);
        notifyCommissionPaid(saved);
        return saved;
    }

    /** Amarra una ExpenseEntity creada por el owner a la factura de comisión. */
    @Transactional
    public AgentCommissionInvoiceEntity linkExpense(String invoiceId, String expenseId) {
        AgentCommissionInvoiceEntity invoice = commissionRepository.findById(invoiceId).orElseThrow();
        invoice.setExpenseId(expenseId);
        invoice.setUpdatedAt(LocalDateTime.now());
        return commissionRepository.save(invoice);
    }

    /** Anula una comisión (p.ej. contrato cancelado antes de pagar). */
    @Transactional
    public AgentCommissionInvoiceEntity voidInvoice(String invoiceId, String reason) {
        UserEntity owner = currentUser();
        AgentCommissionInvoiceEntity invoice = commissionRepository.findById(invoiceId).orElseThrow();
        if (owner.getRole() == Role.OWNER && !invoice.getOwnerId().equals(owner.getId())) {
            throw new SecurityException("Esta comisión no es tuya.");
        }
        if (AgentCommissionInvoiceEntity.STATUS_PAID.equals(invoice.getStatus())) {
            throw new IllegalStateException("No se puede anular una comisión ya pagada.");
        }
        invoice.setStatus(AgentCommissionInvoiceEntity.STATUS_VOIDED);
        invoice.setSpeiLastError(reason);
        return commissionRepository.save(invoice);
    }

    private void notifyCommissionPaid(AgentCommissionInvoiceEntity invoice) {
        UserEntity agent = userRepository.findById(invoice.getAgentUserId()).orElse(null);
        UserEntity owner = userRepository.findById(invoice.getOwnerId()).orElse(null);
        String propLabel = propLabel(invoice.getPropertyId());
        String amount = formatAmount(invoice.getCommissionAmount());
        String datePaid = invoice.getPaidAt() != null
                ? invoice.getPaidAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                : LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        // Plantilla admindi_commission_paid_v1 (5 slots): {{1}}dest {{2}}inmueble
        //   {{3}}monto {{4}}fecha {{5}}URL portal.
        Map<String, String> agentVars = new LinkedHashMap<>();
        agentVars.put("1", firstName(agent));
        agentVars.put("2", propLabel);
        agentVars.put("3", amount);
        agentVars.put("4", datePaid);
        agentVars.put("5", appUrl + "/dashboard?panel=commissions&invoice=" + invoice.getId());
        domainEventDispatcher.dispatch("COMMISSION_PAID",
                "Comisión pagada — $" + amount,
                "Se confirmó el pago de tu comisión por $" + amount
                        + " (contrato de " + invoice.getContractMonths() + " meses) el " + datePaid + ".",
                invoice.getOwnerId(), null, List.of(invoice.getAgentUserId()), agentVars, null);

        Map<String, String> ownerVars = new LinkedHashMap<>();
        ownerVars.put("1", firstName(owner));
        ownerVars.put("2", propLabel);
        ownerVars.put("3", amount);
        ownerVars.put("4", datePaid);
        ownerVars.put("5", appUrl + "/dashboard?panel=owner&invoice=" + invoice.getId());
        domainEventDispatcher.dispatch("COMMISSION_PAID",
                "Comisión marcada como pagada",
                "La comisión del agente ($" + amount + ") quedó registrada como PAGADA el " + datePaid + ".",
                invoice.getOwnerId(), null, List.of(invoice.getOwnerId()), ownerVars, null);
    }

    /** Helper para leer datos bancarios del agente (para mostrar al owner al pagar). */
    public AgentBankAccountEntity getAgentBankAccount(String agentUserId) {
        return bankAccountRepository.findByAgentUserId(agentUserId).orElse(null);
    }

    // ─── Helpers de plantilla ─────────────────────────────────────────────────────

    private String firstName(UserEntity u) {
        if (u == null || u.getName() == null || u.getName().isBlank()) return "";
        return u.getName().trim().split("\\s+")[0];
    }

    private String propLabel(String propertyId) {
        if (propertyId == null) return "Inmueble";
        return propertyRepository.findById(propertyId).map(PropertyEntity::getName).orElse("Inmueble");
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
