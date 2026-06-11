package com.admindi.backend.service;

import com.admindi.backend.ai.BanxicoAdaptiveAi;
import com.admindi.backend.ai.BanxicoCepScraper;
import com.admindi.backend.ai.BanxicoCepScraper.ScrapeInput;
import com.admindi.backend.ai.BanxicoCepScraper.ScrapeResult;
import com.admindi.backend.model.BanxicoScrapeSchemaEntity;
import com.admindi.backend.model.CepValidationAttempt;
import com.admindi.backend.model.TransferProofSubmission;
import com.admindi.backend.repository.CepValidationAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter para la validación Banxico CEP (Comprobante Electrónico de Pago).
 *
 * STATUS: producción.
 *
 * Flujo:
 *  1. Valida campos mínimos en el submission del inquilino (contrato previo).
 *  2. Llama a {@link BanxicoCepScraper} con el schema activo.
 *  3. Si el scraper devuelve "schema_failure" (HTML cambió y los selectores
 *     ya no aplican), invoca {@link BanxicoAdaptiveAi#reinferSchema} para
 *     generar una nueva versión de selectores con Claude. Si pasa validación,
 *     reintenta el scrape una sola vez.
 *  4. Si después del reintento sigue fallando, registra el failure y devuelve
 *     un resultado de "no disponible" (el flujo de {@code LedgerService} cae
 *     al override manual del dueño).
 *  5. Compara monto extraído contra monto declarado por el inquilino. Si hay
 *     discrepancia > 0.01 MXN, se rechaza con motivo.
 *
 * La firma pública {@link #validate(TransferProofSubmission)} → {@link CepValidationResult}
 * se mantiene idéntica al adapter MOCK anterior para no requerir cambios en
 * {@code LedgerService}.
 */
@Service
public class BanxicoCepAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BanxicoCepAdapter.class);
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CepValidationAttemptRepository attemptRepo;
    private final BanxicoCepScraper scraper;
    private final BanxicoAdaptiveAi adaptiveAi;

    @Value("${banxico.cep.enabled:false}")
    private boolean cepEnabled;

    @Value("${banxico.cep.adaptive-ai:true}")
    private boolean adaptiveAiEnabled;

    public BanxicoCepAdapter(CepValidationAttemptRepository attemptRepo,
                              BanxicoCepScraper scraper,
                              BanxicoAdaptiveAi adaptiveAi) {
        this.attemptRepo = attemptRepo;
        this.scraper = scraper;
        this.adaptiveAi = adaptiveAi;
    }

    /**
     * Valida un comprobante SPEI contra Banxico CEP.
     *
     * @return CepValidationResult:
     *   - valid=true, missingFields=empty → payment es confirmado.
     *   - valid=false, missingFields=non-empty → inquilino debe completar datos.
     *   - valid=false, missingFields=empty → CEP rechazó o no disponible.
     */
    public CepValidationResult validate(TransferProofSubmission proof) {
        // 1. Check required fields (mismo contrato que el adapter anterior)
        List<String> missing = checkRequiredFields(proof);
        if (!missing.isEmpty()) {
            CepValidationAttempt attempt = beginAttempt(proof);
            attempt.setStatus("INCOMPLETE_DATA");
            attempt.setMissingFields("[\"" + String.join("\",\"", missing) + "\"]");
            attempt.setRequestPayload("{}");
            attempt.setResponsePayload("{\"error\":\"missing_fields\"}");
            attemptRepo.save(attempt);
            logger.info("[CEP] Incomplete data for proof {}: missing {}", proof.getId(), missing);
            return new CepValidationResult(false, missing, "Datos incompletos para validación CEP");
        }

        // 2. Si el CEP está deshabilitado, caemos a pass-through igual que el
        //    comportamiento original (para QA/local sin acceso a Banxico).
        if (!cepEnabled) {
            CepValidationAttempt attempt = beginAttempt(proof);
            attempt.setStatus("SUCCESS");
            attempt.setResponsePayload("{\"valid\":true,\"source\":\"MOCK_FALLBACK\"," +
                    "\"note\":\"banxico.cep.enabled=false\"}");
            attemptRepo.save(attempt);
            logger.info("[CEP] disabled — auto-pass for proof {}", proof.getId());
            return new CepValidationResult(true, List.of(),
                    "Validación CEP no configurada (modo permisivo)");
        }

        // 3. Scrape real
        ScrapeResult result = scraper.validate(buildScrapeInput(proof));
        CepValidationAttempt attempt = beginAttempt(proof);
        attempt.setRequestPayload(buildRequestPayloadJson(proof));

        if (result.schemaFailure()) {
            // 4. Intento adaptativo con IA si está habilitado
            if (adaptiveAiEnabled) {
                logger.warn("[CEP] schema failure on proof {} — triggering adaptive AI", proof.getId());
                Optional<BanxicoScrapeSchemaEntity> newSchema =
                        adaptiveAi.reinferSchema(result.rawHtml(), result.url());
                if (newSchema.isPresent()) {
                    // Reintento único con el nuevo schema.
                    ScrapeResult retry = scraper.validate(buildScrapeInput(proof));
                    if (retry.found()) {
                        return finalizeSuccessOrMismatch(proof, attempt, retry);
                    }
                    attempt.setStatus("AI_ADAPTED_BUT_RETRY_FAILED");
                    attempt.setResponsePayload("{\"error\":\"retry_after_ai_adaptation_failed\"}");
                    attemptRepo.save(attempt);
                    logger.warn("[CEP] retry after AI adaptation still failed for proof {}", proof.getId());
                    return new CepValidationResult(false, List.of(),
                            "Validación CEP no disponible temporalmente. El dueño revisará tu comprobante.");
                }
            } else {
                adaptiveAi.registerFailure(result.rawHtml(), result.url());
            }

            attempt.setStatus("SCHEMA_FAILURE");
            attempt.setResponsePayload("{\"error\":\"schema_failure\",\"adaptive_ai\":"
                    + adaptiveAiEnabled + "}");
            attemptRepo.save(attempt);
            return new CepValidationResult(false, List.of(),
                    "Validación CEP no disponible temporalmente. El dueño revisará tu comprobante.");
        }

        // V58 fix — distinguir "servicio no disponible" de "no encontrado".
        // Servicio no disponible (HTTP 4xx/5xx, red caída, timeout) → señalamos
        // SERVICE_UNAVAILABLE para que LedgerService cae a PENDING_OWNER_VALIDATION
        // sin rechazar al inquilino ni consumir su intento mensual AI.
        if (result.serviceUnavailable()) {
            attempt.setStatus("SERVICE_UNAVAILABLE");
            attempt.setResponsePayload("{\"error\":\"" + safe(result.message()) + "\"}");
            attemptRepo.save(attempt);
            logger.warn("[CEP] Banxico service unavailable for proof {}: {}", proof.getId(), result.message());
            return new CepValidationResult(false, List.of(),
                    "__SERVICE_UNAVAILABLE__"); // marcador especial que el caller reconoce
        }

        if (!result.found()) {
            attempt.setStatus("NOT_FOUND");
            attempt.setResponsePayload("{\"error\":\"" + safe(result.message()) + "\"}");
            attemptRepo.save(attempt);
            logger.info("[CEP] proof {} not found in Banxico ({})", proof.getId(), result.message());
            return new CepValidationResult(false, List.of(),
                    "Banxico no encontró este comprobante. Verifica clave de rastreo y fecha.");
        }

        return finalizeSuccessOrMismatch(proof, attempt, result);
    }

    private CepValidationResult finalizeSuccessOrMismatch(TransferProofSubmission proof,
                                                           CepValidationAttempt attempt,
                                                           ScrapeResult result) {
        Map<String, String> fields = result.fields();

        // V58.1 — FAIL-CLOSED: si el scraper no pudo extraer la cuenta beneficiaria
        // o el monto del CEP, NO declaramos éxito. Tratarlo como rechazo de CEP
        // (no "service unavailable") porque técnicamente Banxico respondió
        // pero con datos incompletos — probablemente los selectores están rotos.
        String cepCuenta = normalizeClabe(fields.get("cuentaBeneficiario"));
        String expectedClabe = normalizeClabe(proof.getAccountReceiver());
        String cepMontoStr = fields.get("monto");
        BigDecimal cepMonto = parseAmount(cepMontoStr);
        BigDecimal declared = proof.getAmount();

        if (cepCuenta == null || cepCuenta.isBlank() || cepMonto == null) {
            attempt.setStatus("REJECTED_CEP_INCOMPLETE");
            attempt.setResponsePayload(buildFieldsJson(fields)
                    + ", \"reason\":\"cep_missing_critical_fields\"");
            attemptRepo.save(attempt);
            logger.warn("[CEP] Respuesta de Banxico sin cuentaBeneficiario o monto para proof {} (cuenta={}, monto={})",
                    proof.getId(), cepCuenta != null, cepMonto != null);
            return new CepValidationResult(false, List.of(),
                    "Banxico respondió con datos incompletos. No podemos validar tu pago automáticamente; "
                    + "tu arrendador lo revisará manualmente.");
        }

        // V58 — Verificar que la cuenta receptora del CEP coincide con la
        // CLABE del dueño. Si NO coinciden, el inquilino transfirió a otra cuenta.
        if (expectedClabe != null && !expectedClabe.isBlank()
                && !cepCuenta.equals(expectedClabe)) {
            attempt.setStatus("REJECTED_ACCOUNT_MISMATCH");
            attempt.setResponsePayload(buildFieldsJson(fields)
                    + ", \"ownerClabeMasked\":\"" + maskClabe(expectedClabe) + "\"");
            attemptRepo.save(attempt);
            logger.warn("[CEP] Account mismatch for proof {}: CEP cuenta={} vs owner={}",
                    proof.getId(), maskClabe(cepCuenta), maskClabe(expectedClabe));
            return new CepValidationResult(false, List.of(),
                    "La cuenta receptora de tu transferencia (CLABE " + maskClabe(cepCuenta)
                            + ") NO coincide con la del arrendador al que intentas pagar. "
                            + "Verifica que estés transfiriendo a la CLABE correcta.");
        }

        // Comparación de monto para blindar contra suplantación (user declara
        // monto menor del real). Toleramos 0.01 por centavos de redondeo.
        if (declared != null
                && cepMonto.subtract(declared).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
            attempt.setStatus("REJECTED_AMOUNT_MISMATCH");
            attempt.setResponsePayload(buildFieldsJson(fields)
                    + ", \"declared\":\"" + declared.toPlainString() + "\"");
            attemptRepo.save(attempt);
            return new CepValidationResult(false, List.of(),
                    "El monto en el CEP (" + cepMonto.toPlainString()
                            + ") no coincide con el monto declarado (" + declared.toPlainString() + ").");
        }

        attempt.setStatus("SUCCESS");
        attempt.setResponsePayload(buildFieldsJson(fields));
        attemptRepo.save(attempt);
        logger.info("[CEP] proof {} validated OK (monto match={})",
                proof.getId(), cepMonto != null);
        return new CepValidationResult(true, List.of(), "Validación CEP exitosa",
                fields, result.cepXml(), result.cepPdfBytes());
    }

    private List<String> checkRequiredFields(TransferProofSubmission proof) {
        List<String> missing = new ArrayList<>();
        if (proof.getClaveRastreo() == null || proof.getClaveRastreo().isBlank()) missing.add("claveRastreo");
        if (proof.getAmount() == null) missing.add("amount");
        if (proof.getTransferDate() == null) missing.add("transferDate");
        if (proof.getBankEmitter() == null || proof.getBankEmitter().isBlank()) missing.add("bankEmitter");
        if (proof.getAccountReceiver() == null || proof.getAccountReceiver().isBlank()) missing.add("accountReceiver");
        return missing;
    }

    private ScrapeInput buildScrapeInput(TransferProofSubmission proof) {
        return new ScrapeInput(
                proof.getClaveRastreo(),
                proof.getTransferDate() != null ? proof.getTransferDate().format(ISO_DATE) : null,
                proof.getBankEmitter(),
                proof.getAccountReceiver(),
                proof.getAmount() != null ? proof.getAmount().toPlainString() : null
        );
    }

    private CepValidationAttempt beginAttempt(TransferProofSubmission proof) {
        CepValidationAttempt attempt = new CepValidationAttempt();
        attempt.setTransferProofId(proof.getId());
        return attempt;
    }

    private String buildRequestPayloadJson(TransferProofSubmission proof) {
        return String.format(
                "{\"claveRastreo\":\"%s\",\"monto\":%s,\"fecha\":\"%s\",\"bancoEmisor\":\"%s\",\"cuentaReceptora\":\"%s\"}",
                escape(proof.getClaveRastreo()),
                proof.getAmount() != null ? proof.getAmount().toPlainString() : "0",
                proof.getTransferDate() != null ? proof.getTransferDate().toString() : "",
                escape(proof.getBankEmitter()),
                escape(proof.getAccountReceiver()));
    }

    private String buildFieldsJson(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"")
              .append(escape(e.getValue())).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private BigDecimal parseAmount(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            String clean = s.replaceAll("[,$\\s]", "");
            return new BigDecimal(clean);
        } catch (Exception ex) { return null; }
    }

    /**
     * Normaliza CLABE: solo dígitos. Banxico CEP suele devolverla con espacios
     * o guiones, el input del dueño podría tener lo mismo. 18 dígitos es el
     * estándar pero no lo enforzamos aquí (el valor viene de DB ya validado).
     */
    private String normalizeClabe(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    /**
     * Enmascara una CLABE para logs y mensajes al usuario: muestra los
     * primeros 4 y los últimos 3 dígitos, oculta el medio. "012180015012345678"
     * → "0121***678".
     */
    private String maskClabe(String s) {
        String norm = normalizeClabe(s);
        if (norm == null || norm.length() < 7) return "***";
        return norm.substring(0, 4) + "***" + norm.substring(norm.length() - 3);
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String safe(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }

    /**
     * Result of a CEP validation attempt.
     */
    public static class CepValidationResult {
        private final boolean valid;
        private final List<String> missingFields;
        private final String message;
        private final Map<String, String> cepFields;
        private final String cepXml;
        private final byte[] cepPdfBytes;

        public CepValidationResult(boolean valid, List<String> missingFields, String message) {
            this(valid, missingFields, message, Map.of(), null, null);
        }

        public CepValidationResult(boolean valid, List<String> missingFields, String message,
                                   Map<String, String> cepFields, String cepXml, byte[] cepPdfBytes) {
            this.valid = valid;
            this.missingFields = missingFields;
            this.message = message;
            this.cepFields = cepFields != null ? Map.copyOf(cepFields) : Map.of();
            this.cepXml = cepXml;
            this.cepPdfBytes = cepPdfBytes;
        }

        public boolean isValid() { return valid; }
        public List<String> getMissingFields() { return missingFields; }
        public String getMessage() { return message; }
        public Map<String, String> getCepFields() { return cepFields; }
        public String getCepXml() { return cepXml; }
        public byte[] getCepPdfBytes() { return cepPdfBytes; }
    }
}
