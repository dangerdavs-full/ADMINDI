package com.admindi.backend.ai;

import com.admindi.backend.model.BanxicoScrapeSchemaEntity;
import com.admindi.backend.repository.BanxicoScrapeSchemaRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scraper del Validador Banxico CEP.
 *
 * Flujo:
 *  1. Resuelve las claves Banxico de emisor y receptor usando el catálogo
 *     oficial {@code instituciones.do} para la fecha de operación.
 *  2. Hace POST a {@code valida.do} con el payload que usa el frontend oficial
 *     del CEP.
 *  3. Si Banxico encuentra el comprobante, sigue el enlace oficial de descarga
 *     XML y extrae los campos desde ese XML (más estable que raspar HTML).
 *  4. Si Banxico devuelve una respuesta HTML no encontrada o temporal,
 *     clasifica correctamente el caso.
 *  5. Sólo como último recurso usa los selectores versionados desde
 *     {@link BanxicoScrapeSchemaRepository} para soportar formatos HTML
 *     heredados o cambios transitorios.
 *
 * Rate limit interno: respetamos un mínimo entre requests consecutivos para
 * no saturar Banxico. Es un servicio público no comercial; el rate limit es
 * de cortesía más que por API formal.
 */
@Service
public class BanxicoCepScraper {

