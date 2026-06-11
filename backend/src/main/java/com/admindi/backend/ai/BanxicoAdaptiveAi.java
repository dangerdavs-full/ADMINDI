package com.admindi.backend.ai;

import com.admindi.backend.model.BanxicoScrapeFailureEntity;
import com.admindi.backend.model.BanxicoScrapeSchemaEntity;
import com.admindi.backend.repository.BanxicoScrapeFailureRepository;
import com.admindi.backend.repository.BanxicoScrapeSchemaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Capa adaptativa para el scraper de Banxico CEP.
 *
 * Cuando el scraper con el schema activo falla al extraer los campos clave,
 * este servicio pide a Claude re-inferir los selectores/regex a partir del
 * HTML actual. Si los nuevos selectores pasan una validación mínima, se
 * inserta una nueva versión activa en {@code banxico_scrape_schema} y se
 * registra la resolución en {@code banxico_scrape_failure}.
 *
 * Invariantes:
 *  - Solo un schema puede estar activo (DB enforceable con índice único
 *    parcial en V55).
 *  - Antes de activar el schema nuevo, validamos que Claude devolvió selectores
 *    para TODOS los campos requeridos (claveRastreo, monto, fechaOperacion,
 *    bancoEmisor, bancoReceptor, cuentaBeneficiario).
 *  - Cada adaptación se audita y se puede consultar desde el panel superadmin.
 */
@Service
public class BanxicoAdaptiveAi {

    private static final Logger logger = LoggerFactory.getLogger(BanxicoAdaptiveAi.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Campos que DEBEN venir en los selectors para considerar el schema válido. */
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "claveRastreo", "monto", "fechaOperacion",
            "bancoEmisor", "bancoReceptor", "cuentaBeneficiario"
    );

    private static final String SYSTEM_PROMPT = """
            Eres un experto en web scraping con Jsoup. Recibes un fragmento HTML \
            del sitio de Banxico CEP (https://www.banxico.org.mx/cep/). Tu tarea \
            es generar un diccionario JSON con selectores Jsoup para extraer los \
            campos del comprobante oficial SPEI.
            
            Reglas ESTRICTAS:
            1) Devuelve SOLO JSON válido, sin markdown ni explicaciones.
            2) Cada selector debe ser válido en Jsoup (subset de CSS3). Puedes usar:
               - Selectores de tag/class/id: div.clase, #monto, table > tr.
               - Selectores relacionales: tr:contains(Clave) td:last-child.
               - NO uses XPath, jQuery-only, ni selectores con :has() o regex complejos.
            3) Los campos que no encuentres déjalos con null.
            4) Si el HTML es una página de error de Banxico (CEP no encontrado, fuera \
               de horario, etc.), pon notFoundMarker con el selector que identifica \
               esa condición y deja los demás campos en null.
            
            Schema de salida:
            {
              "claveRastreo": "selector Jsoup"|null,
              "monto": "selector"|null,
              "fechaOperacion": "selector"|null,
              "fechaAbono": "selector"|null,
              "bancoEmisor": "selector"|null,
              "bancoReceptor": "selector"|null,
              "cuentaBeneficiario": "selector"|null,
              "beneficiario": "selector"|null,
              "sellDigital": "selector"|null,
              "notFoundMarker": "selector"|null,
              "reasoning": "breve explicación en español de tu elección (max 200 chars)"
            }
            """;

    private final ClaudeService claude;
    private final BanxicoScrapeSchemaRepository schemaRepo;
    private final BanxicoScrapeFailureRepository failureRepo;

    public BanxicoAdaptiveAi(ClaudeService claude,
                              BanxicoScrapeSchemaRepository schemaRepo,
                              BanxicoScrapeFailureRepository failureRepo) {
        this.claude = claude;
        this.schemaRepo = schemaRepo;
        this.failureRepo = failureRepo;
    }

