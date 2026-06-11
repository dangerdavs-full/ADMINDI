package com.admindi.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>NOTA DE SEGURIDAD — por qué ya no se exponen /uploads/**</b></p>
 *
 * <p>Versiones anteriores registraban aquí un {@code ResourceHandler} que servía
 * el directorio {@code uploads/} como recursos estáticos en la URL
 * {@code /uploads/**}. Eso introducía dos vulnerabilidades:</p>
 * <ol>
 *   <li><b>IDOR</b>: cualquier usuario autenticado podía descargar PDFs de otros
 *       dueños con solo conocer/adivinar la ruta (contrato de lease, comprobante
 *       SPEI, evidencia de convenio) — el único control era tener un JWT válido,
 *       no pertenecer a ese dueño.</li>
 *   <li><b>URL estable no-revocable</b>: una URL filtrada (en Slack, email o
 *       captura de pantalla) quedaba accesible indefinidamente sin expiración.</li>
 * </ol>
 *
 * <p>Hoy los archivos se sirven exclusivamente vía el controller tipado
 * {@code /api/secure-files/...} ({@link com.admindi.backend.controller.SecureFileController}),
 * que valida autorización por recurso y fuerza cabeceras {@code Cache-Control: private, no-store}.
 * El frontend descarga los blobs con axios (header Authorization siempre presente) y
 * los abre mediante {@code URL.createObjectURL}, obteniendo URLs efímeras locales al
 * tab — no compartibles.</p>
 *
 * <p>Si alguna vez se re-requiere un endpoint público de recursos (p. ej. assets de
 * marketing servidos al público), hay que crear un subdirectorio distinto y mapear
 * solo ese, nunca {@code uploads/} que contiene documentación sensible de dueños.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
    }

    // addResourceHandlers intencionalmente NO implementado. No se exponen recursos
    // estáticos desde disco. Ver javadoc de la clase para el racional completo.
}
