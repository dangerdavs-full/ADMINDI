package com.admindi.backend.controller;

import com.admindi.backend.model.AgentBankAccountEntity;
import com.admindi.backend.model.AgentCommissionInvoiceEntity;
import com.admindi.backend.model.AgentNotificationChainEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.ProspectSubmissionEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.repository.AgentNotificationChainRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.repository.VacancyRepository;
import com.admindi.backend.service.AgentBankAccountService;
import com.admindi.backend.service.AgentCommissionService;
import com.admindi.backend.service.ContractClosureService;
import com.admindi.backend.service.FileOwnershipService;
import com.admindi.backend.service.FileStorageService;
import com.admindi.backend.service.ProspectService;
import com.admindi.backend.service.VacancyAgentOrchestrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Panel del agente inmobiliario: vacancias asignadas, fotos, prospectos, cierre de
 * contrato, comisiones y CLABE.
 */
@RestController
@RequestMapping("/api/real-estate-agent")
@PreAuthorize("hasRole('REAL_ESTATE_AGENT')")
public class RealEstateAgentController {

    private final VacancyAgentOrchestrationService vacancyOrchestrator;
    private final ProspectService prospectService;
    private final ContractClosureService contractClosureService;
    private final AgentCommissionService commissionService;
    private final AgentBankAccountService bankAccountService;
    private final VacancyRepository vacancyRepository;
    private final AgentNotificationChainRepository chainRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorage;
    private final FileOwnershipService fileOwnership;

    public RealEstateAgentController(VacancyAgentOrchestrationService vacancyOrchestrator,
                                     ProspectService prospectService,
                                     ContractClosureService contractClosureService,
                                     AgentCommissionService commissionService,
                                     AgentBankAccountService bankAccountService,
                                     VacancyRepository vacancyRepository,
                                     AgentNotificationChainRepository chainRepository,
                                     PropertyRepository propertyRepository,
                                     UserRepository userRepository,
                                     FileStorageService fileStorage,
                                     FileOwnershipService fileOwnership) {
        this.vacancyOrchestrator = vacancyOrchestrator;
        this.prospectService = prospectService;
        this.contractClosureService = contractClosureService;
        this.commissionService = commissionService;
        this.bankAccountService = bankAccountService;
        this.vacancyRepository = vacancyRepository;
        this.chainRepository = chainRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
        this.fileOwnership = fileOwnership;
    }

    // ── Upload genérico (evidencias del agente) ──────────────────────────────

    /**
     * Sube un archivo (foto de inmueble o PDF de contrato firmado) y devuelve
     * su {@code fileId}. El agente luego pasa este id a los endpoints de
     * {@code /photos} o {@code /close}.
     */
    @PostMapping(path = "/files/upload", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file,
                                                           @RequestParam(value = "category", defaultValue = "agent-evidence") String category) {
        String fileId = fileStorage.store(file, category);
        String actorEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        userRepository.findByLoginIdentifier(actorEmail).ifPresent(u ->
                fileOwnership.registerClaim(fileId, u.getId(), category));
        return ResponseEntity.ok(Map.of("fileId", fileId));
    }

    // ── Vacancia: listados ─────────────────────────────────────────────────────

