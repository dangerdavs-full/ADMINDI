package com.admindi.backend.service;

import com.admindi.backend.model.AgentBankAccountEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AgentBankAccountRepository;
import com.admindi.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gestión de la CLABE del agente (REAL_ESTATE_AGENT o MAINTENANCE_PROVIDER) para
 * recibir pagos del dueño.
 *
 * <p>Validación Banxico: en esta fase usamos validación <strong>sintáctica</strong>
 * (algoritmo módulo 10 ponderado) como el validador principal. La integración real
 * con Banxico (consulta SPEI de nombre del titular) vive en {@code BanxicoCepAdapter}
 * y hoy es un mock; se mantiene la interfaz para cuando Banxico provea el endpoint
 * real. Cuando la CLABE es sintácticamente válida, la marcamos VALIDATED; cuando
 * falla sintácticamente o Banxico devuelve error, incrementamos el contador de
 * intentos — al llegar a {@link AgentBankAccountEntity#MAX_VALIDATION_ATTEMPTS} se
 * congela en FAILED y el agente debe corregir manualmente.
 */
@Service
public class AgentBankAccountService {

    private final AgentBankAccountRepository repository;
    private final UserRepository userRepository;
    private final DomainEventDispatcher domainEventDispatcher;
    private final BanxicoInstitutionCatalogService banxicoInstitutionCatalogService;

    @Value("${app.url:https://app.admindi.com}")
    private String appUrl;

    public AgentBankAccountService(AgentBankAccountRepository repository,
                                   UserRepository userRepository,
                                   DomainEventDispatcher domainEventDispatcher,
                                   BanxicoInstitutionCatalogService banxicoInstitutionCatalogService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.domainEventDispatcher = domainEventDispatcher;
        this.banxicoInstitutionCatalogService = banxicoInstitutionCatalogService;
    }

    private UserEntity currentAgent() {
        UserEntity u = userRepository.findByLoginIdentifier(
                SecurityContextHolder.getContext().getAuthentication().getName()).orElseThrow();
        if (u.getRole() != Role.REAL_ESTATE_AGENT && u.getRole() != Role.MAINTENANCE_PROVIDER) {
            throw new SecurityException("Solo un agente (inmobiliario o de mantenimiento) puede administrar su CLABE.");
        }
        return u;
    }

    /** Datos de la CLABE del agente autenticado; {@code empty} si nunca configuró una. */
    public Optional<AgentBankAccountEntity> getMine() {
        UserEntity u = currentAgent();
        return repository.findByAgentUserId(u.getId());
    }

    /** Dato público mínimo para mostrar al owner al pedirle que pague SPEI. */
    public Optional<AgentBankAccountEntity> getForAgent(String agentUserId) {
        return repository.findByAgentUserId(agentUserId);
    }

    /**
     * V63 — La cuenta del agente está "completa" si tiene CLABE + banco + titular
     * no vacíos. NO exigimos validación Banxico (status=VALIDATED) porque el
     * validador hoy es sintáctico y el mock puede mantenerse VALIDATED sin banxico
     * real; exigir VALIDATED aquí bloquearía demasiado a los agentes legítimos.
     *
     * <p>Usado por:</p>
     * <ul>
     *   <li>{@code requireCompleteAccountForCurrentAgent} — guard en endpoints
     *       operativos (aceptar ticket, cotizar, aceptar vacancy, proponer
     *       prospecto, etc.).</li>
     *   <li>Frontend {@code AgentOnboardingWizard} para decidir si bloquea el
     *       dashboard al primer login.</li>
     *   <li>{@code OwnerLinkedProviderController} para mostrar el chip "cuenta
     *       activa" al dueño.</li>
     * </ul>
     */
    public boolean isAccountComplete(String agentUserId) {
        if (agentUserId == null || agentUserId.isBlank()) {
            // #region agent log
            writeDebug("BANK", "isAccountComplete:blank_user", Map.of("userId", "null_or_blank"));
            // #endregion
            return false;
        }
        Optional<AgentBankAccountEntity> acct = repository.findByAgentUserId(agentUserId);
        boolean result = acct
                .map(a -> notBlank(a.getClabe()) && notBlank(a.getBankName()) && notBlank(a.getAccountHolder()))
                .orElse(false);
        // #region agent log
        writeDebug("BANK", "isAccountComplete:check", Map.of(
                "userId", agentUserId,
                "hasRecord", acct.isPresent(),
                "clabePresent", acct.map(a -> notBlank(a.getClabe())).orElse(false),
                "bankPresent", acct.map(a -> notBlank(a.getBankName())).orElse(false),
                "holderPresent", acct.map(a -> notBlank(a.getAccountHolder())).orElse(false),
                "result", result));
        // #endregion
        return result;
    }

    /**
     * Guard: si el agente autenticado NO tiene cuenta bancaria completa, lanza
     * una excepción 412 PRECONDITION_FAILED con code {@code BANK_ACCOUNT_REQUIRED}
     * que el frontend intercepta para reabrir el wizard de onboarding.
     *
     * <p>Llamado explícitamente desde los endpoints operativos del agente. Se
     * prefiere llamada explícita sobre un aspect para que sea evidente en cada
     * sitio qué exige la precondición.</p>
     *
     * @throws org.springframework.web.server.ResponseStatusException 412 si falta
     */
    public void requireCompleteAccountForCurrentAgent() {
        UserEntity u = currentAgent();
        boolean complete = isAccountComplete(u.getId());
        // #region agent log
        writeDebug("BANK", "requireCompleteAccount:check", Map.of(
                "userId", u.getId(),
                "username", String.valueOf(u.getLoginUsername()),
                "role", String.valueOf(u.getRole()),
                "complete", complete));
        // #endregion
        if (!complete) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                    "BANK_ACCOUNT_REQUIRED");
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // #region agent log
    private static final java.nio.file.Path DEBUG_LOG_PATH =
            java.nio.file.Path.of("..", "debug-93290f.log").toAbsolutePath().normalize();
    private static void writeDebug(String hypothesisId, String msg, Map<String, Object> data) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"sessionId\":\"93290f\",\"hypothesisId\":\"").append(hypothesisId)
              .append("\",\"location\":\"AgentBankAccountService.java\",\"message\":\"")
              .append(msg.replace("\"", "\\\"")).append("\",\"timestamp\":")
              .append(System.currentTimeMillis()).append(",\"data\":{");
            boolean first = true;
            for (Map.Entry<String, Object> e : data.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v == null) sb.append("null");
                else if (v instanceof Number || v instanceof Boolean) sb.append(v);
                else sb.append("\"").append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            }
            sb.append("}}\n");
            java.nio.file.Files.writeString(DEBUG_LOG_PATH, sb.toString(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignore) {}
    }
    // #endregion

    /**
     * Upsert de la CLABE del agente autenticado. Ejecuta una pasada de validación
     * inmediatamente (sintáctica). Si la CLABE es inválida sintácticamente, lanza
     * excepción — no guardamos basura en la base.
     */
    @Transactional
    public AgentBankAccountEntity upsertMine(String clabe, String bankName, String accountHolder) {
        UserEntity u = currentAgent();
        if (clabe == null || clabe.isBlank()) {
            throw new IllegalArgumentException("CLABE obligatoria.");
        }
        String cleaned = clabe.trim();
        if (!ClabeValidator.isValid(cleaned)) {
            throw new IllegalArgumentException("CLABE sintácticamente inválida — revisa los 18 dígitos.");
        }
        String canonicalBankName = banxicoInstitutionCatalogService
                .resolveReceiver(cleaned, bankName)
                .map(BanxicoInstitutionCatalogService.ResolvedInstitution::name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No pude identificar un banco Banxico válido para esa CLABE."));

        AgentBankAccountEntity entity = repository.findByAgentUserId(u.getId())
                .orElseGet(() -> {
                    AgentBankAccountEntity e = new AgentBankAccountEntity();
                    e.setAgentUserId(u.getId());
                    return e;
                });
        boolean clabeChanged = !cleaned.equals(entity.getClabe());
        entity.setClabe(cleaned);
        entity.setBankName(canonicalBankName);
        entity.setAccountHolder(accountHolder);
        entity.setUpdatedAt(LocalDateTime.now());

        // Si la CLABE cambió, reseteamos los contadores — es una cuenta nueva.
        if (clabeChanged) {
            entity.setValidationStatus(AgentBankAccountEntity.STATUS_PENDING);
            entity.setValidationAttempts(0);
            entity.setLastValidationError(null);
            entity.setValidatedAt(null);
        }

        AgentBankAccountEntity saved = repository.save(entity);
        triggerValidation(saved);
        return repository.findById(saved.getId()).orElse(saved);
    }

    /**
     * Intenta validar la CLABE (hasta 3 veces). Una sola llamada incrementa el contador
     * cuando hay error; si pasa, marca VALIDATED y dispara notificación al agente.
     */
    @Transactional
    public AgentBankAccountEntity triggerValidation(AgentBankAccountEntity account) {
        if (AgentBankAccountEntity.STATUS_VALIDATED.equals(account.getValidationStatus())) {
            return account; // ya validada, no reintentar
        }
        if (account.getValidationAttempts() >= AgentBankAccountEntity.MAX_VALIDATION_ATTEMPTS) {
            // Ya agotó los 3 intentos; el agente debe reintentar manualmente cambiando la CLABE.
            return account;
        }

        account.setValidationAttempts(account.getValidationAttempts() + 1);

        // Validación actual (mock Banxico) = sintáctica. Cuando el adapter real esté listo,
        // aquí se invoca BanxicoCepAdapter.validateClabe(...) y se captura su respuesta.
        boolean ok = ClabeValidator.isValid(account.getClabe());
        if (ok) {
            account.setValidationStatus(AgentBankAccountEntity.STATUS_VALIDATED);
            account.setValidatedAt(LocalDateTime.now());
            account.setLastValidationError(null);
            AgentBankAccountEntity saved = repository.save(account);
            UserEntity agent = userRepository.findById(account.getAgentUserId()).orElse(null);
            // Plantilla admindi_agent_bank_account_validated_v1:
            //   {{1}}nombre agente {{2}}últimos 4 de CLABE {{3}}banco.
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("1", firstName(agent));
            vars.put("2", lastFour(saved.getClabe()));
            vars.put("3", nullSafe(saved.getBankName()));
            domainEventDispatcher.dispatch("AGENT_BANK_ACCOUNT_VALIDATED",
                    "CLABE validada",
                    "Tu CLABE " + saved.maskedClabe() + " fue validada. El dueño ya puede depositarte.",
                    null, null, List.of(saved.getAgentUserId()), vars, null);
            return saved;
        }
        account.setLastValidationError("CLABE sintácticamente inválida.");
        if (account.getValidationAttempts() >= AgentBankAccountEntity.MAX_VALIDATION_ATTEMPTS) {
            account.setValidationStatus(AgentBankAccountEntity.STATUS_FAILED);
            AgentBankAccountEntity saved = repository.save(account);
            UserEntity agent = userRepository.findById(account.getAgentUserId()).orElse(null);
            // Plantilla admindi_agent_bank_account_failed_v1:
            //   {{1}}nombre {{2}}motivo {{3}}URL portal agente (sección CLABE).
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("1", firstName(agent));
            vars.put("2", nullSafe(saved.getLastValidationError()));
            vars.put("3", appUrl + "/dashboard?panel=agent&tab=clabe");
            domainEventDispatcher.dispatch("AGENT_BANK_ACCOUNT_FAILED",
                    "CLABE no pudo ser validada",
                    "Tu CLABE " + saved.maskedClabe() + " falló los 3 intentos de validación. "
                            + "Por favor corrígela en tu panel: " + vars.get("3"),
                    null, null, List.of(saved.getAgentUserId()), vars, null);
            return saved;
        }
        return repository.save(account);
    }

    // ─── Helpers de plantilla ─────────────────────────────────────────────────────

    private String firstName(UserEntity u) {
        if (u == null || u.getName() == null || u.getName().isBlank()) return "";
        return u.getName().trim().split("\\s+")[0];
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    private String lastFour(String clabe) {
        if (clabe == null || clabe.length() < 4) return "****";
        return clabe.substring(clabe.length() - 4);
    }
}
