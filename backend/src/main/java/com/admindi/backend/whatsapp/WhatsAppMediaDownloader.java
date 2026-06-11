package com.admindi.backend.whatsapp;

import com.admindi.backend.service.TwilioWhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Descarga de archivos multimedia asociados a un mensaje WhatsApp recibido vía
 * webhook de Twilio.
 *
 * Twilio publica los medios en URLs tipo:
 *   https://api.twilio.com/2010-04-01/Accounts/{AccountSid}/Messages/{MessageSid}/Media/{MediaSid}
 * que requieren Basic Auth con (account-sid, auth-token). Reusamos las
 * credenciales de {@link TwilioWhatsAppService}.
 *
 * Validaciones de seguridad:
 *  - MIME whitelist estricta (solo imágenes y PDFs que esperamos para
 *    comprobantes y fotos de ticket).
 *  - Tamaño máximo configurable ({@code whatsapp.bot.max-media-size-bytes}).
 *  - Host del URL debe ser {@code api.twilio.com} o {@code media.twiliocdn.com}.
 *    Jamás descargamos URLs arbitrarias enviadas por el usuario final.
 *  - La URL proviene de los params del webhook verificado por HMAC-SHA1 en
 *    {@code TwilioWebhookController}, así que solo Twilio pudo ponerla ahí.
 */
@Service
public class WhatsAppMediaDownloader {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppMediaDownloader.class);

    /**
     * Hosts autorizados. Cualquier otro origen se rechaza. Twilio usa distintos
     * dominios según versión y CDN (verificado en su doc oficial).
     */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "api.twilio.com",
            "media.twiliocdn.com",
            "mcs.us1.twilio.com"
    );

    private static final Set<String> ALLOWED_MIMES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/gif",
            "application/pdf"
    );

    private final TwilioWhatsAppService twilioService;
    private final HttpClient httpClient;

    @Value("${whatsapp.bot.max-media-size-bytes:5242880}")
    private long maxSizeBytes;

    public WhatsAppMediaDownloader(TwilioWhatsAppService twilioService) {
        this.twilioService = twilioService;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record Media(byte[] bytes, String mimeType, String originalUrl) {
        public boolean isEmpty() { return bytes == null || bytes.length == 0; }
    }

    /**
     * Descarga un archivo desde la URL de Twilio. Devuelve {@link Optional#empty()}
     * si hay problema de política o de red; el caller decide el fallback.
     */
    public Optional<Media> download(String mediaUrl, String declaredMime) {
        if (mediaUrl == null || mediaUrl.isBlank()) return Optional.empty();

        URI uri;
        try {
            uri = URI.create(mediaUrl);
        } catch (Exception ex) {
            logger.warn("[WA-MEDIA] invalid URL: {}", ex.getMessage());
            return Optional.empty();
        }

        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase())) {
            logger.warn("[WA-MEDIA] rejected host={}", host);
            return Optional.empty();
        }

        // Si el declaredMime viene en el webhook, lo validamos primero.
        if (declaredMime != null && !declaredMime.isBlank()
                && !ALLOWED_MIMES.contains(declaredMime.toLowerCase())) {
            logger.warn("[WA-MEDIA] rejected declared mime={}", declaredMime);
            return Optional.empty();
        }

        String sid = twilioService.getAccountSidInternal();
        String token = twilioService.getAuthTokenInternal();
        if (sid == null || sid.isBlank() || token == null || token.isBlank()) {
            logger.warn("[WA-MEDIA] no twilio credentials available");
            return Optional.empty();
        }

        String basic = Base64.getEncoder()
                .encodeToString((sid + ":" + token).getBytes(StandardCharsets.UTF_8));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Basic " + basic)
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn("[WA-MEDIA] status={} for mediaUrl host={}",
                        response.statusCode(), host);
                return Optional.empty();
            }

            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0) return Optional.empty();
            if (bytes.length > maxSizeBytes) {
                logger.warn("[WA-MEDIA] rejected oversize bytes={}", bytes.length);
                return Optional.empty();
            }

            String mime = response.headers()
                    .firstValue("content-type")
                    .orElse(declaredMime == null ? "application/octet-stream" : declaredMime);
            // Quitar charset si el servidor lo agrega: "image/jpeg; charset=..."
            int semi = mime.indexOf(';');
            if (semi >= 0) mime = mime.substring(0, semi).trim();

            if (!ALLOWED_MIMES.contains(mime.toLowerCase())) {
                logger.warn("[WA-MEDIA] rejected downloaded mime={}", mime);
                return Optional.empty();
            }

            return Optional.of(new Media(bytes, mime.toLowerCase(), mediaUrl));
        } catch (Exception ex) {
            logger.warn("[WA-MEDIA] download failed: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public List<String> allowedMimes() {
        return List.copyOf(ALLOWED_MIMES);
    }
}
