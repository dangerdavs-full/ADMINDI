package com.admindi.backend.util;

import java.text.Normalizer;

/**
 * Generación determinística de slugs para nombres de carpetas y archivos
 * del archivador de notificaciones (Bloque C7).
 *
 * <h2>Requisitos que cumple</h2>
 * <ul>
 *   <li>Sanitización estricta: elimina path-traversal (..), barras, nulls, caracteres
 *       de control — nunca es posible escaparse del directorio de archivo.</li>
 *   <li>Consistencia cross-OS: ASCII bajo, sin caracteres reservados en Windows
 *       ({@code < > : " / \ | ? *}).</li>
 *   <li>Búsqueda por fragmento: el slug mantiene la palabra legible. Buscar
 *       "cuernavaca" o "06140" encuentra el archivo.</li>
 * </ul>
 *
 * <h2>No-objetivo (intencional)</h2>
 * <ul>
 *   <li>No es para URLs ni IDs — es específicamente para nombres de archivo/carpeta.</li>
 *   <li>No garantiza unicidad por sí solo: el caller concatena varios fragmentos.</li>
 * </ul>
 */
public final class SlugUtil {

    private SlugUtil() {}

    public static String slugify(String input) {
        if (input == null) return "unknown";
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return "unknown";

        // Normalize accents: "López" → "Lopez", "Juárez" → "Juarez", "niño" → "nino".
        String normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase();

        // Replace anything that's not [a-z0-9] by single underscore. Collapse repeats.
        String ascii = normalized.replaceAll("[^a-z0-9]+", "_");
        // Strip leading/trailing underscores.
        ascii = ascii.replaceAll("^_+|_+$", "");

        // Safety: paranoid length cap to avoid OS filename limits (255 in most systems,
        // but we concatenate several slugs + extension).
        if (ascii.length() > 60) ascii = ascii.substring(0, 60);
        if (ascii.isEmpty()) return "unknown";
        return ascii;
    }

    /**
     * Combina dos slugs con separador simple. El caller ya pasó cada fragmento por
     * {@link #slugify(String)} o bien le delega aquí mismo mediante una llamada en
     * cadena. No acepta {@code null}.
     */
    public static String join(String a, String b) {
        return a + "_" + b;
    }
}