    private static final Logger logger = LoggerFactory.getLogger(BanxicoCepScraper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final String XML_DOWNLOAD_FRAGMENT = "descarga.do?formato=XML";
    private static final String PDF_DOWNLOAD_FRAGMENT = "descarga.do?formato=PDF";

    private final BanxicoScrapeSchemaRepository schemaRepo;

    @Value("${banxico.cep.base-url:https://www.banxico.org.mx/cep/}")
    private String baseUrl;

    @Value("${banxico.cep.request-timeout-ms:15000}")
    private int timeoutMs;

    @Value("${banxico.cep.user-agent:ADMINDI/1.0 (contact@admindi.mx; CEP validator)}")
    private String userAgent;

    @Value("${banxico.cep.min-request-interval-ms:1000}")
    private long minRequestIntervalMs;

    private final AtomicLong lastRequestTs = new AtomicLong(0);

    public BanxicoCepScraper(BanxicoScrapeSchemaRepository schemaRepo) {
        this.schemaRepo = schemaRepo;
    }

    public record ScrapeInput(
            String claveRastreo,
            String fechaOperacion,      // Fecha operación (puede ser LocalDate o String dd-MM-yyyy)
            String bancoEmisorClave,    // Clave Banxico del emisor (3-5 dígitos)
            String cuentaBeneficiaria,  // CLABE 18
            String monto
    ) {}

    public record ScrapeResult(
            boolean found,
            boolean schemaFailure,     // true si el parsing falló — candidato a re-inferencia
            boolean serviceUnavailable, // true si Banxico no respondió (timeout, HTTP 4xx/5xx, red caída)
            Map<String, String> fields, // campos extraídos con claves iguales a selectors_json
            String rawHtml,             // HTML original (para registrar en failure si aplica)
            String url,                 // URL consultada
            String message,
            String cepXml,
            byte[] cepPdfBytes
    ) {
        /** Banxico no está disponible (timeout, 4xx/5xx, red). NO consume intento del user. */
        public static ScrapeResult serviceUnavailable(String msg) {
            return new ScrapeResult(false, false, true, Map.of(), "", "", msg, null, null);
        }
        /** Banxico respondió pero no encontró el comprobante. Rechazo legítimo (puede consumir intento). */
        public static ScrapeResult notFound(String msg) {
            return new ScrapeResult(false, false, false, Map.of(), "", "", msg, null, null);
        }
        /** El parser no pudo extraer los campos — candidato a re-inferencia con IA. */
        public static ScrapeResult schemaFailure(String html, String url) {
            return new ScrapeResult(false, true, false, Map.of(), html, url, "schema_mismatch", null, null);
        }
        /** Parsing exitoso. */
        public static ScrapeResult ok(Map<String, String> fields, String html, String url) {
            return new ScrapeResult(true, false, false, fields, html, url, "ok", null, null);
        }
        /** Parsing exitoso con artefactos oficiales del CEP. */
        public static ScrapeResult ok(Map<String, String> fields, String html, String url,
                                      String cepXml, byte[] cepPdfBytes) {
            return new ScrapeResult(true, false, false, fields, html, url, "ok", cepXml, cepPdfBytes);
        }
    }

    /**
     * Ejecuta la consulta al CEP oficial y devuelve los campos extraídos
     * según el schema activo.
     */
    public ScrapeResult validate(ScrapeInput input) {
        // Rate limit interno: respetar minRequestIntervalMs entre requests.
        respectRateLimit();

        URI validateUri = resolveCepUri("valida.do");
        URI catalogUri = resolveCepUri("instituciones.do?fecha=" + urlEncode(normalizeFecha(input.fechaOperacion())));

        InstitutionCatalog catalog;
        try {
            catalog = loadInstitutionCatalog(catalogUri);
        } catch (Exception ex) {
            logger.warn("[BANXICO-SCRAPER] could not load institutions catalog: {}", ex.getClass().getSimpleName());
            return ScrapeResult.serviceUnavailable("institutions_catalog_unavailable");
        }

        String emisor = resolveInstitutionCode(input.bancoEmisorClave(), catalog.emitters());
        String receptor = resolveReceiverCode(input.cuentaBeneficiaria(), catalog.receivers());
        if (emisor == null || receptor == null) {
            logger.info("[BANXICO-SCRAPER] unresolved bank codes (emisor={}, receptor={})",
                    input.bancoEmisorClave(), maskAccount(input.cuentaBeneficiaria()));
            return ScrapeResult.notFound("unresolved_institution_codes");
        }
        if (emisor.equals(receptor)) {
            logger.info("[BANXICO-SCRAPER] emisor/receptor are equal ({}), CEP not applicable", emisor);
            return ScrapeResult.notFound("same_institution_not_supported");
        }

        String body = buildFormBody(input, emisor, receptor);
        String url = validateUri.toString();
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        HttpClient httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(validateUri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn("[BANXICO-SCRAPER] http {} for url={}", response.statusCode(), url);
                // HTTP 4xx/5xx → Banxico NO respondió correctamente. NO es "no encontrado",
                // es "servicio no disponible" → no debería consumir intento del inquilino.
                return ScrapeResult.serviceUnavailable("http_" + response.statusCode());
            }

            String html = response.body();
            Document doc = Jsoup.parse(html);

            Optional<String> xmlHref = extractDownloadHref(doc, XML_DOWNLOAD_FRAGMENT);
            if (xmlHref.isPresent()) {
                String xml = downloadCepXml(httpClient, resolveCepUri(xmlHref.get()));
                Map<String, String> fields = parseCepXml(xml);
                byte[] pdfBytes = extractDownloadHref(doc, PDF_DOWNLOAD_FRAGMENT)
                        .map(href -> downloadCepPdfSilently(httpClient, resolveCepUri(href)))
                        .orElse(null);
                return ScrapeResult.ok(fields, html, url, xml, pdfBytes);
            }

            if (isOfficialNotFoundHtml(doc)) {
                return ScrapeResult.notFound("cep_not_found: " + truncate(doc.text(), 200));
            }

            if (isTemporaryHtmlError(doc)) {
                return ScrapeResult.serviceUnavailable("temporary_html_error");
            }

            Optional<BanxicoScrapeSchemaEntity> schemaOpt = schemaRepo.findFirstByActiveTrue();
            if (schemaOpt.isEmpty()) {
                logger.warn("[BANXICO-SCRAPER] no active schema, cannot parse fallback html");
                return ScrapeResult.serviceUnavailable("no_active_schema");
            }
            Map<String, String> extracted = extractUsingSchema(doc, schemaOpt.get());
            if (extracted.containsKey("notFoundMarker")) {
                return ScrapeResult.notFound("cep_not_found: " + truncate(extracted.get("notFoundMarker"), 200));
            }
            if (hasEnoughCriticalFields(extracted)) {
                return ScrapeResult.ok(extracted, html, url);
            }
            logger.warn("[BANXICO-SCRAPER] schema failure after html fallback parsing");
            return ScrapeResult.schemaFailure(html, url);
        } catch (Exception ex) {
            logger.warn("[BANXICO-SCRAPER] request failed: {}", ex.getClass().getSimpleName());
            // Red caída, timeout, DNS error → servicio no disponible, no rechazo.
            return ScrapeResult.serviceUnavailable("network_error");
        }
    }