    /**
     * Invitaciones vigentes (PENDING) para este agente. Son vacancias donde el
     * dueño lo tiene en su lista y le corresponde turno ahora. El agente tiene 72h
     * desde {@code notifiedAt} para aceptar o rechazar (ver {@code expiresAt}).
     */
    @GetMapping("/vacancies/invitations")
    public ResponseEntity<List<Map<String, Object>>> listInvitations() {
        String agentId = currentAgentId();
        List<AgentNotificationChainEntity> pending = chainRepository
                .findByAgentUserIdAndDecisionOrderByNotifiedAtDesc(
                        agentId, AgentNotificationChainEntity.DECISION_PENDING);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentNotificationChainEntity link : pending) {
            if (!AgentNotificationChainEntity.RESOURCE_VACANCY.equals(link.getResourceType())) continue;
            VacancyEntity v = vacancyRepository.findById(link.getResourceId()).orElse(null);
            if (v == null || v.getClosedAt() != null) continue;
            out.add(buildVacancyView(v, link));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Vacancias ya aceptadas y en curso (photos, prospect, contract) o recién
     * aceptadas por este agente. Es el buzón principal del agente.
     */
    @GetMapping("/vacancies/mine")
    public ResponseEntity<List<Map<String, Object>>> listMyVacancies() {
        String agentId = currentAgentId();
        List<VacancyEntity> mine = vacancyRepository
                .findByAssignedAgentIdAndClosedAtIsNullOrderByOpenedAtDesc(agentId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (VacancyEntity v : mine) {
            out.add(buildVacancyView(v, null));
        }
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> buildVacancyView(VacancyEntity v, AgentNotificationChainEntity link) {
        PropertyEntity p = propertyRepository.findById(v.getPropertyId()).orElse(null);
        UserEntity owner = userRepository.findById(v.getOwnerId()).orElse(null);
        Map<String, Object> m = new HashMap<>();
        m.put("id", v.getId());
        m.put("status", v.getStatus());
        m.put("chainState", v.getChainState());
        m.put("openedAt", v.getOpenedAt());
        m.put("notes", v.getNotes());
        m.put("photosUploadedAt", v.getPhotosUploadedAt());
        m.put("contractSignedAt", v.getContractSignedAt());
        m.put("contractMonths", v.getContractMonths());
        m.put("contractMonthlyRent", v.getContractMonthlyRent());
        m.put("contractDeposit", v.getContractDeposit());
        m.put("propertyId", v.getPropertyId());
        m.put("propertyName", p != null ? p.getName() : null);
        m.put("propertyAddress", p != null ? p.getAddress() : null);
        m.put("ownerId", v.getOwnerId());
        m.put("ownerName", owner != null ? owner.getName() : null);
        m.put("ownerEmail", owner != null ? owner.getContactEmail() : null);
        m.put("ownerPhone", owner != null ? owner.getPhone() : null);
        m.put("assignedAgentId", v.getAssignedAgentId());
        if (link != null) {
            Map<String, Object> inv = new HashMap<>();
            inv.put("linkId", link.getId());
            inv.put("notifiedAt", link.getNotifiedAt());
            inv.put("expiresAt", link.getExpiresAt());
            inv.put("priorityOrder", link.getPriorityOrder());
            m.put("invitation", inv);
            m.put("expiresInHours", hoursUntil(link.getExpiresAt()));
        }
        return m;
    }

    private Long hoursUntil(LocalDateTime target) {
        if (target == null) return null;
        long mins = java.time.Duration.between(LocalDateTime.now(), target).toMinutes();
        return Math.max(0L, mins / 60L);
    }

    private String currentAgentId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity u = userRepository.findByLoginIdentifier(email).orElseThrow(
                () -> new SecurityException("Usuario no encontrado"));
        if (u.getRole() != Role.REAL_ESTATE_AGENT) {
            throw new SecurityException("Acción reservada al agente inmobiliario.");
        }
        return u.getId();
    }

    // ── Vacancia: accept/reject/fotos ──────────────────────────────────────────

    @PostMapping("/vacancies/{vacancyId}/accept")
    public ResponseEntity<VacancyEntity> acceptVacancy(@PathVariable String vacancyId,
                                                        @RequestBody(required = false) Map<String, String> body) {
        // V63 — aceptar una vacancia implica que eventualmente se le paga comisión.
        // Exigimos cuenta bancaria completa antes de operar.
        bankAccountService.requireCompleteAccountForCurrentAgent();
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(vacancyOrchestrator.agentAccept(vacancyId, reason));
    }

    @PostMapping("/vacancies/{vacancyId}/reject")
    public ResponseEntity<VacancyEntity> rejectVacancy(@PathVariable String vacancyId,
                                                        @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(vacancyOrchestrator.agentReject(vacancyId, reason));
    }

    @PostMapping("/vacancies/{vacancyId}/photos")
    public ResponseEntity<VacancyEntity> recordPhotos(@PathVariable String vacancyId,
                                                       @RequestBody Map<String, List<String>> body) {
        List<String> fileIds = body.getOrDefault("propertyFileIds", List.of());
        return ResponseEntity.ok(vacancyOrchestrator.recordPhotosUploaded(vacancyId, fileIds));
    }

    // ── Prospectos ─────────────────────────────────────────────────────────────

    @GetMapping("/prospects")
    public ResponseEntity<List<ProspectSubmissionEntity>> listProspects() {
        return ResponseEntity.ok(prospectService.listMineAsAgent());
    }

    @PostMapping("/prospects")
    public ResponseEntity<ProspectSubmissionEntity> submitProspect(@RequestBody Map<String, String> body) {
        // V63 — proponer inquilino genera comisión al cierre; exigimos CLABE completa.
        bankAccountService.requireCompleteAccountForCurrentAgent();
        return ResponseEntity.ok(prospectService.submit(
                body.get("vacancyId"),
                body.get("name"),
                body.get("phone"),
                body.get("email"),
                body.get("notes")));
    }

    // ── Cierre de contrato (reporta, no crea lease) ────────────────────────────

    @PostMapping("/vacancies/{vacancyId}/close")
    public ResponseEntity<ContractClosureService.ContractClosureResult> closeContract(
            @PathVariable String vacancyId,
            @RequestBody Map<String, Object> body) {
        // V63 — el cierre dispara la comisión; sin CLABE no hay cómo pagarla.
        bankAccountService.requireCompleteAccountForCurrentAgent();
        String evidenceFileId = (String) body.get("evidenceFileId");
        Integer months = body.get("months") != null ? ((Number) body.get("months")).intValue() : null;
        BigDecimal monthlyRent = body.get("monthlyRent") != null
                ? new BigDecimal(body.get("monthlyRent").toString()) : null;
        BigDecimal deposit = body.get("deposit") != null
                ? new BigDecimal(body.get("deposit").toString()) : BigDecimal.ZERO;
        String agentSource = (String) body.get("agentSource"); // PLATFORM o PRIVATE
        BigDecimal overridePct = body.get("commissionPct") != null
                ? new BigDecimal(body.get("commissionPct").toString()) : null;
        return ResponseEntity.ok(contractClosureService.reportClosure(
                vacancyId, evidenceFileId, months, monthlyRent, deposit, agentSource, overridePct));
    }

    // ── Comisiones ─────────────────────────────────────────────────────────────

    @GetMapping("/commissions")
    public ResponseEntity<List<AgentCommissionInvoiceEntity>> listCommissions() {
        return ResponseEntity.ok(commissionService.listMineAsAgent());
    }

    @PostMapping("/commissions/{invoiceId}/confirm-received")
    public ResponseEntity<AgentCommissionInvoiceEntity> confirmReceived(@PathVariable String invoiceId) {
        return ResponseEntity.ok(commissionService.agentManualConfirm(invoiceId));
    }

    // ── CLABE ─────────────────────────────────────────────────────────────────

    @GetMapping("/bank-account")
    public ResponseEntity<AgentBankAccountEntity> getBankAccount() {
        return bankAccountService.getMine().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/bank-account")
    public ResponseEntity<AgentBankAccountEntity> upsertBankAccount(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(bankAccountService.upsertMine(
                body.get("clabe"),
                body.get("bankName"),
                body.get("accountHolder")));
    }

    /**
     * V63 — estado de onboarding para decidir si el frontend bloquea el dashboard
     * con el wizard. Espejo del endpoint del provider para mantener simetría.
     */
    @GetMapping("/bank-account/status")
    public ResponseEntity<Map<String, Object>> bankAccountStatus() {
        String agentId = currentAgentId();
        boolean complete = bankAccountService.isAccountComplete(agentId);
        return ResponseEntity.ok(Map.of(
                "complete", complete,
                "accountActive", complete
        ));
    }
}