    /**
     * Intenta re-inferir el schema a partir del HTML actual. Si lo logra:
     *  1. Desactiva el schema anterior.
     *  2. Inserta uno nuevo activo con origin=AI_AUTO.
     *  3. Marca el failure como resolved.
     *
     * @return el nuevo schema activo, o {@link Optional#empty()} si no pudo resolver.
     */
    @Transactional
    public Optional<BanxicoScrapeSchemaEntity> reinferSchema(String rawHtml, String sourceUrl) {
        if (rawHtml == null || rawHtml.isBlank()) return Optional.empty();

        BanxicoScrapeFailureEntity failure = registerFailure(rawHtml, sourceUrl);

        ClaudeService.ClaudeResponse resp = claude.chat(
                SYSTEM_PROMPT,
                "HTML RECIBIDO (sin headers HTTP, sin cookies):\n\n"
                        + snippet(rawHtml, 8000),
                true,
                null,   // system-level call: no user-scoped budget
                null,
                "BANXICO_ADAPTIVE");

        if (!resp.ok()) {
            failure.setAiError(resp.errorMessage() == null ? "claude_error" : resp.errorMessage());
            failureRepo.save(failure);
            return Optional.empty();
        }

        Map<String, Object> selectors = new LinkedHashMap<>(resp.structured());
        // Validación mínima: todos los REQUIRED_FIELDS deben venir con selector no nulo.
        for (String req : REQUIRED_FIELDS) {
            Object v = selectors.get(req);
            if (v == null || v.toString().isBlank()) {
                failure.setAiError("missing_required_field=" + req);
                failureRepo.save(failure);
                return Optional.empty();
            }
        }

        // Eliminar 'reasoning' antes de persistir — es solo para log.
        Object reasoning = selectors.remove("reasoning");

        Integer nextVersion = schemaRepo.findTopByOrderByVersionDesc()
                .map(s -> s.getVersion() + 1)
                .orElse(2);

        // Desactivar el anterior dentro de la misma transacción.
        schemaRepo.findFirstByActiveTrue().ifPresent(old -> {
            old.setActive(false);
            old.setDeactivatedAt(LocalDateTime.now());
            schemaRepo.save(old);
        });

        BanxicoScrapeSchemaEntity schema = new BanxicoScrapeSchemaEntity();
        schema.setId(UUID.randomUUID().toString());
        schema.setVersion(nextVersion);
        try {
            schema.setSelectorsJson(MAPPER.writeValueAsString(selectors));
        } catch (Exception ex) {
            failure.setAiError("json_serialize: " + ex.getMessage());
            failureRepo.save(failure);
            return Optional.empty();
        }
        schema.setActive(true);
        schema.setSource("AI_AUTO");
        BanxicoScrapeSchemaEntity saved = schemaRepo.save(schema);

        failure.setResolvedByAiAt(LocalDateTime.now());
        failure.setNewSchemaId(saved.getId());
        failureRepo.save(failure);

        logger.warn("[BANXICO-ADAPTIVE] new schema version={} active (reasoning={})",
                nextVersion, reasoning);
        return Optional.of(saved);
    }

    /**
     * Expuesto para llamadas externas que solo quieren registrar un fallo
     * sin intentar adaptar (ej. modo adaptative-ai=false).
     */
    @Transactional
    public BanxicoScrapeFailureEntity registerFailure(String rawHtml, String sourceUrl) {
        BanxicoScrapeFailureEntity failure = new BanxicoScrapeFailureEntity();
        failure.setId(UUID.randomUUID().toString());
        failure.setUrl(sourceUrl == null ? "" : sourceUrl);
        failure.setHtmlSnippetHash(hashSha256(rawHtml));
        failure.setHtmlSnippetPreview(snippet(rawHtml, 4096));
        failure.setDetectedAt(LocalDateTime.now());
        return failureRepo.save(failure);
    }

    private String snippet(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String hashSha256(String s) {
        if (s == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