    private String buildFormBody(ScrapeInput input, String emisor, String receptor) {
        StringBuilder sb = new StringBuilder();
        // Payload que usa el propio frontend oficial (`js/cep-min.js`):
        // valida.do + tipoConsulta=1 + captcha='c' cuando el captcha está oculto.
        appendParam(sb, "tipoCriterio", "T");  // T=clave rastreo
        appendParam(sb, "criterio", input.claveRastreo());
        appendParam(sb, "fecha", normalizeFecha(input.fechaOperacion()));
        appendParam(sb, "emisor", emisor);
        appendParam(sb, "receptor", receptor);
        appendParam(sb, "cuenta", input.cuentaBeneficiaria() == null ? "" : input.cuentaBeneficiaria());
        appendParam(sb, "receptorParticipante", "0");
        appendParam(sb, "monto", input.monto() == null ? "" : input.monto());
        appendParam(sb, "captcha", "c");
        appendParam(sb, "tipoConsulta", "1");
        return sb.toString();
    }

    private void appendParam(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append('&');
        sb.append(urlEncode(key)).append('=').append(urlEncode(value == null ? "" : value));
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String normalizeFecha(String raw) {
        if (raw == null || raw.isBlank()) return "";
        // Acepta "yyyy-MM-dd" y lo convierte al "dd-MM-yyyy" que espera el form
        // oficial de Banxico. Si ya viene en el formato correcto lo respetamos.
        try {
            LocalDate d = LocalDate.parse(raw);
            return d.format(DATE_FMT);
        } catch (Exception ignore) {
            return raw;
        }
    }

    private void respectRateLimit() {
        long now = System.currentTimeMillis();
        long last = lastRequestTs.get();
        long wait = (last + minRequestIntervalMs) - now;
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestTs.set(System.currentTimeMillis());
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private URI resolveCepUri(String relativeOrAbsolute) {
        URI candidate = URI.create(relativeOrAbsolute);
        if (candidate.isAbsolute()) {
            return candidate;
        }
        String root = baseUrl == null || baseUrl.isBlank()
                ? "https://www.banxico.org.mx/cep/"
                : baseUrl.trim();
        if (!root.endsWith("/")) {
            int lastSlash = root.lastIndexOf('/');
            String tail = lastSlash >= 0 ? root.substring(lastSlash + 1) : root;
            root = tail.contains(".") ? root.substring(0, lastSlash + 1) : root + "/";
        }
        return URI.create(root).resolve(relativeOrAbsolute);
    }

    private InstitutionCatalog loadInstitutionCatalog(URI catalogUri) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(catalogUri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json,text/plain,*/*")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("catalog_http_" + response.statusCode());
        }

        Map<String, Object> raw = MAPPER.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        List<InstitutionEntry> institutions = parseInstitutionEntries(raw.get("instituciones"));
        List<InstitutionEntry> emitters = parseInstitutionEntries(raw.get("institucionesMISPEI"));
        if (emitters.isEmpty()) emitters = institutions;
        return new InstitutionCatalog(emitters, institutions);
    }

    private List<InstitutionEntry> parseInstitutionEntries(Object raw) {
        List<InstitutionEntry> out = new ArrayList<>();
        if (!(raw instanceof List<?> rows)) return out;
        for (Object row : rows) {
            if (!(row instanceof List<?> cells) || cells.size() < 2) continue;
            String code = cells.get(0) == null ? null : cells.get(0).toString().trim();
            String name = cells.get(1) == null ? null : cells.get(1).toString().trim();
            if (code == null || code.isBlank() || name == null || name.isBlank()) continue;
            out.add(new InstitutionEntry(code, name, normalizeInstitutionName(name)));
        }
        return out;
    }

    String resolveInstitutionCode(String rawValue, List<InstitutionEntry> institutions) {
        if (rawValue == null || rawValue.isBlank() || institutions == null || institutions.isEmpty()) {
            return null;
        }
        String raw = rawValue.trim();
        if (raw.chars().allMatch(Character::isDigit)) {
            Optional<String> exact = institutions.stream()
                    .map(InstitutionEntry::code)
                    .filter(code -> code.equals(raw))
                    .findFirst();
            if (exact.isPresent()) return exact.get();

            if (raw.length() == 3) {
                List<String> suffixMatches = institutions.stream()
                        .map(InstitutionEntry::code)
                        .filter(code -> code.endsWith(raw))
                        .distinct()
                        .toList();
                if (suffixMatches.size() == 1) return suffixMatches.get(0);
            }
        }

        String normalizedInput = normalizeInstitutionName(raw);
        String canonicalInput = canonicalInstitutionKey(normalizedInput);

        List<InstitutionEntry> exactMatches = institutions.stream()
                .filter(entry -> entry.normalizedName().equals(normalizedInput)
                        || canonicalInstitutionKey(entry.normalizedName()).equals(canonicalInput))
                .toList();
        if (exactMatches.size() == 1) return exactMatches.get(0).code();

        List<InstitutionEntry> fuzzyMatches = institutions.stream()
                .filter(entry -> {
                    String candidate = canonicalInstitutionKey(entry.normalizedName());
                    return candidate.contains(canonicalInput) || canonicalInput.contains(candidate);
                })
                .toList();
        if (fuzzyMatches.size() == 1) return fuzzyMatches.get(0).code();

        return null;
    }

    String resolveReceiverCode(String accountReceiver, List<InstitutionEntry> institutions) {
        if (accountReceiver == null || accountReceiver.isBlank()) return null;
        String digits = accountReceiver.replaceAll("\\D", "");
        if (digits.length() < 3) return null;
        return resolveInstitutionCode(digits.substring(0, 3), institutions);
    }

    String normalizeInstitutionName(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private String canonicalInstitutionKey(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value;
        normalized = normalized.replace("BANCO ", "");
        normalized = normalized.replace(" MEXICO", "");
        normalized = normalized.replace(" DE MEXICO", "");
        normalized = normalized.replace(" WALLET", "");
        normalized = normalized.replace(" BANCO", "");
        normalized = normalized.replace("BANCO MERCANTIL DEL NORTE", "BANORTE");
        normalized = normalized.replace("BANCOMER", "BBVA");
        normalized = normalized.replace("BBVA MEXICO", "BBVA");
        normalized = normalized.replace("CITIBANAMEX", "BANAMEX");
        normalized = normalized.replace("CITI MEXICO", "CITI");
        normalized = normalized.replace("MERCADO PAGO W", "MERCADO PAGO");
        normalized = normalized.replace("MEXPAGO", "MEX PAGO");
        normalized = normalized.replace("MONEXCB", "MONEX");
        normalized = normalized.replace("NU MEXICO", "NU");
        normalized = normalized.replace("STORI", "STORI");
        return normalized.replace(" ", "");
    }

    private Optional<String> extractDownloadHref(Document doc, String fragment) {
        Element link = doc.selectFirst("a[href*=\"" + fragment + "\"]");
        if (link == null) return Optional.empty();
        String href = link.attr("href");
        return href == null || href.isBlank() ? Optional.empty() : Optional.of(href);
    }

    private boolean isOfficialNotFoundHtml(Document doc) {
        String text = normalizeHtmlText(doc.text());
        return text.contains("OPERACION NO ENCONTRADA")
                || text.contains("NO ES POSIBLE GENERAR EL CEP")
                || text.contains("NO HA RECIBIDO UNA ORDEN DE PAGO QUE CUMPLA CON EL CRITERIO");
    }

    private boolean isTemporaryHtmlError(Document doc) {
        String text = normalizeHtmlText(doc.text());
        return text.contains("INTENTE NUEVAMENTE MAS TARDE")
                || text.contains("OCURRIO UN ERROR EN LA CONSULTA")
                || text.contains("EL HORARIO DE CONSULTA ES");
    }

    private String normalizeHtmlText(String text) {
        return normalizeInstitutionName(text == null ? "" : text);
    }

    private Map<String, String> extractUsingSchema(Document doc, BanxicoScrapeSchemaEntity schema) {
        Map<String, String> selectors;
        try {
            Map<String, Object> raw = MAPPER.readValue(schema.getSelectorsJson(),
                    new TypeReference<Map<String, Object>>() {});
            selectors = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                selectors.put(e.getKey(), e.getValue() == null ? null : e.getValue().toString());
            }
        } catch (Exception ex) {
            logger.warn("[BANXICO-SCRAPER] invalid schema json: {}", ex.getMessage());
            return Map.of();
        }

        String notFoundSelector = selectors.get("notFoundMarker");
        if (notFoundSelector != null && !notFoundSelector.isBlank()) {
            try {
                Element notFound = doc.selectFirst(notFoundSelector);
                if (notFound != null && !notFound.text().isBlank()) {
                    return Map.of("notFoundMarker", notFound.text().trim());
                }
            } catch (Exception selEx) {
                logger.debug("[BANXICO-SCRAPER] notFoundMarker selector invalid: {}", selEx.getMessage());
            }
        }

        Map<String, String> extracted = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : selectors.entrySet()) {
            String field = e.getKey();
            String selector = e.getValue();
            if ("notFoundMarker".equals(field) || selector == null || selector.isBlank()) continue;
            try {
                Element el = doc.selectFirst(selector);
                String value = el != null ? el.text().trim() : null;
                if (value != null && !value.isEmpty()) {
                    extracted.put(field, value);
                }
            } catch (Exception selEx) {
                logger.debug("[BANXICO-SCRAPER] selector invalid for {}: {}", field, selEx.getMessage());
            }
        }
        return extracted;
    }

    private boolean hasEnoughCriticalFields(Map<String, String> extracted) {
        String[] criticalFields = {"claveRastreo", "monto", "fechaOperacion",
                "bancoEmisor", "cuentaBeneficiario"};
        int found = 0;
        for (String field : criticalFields) {
            if (extracted.containsKey(field)) found++;
        }
        return found >= 3;
    }

    private String downloadCepXml(HttpClient httpClient, URI xmlUri) throws Exception {
        HttpRequest xmlRequest = HttpRequest.newBuilder()
                .uri(xmlUri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", userAgent)
                .header("Accept", "application/xml,text/xml;q=0.9,*/*;q=0.8")
                .GET()
                .build();
        HttpResponse<String> xmlResponse = httpClient.send(xmlRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (xmlResponse.statusCode() < 200 || xmlResponse.statusCode() >= 300) {
            throw new IllegalStateException("xml_http_" + xmlResponse.statusCode());
        }
        return xmlResponse.body();
    }

    private byte[] downloadCepPdfSilently(HttpClient httpClient, URI pdfUri) {
        try {
            HttpRequest pdfRequest = HttpRequest.newBuilder()
                    .uri(pdfUri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/pdf,*/*;q=0.8")
                    .GET()
                    .build();
            HttpResponse<byte[]> pdfResponse = httpClient.send(pdfRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (pdfResponse.statusCode() < 200 || pdfResponse.statusCode() >= 300) {
                return null;
            }
            return pdfResponse.body();
        } catch (Exception ignore) {
            return null;
        }
    }

    Map<String, String> parseCepXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);

        org.w3c.dom.Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        org.w3c.dom.Element root = document.getDocumentElement();
        org.w3c.dom.Element beneficiario = firstElement(root, "Beneficiario");
        org.w3c.dom.Element ordenante = firstElement(root, "Ordenante");

        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, "claveRastreo", root.getAttribute("claveRastreo"));
        putIfPresent(fields, "fechaOperacion", root.getAttribute("FechaOperacion"));
        putIfPresent(fields, "monto", beneficiario != null ? beneficiario.getAttribute("MontoPago") : null);
        putIfPresent(fields, "bancoEmisor", ordenante != null ? ordenante.getAttribute("BancoEmisor") : null);
        putIfPresent(fields, "bancoReceptor", beneficiario != null ? beneficiario.getAttribute("BancoReceptor") : null);
        putIfPresent(fields, "cuentaBeneficiario", beneficiario != null ? beneficiario.getAttribute("Cuenta") : null);
        putIfPresent(fields, "beneficiario", beneficiario != null ? beneficiario.getAttribute("Nombre") : null);
        putIfPresent(fields, "rfcBeneficiario", beneficiario != null ? beneficiario.getAttribute("RFC") : null);
        putIfPresent(fields, "cuentaOrdenante", ordenante != null ? ordenante.getAttribute("Cuenta") : null);
        putIfPresent(fields, "ordenante", ordenante != null ? ordenante.getAttribute("Nombre") : null);
        return fields;
    }

    private org.w3c.dom.Element firstElement(org.w3c.dom.Element root, String tagName) {
        org.w3c.dom.NodeList nodes = root.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        return (org.w3c.dom.Element) nodes.item(0);
    }

    private void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value.trim());
        }
    }

    private String maskAccount(String account) {
        if (account == null) return "";
        String digits = account.replaceAll("\\D", "");
        if (digits.length() <= 6) return "***";
        return digits.substring(0, 3) + "***" + digits.substring(digits.length() - 3);
    }

    record InstitutionEntry(String code, String name, String normalizedName) {}

    record InstitutionCatalog(List<InstitutionEntry> emitters, List<InstitutionEntry> receivers) {}
}
