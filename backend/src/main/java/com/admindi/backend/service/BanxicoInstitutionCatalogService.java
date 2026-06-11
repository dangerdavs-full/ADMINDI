package com.admindi.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Catálogo oficial de instituciones Banxico usado por formularios y validaciones.
 *
 * <p>Fuente de verdad: {@code instituciones.do?fecha=dd-MM-yyyy}. El catálogo se
 * cachea en memoria para no golpear Banxico en cada request del frontend.</p>
 */
@Service
public class BanxicoInstitutionCatalogService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final ZoneId MX_ZONE = ZoneId.of("America/Mexico_City");

    private final Object refreshLock = new Object();

    @Value("${banxico.cep.base-url:https://www.banxico.org.mx/cep/}")
    private String baseUrl;

    @Value("${banxico.cep.request-timeout-ms:15000}")
    private int timeoutMs;

    @Value("${banxico.cep.user-agent:ADMINDI/1.0 (contact@admindi.mx; CEP validator)}")
    private String userAgent;

    @Value("${banxico.cep.catalog-cache-minutes:360}")
    private long cacheMinutes;

    private volatile CatalogSnapshot cachedSnapshot;

    public CatalogSnapshot getCatalog() {
        LocalDate today = LocalDate.now(MX_ZONE);
        CatalogSnapshot snapshot = cachedSnapshot;
        if (isFresh(snapshot, today)) {
            return snapshot;
        }

        synchronized (refreshLock) {
            snapshot = cachedSnapshot;
            if (isFresh(snapshot, today)) {
                return snapshot;
            }

            try {
                CatalogSnapshot fetched = fetchCatalog(today);
                cachedSnapshot = fetched;
                return fetched;
            } catch (Exception ex) {
                if (snapshot != null) {
                    return snapshot;
                }
                throw new IllegalStateException("No pude cargar el catálogo Banxico.", ex);
            }
        }
    }

    public Optional<ResolvedInstitution> resolveEmitter(String rawValue) {
        return resolveByValue(rawValue, getCatalog().emitters());
    }

    public Optional<ResolvedInstitution> resolveReceiver(String clabe, String rawValue) {
        String digits = digitsOnly(clabe);
        if (digits.length() >= 3) {
            Optional<ResolvedInstitution> byClabe = resolveByCode(digits.substring(0, 3), getCatalog().receivers());
            if (byClabe.isPresent()) {
                return byClabe;
            }
        }
        return resolveByValue(rawValue, getCatalog().receivers());
    }

    public Optional<ResolvedInstitution> resolveReceiverByClabe(String clabe) {
        String digits = digitsOnly(clabe);
        if (digits.length() < 3) {
            return Optional.empty();
        }
        return resolveByCode(digits.substring(0, 3), getCatalog().receivers());
    }

    private boolean isFresh(CatalogSnapshot snapshot, LocalDate today) {
        return snapshot != null
                && today.equals(snapshot.effectiveDate())
                && snapshot.fetchedAt().plusMinutes(cacheMinutes).isAfter(LocalDateTime.now(MX_ZONE));
    }

    private CatalogSnapshot fetchCatalog(LocalDate date) throws Exception {
        URI uri = resolveCepUri("instituciones.do?fecha=" + urlEncode(date.format(DATE_FMT)));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json,text/plain,*/*")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("banxico_catalog_http_" + response.statusCode());
        }

        Map<String, Object> raw = MAPPER.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        List<InstitutionOption> receivers = parseInstitutionEntries(raw.get("instituciones"));
        List<InstitutionOption> emitters = parseInstitutionEntries(raw.get("institucionesMISPEI"));
        if (emitters.isEmpty()) {
            emitters = receivers;
        }

        return new CatalogSnapshot(
                date,
                LocalDateTime.now(MX_ZONE),
                sortAndDeduplicate(emitters),
                sortAndDeduplicate(receivers)
        );
    }

    private List<InstitutionOption> parseInstitutionEntries(Object raw) {
        List<InstitutionOption> out = new ArrayList<>();
        if (!(raw instanceof List<?> rows)) {
            return out;
        }
        for (Object row : rows) {
            if (!(row instanceof List<?> cells) || cells.size() < 2) {
                continue;
            }
            String code = cells.get(0) == null ? null : cells.get(0).toString().trim();
            String name = cells.get(1) == null ? null : cells.get(1).toString().trim();
            if (code == null || code.isBlank() || name == null || name.isBlank()) {
                continue;
            }
            out.add(new InstitutionOption(code, name));
        }
        return out;
    }

    private List<InstitutionOption> sortAndDeduplicate(List<InstitutionOption> items) {
        Map<String, InstitutionOption> byCode = new LinkedHashMap<>();
        for (InstitutionOption item : items) {
            byCode.putIfAbsent(item.code(), item);
        }
        return byCode.values().stream()
                .sorted(Comparator.comparing(InstitutionOption::name))
                .toList();
    }

    private Optional<ResolvedInstitution> resolveByCode(String rawCode, List<InstitutionOption> institutions) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        String digits = digitsOnly(rawCode);
        if (digits.isBlank()) {
            return Optional.empty();
        }

        List<InstitutionOption> exactMatches = institutions.stream()
                .filter(option -> option.code().equals(digits))
                .toList();
        if (exactMatches.size() == 1) {
            InstitutionOption match = exactMatches.get(0);
            return Optional.of(new ResolvedInstitution(match.code(), match.name()));
        }

        List<InstitutionOption> suffixMatches = institutions.stream()
                .filter(option -> option.code().endsWith(digits))
                .toList();
        if (suffixMatches.size() == 1) {
            InstitutionOption match = suffixMatches.get(0);
            return Optional.of(new ResolvedInstitution(match.code(), match.name()));
        }

        return Optional.empty();
    }

    private Optional<ResolvedInstitution> resolveByValue(String rawValue, List<InstitutionOption> institutions) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }

        Optional<ResolvedInstitution> byCode = resolveByCode(rawValue, institutions);
        if (byCode.isPresent()) {
            return byCode;
        }

        String normalizedInput = normalizeInstitutionName(rawValue);
        String canonicalInput = canonicalInstitutionKey(normalizedInput);

        List<InstitutionOption> exactMatches = institutions.stream()
                .filter(option -> option.normalizedName().equals(normalizedInput)
                        || canonicalInstitutionKey(option.normalizedName()).equals(canonicalInput))
                .toList();
        if (exactMatches.size() == 1) {
            InstitutionOption match = exactMatches.get(0);
            return Optional.of(new ResolvedInstitution(match.code(), match.name()));
        }

        List<InstitutionOption> fuzzyMatches = institutions.stream()
                .filter(option -> {
                    String candidate = canonicalInstitutionKey(option.normalizedName());
                    return candidate.contains(canonicalInput) || canonicalInput.contains(candidate);
                })
                .toList();
        if (fuzzyMatches.size() == 1) {
            InstitutionOption match = fuzzyMatches.get(0);
            return Optional.of(new ResolvedInstitution(match.code(), match.name()));
        }

        return Optional.empty();
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

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private String normalizeInstitutionName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private String canonicalInstitutionKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
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
        return normalized.replace(" ", "");
    }

    public record InstitutionOption(String code, String name) {
        public String normalizedName() {
            return Normalizer.normalize(name, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "")
                    .toUpperCase(Locale.ROOT)
                    .replaceAll("[^A-Z0-9]+", " ")
                    .trim()
                    .replaceAll("\\s+", " ");
        }
    }

    public record ResolvedInstitution(String code, String name) {}

    public record CatalogSnapshot(
            LocalDate effectiveDate,
            LocalDateTime fetchedAt,
            List<InstitutionOption> emitters,
            List<InstitutionOption> receivers
    ) {
        public CatalogSnapshot(LocalDate effectiveDate,
                               LocalDateTime fetchedAt,
                               List<InstitutionOption> emitters,
                               List<InstitutionOption> receivers) {
            Objects.requireNonNull(effectiveDate, "effectiveDate");
            Objects.requireNonNull(fetchedAt, "fetchedAt");
            this.effectiveDate = effectiveDate;
            this.fetchedAt = fetchedAt;
            this.emitters = List.copyOf(emitters);
            this.receivers = List.copyOf(receivers);
        }
    }
}
