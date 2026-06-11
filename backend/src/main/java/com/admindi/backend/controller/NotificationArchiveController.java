package com.admindi.backend.controller;

import com.admindi.backend.service.NotificationArchiveBrowserService;
import com.admindi.backend.service.NotificationArchiveBrowserService.FileSummary;
import com.admindi.backend.service.NotificationArchiveBrowserService.FolderSummary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoints del panel del archivo de notificaciones (Bloque C8).
 *
 * <p>Todos los endpoints están restringidos a SUPER_ADMIN. El modelo de seguridad es:
 * <ul>
 *   <li>Lecturas (search, list, download): autorizadas por rol únicamente.</li>
 *   <li>Borrados: además exigen reauth (password + MFA) en el body.</li>
 * </ul>
 * La auditoría de cada operación la emite el servicio, no el controller.
 */
@RestController
@RequestMapping("/api/superadmin/notification-archive")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class NotificationArchiveController {

    private final NotificationArchiveBrowserService service;

    public NotificationArchiveController(NotificationArchiveBrowserService service) {
        this.service = service;
    }

    @GetMapping("/search")
    public List<FolderSummary> search(@RequestParam(value = "q", required = false) String q) {
        return service.search(q);
    }

    /**
     * Dispara manualmente el archivador trimestral. Útil cuando el cron nocturno
     * fue saltado o para smokes en entornos no productivos.
     *
     * <p>{@code cutoffDays} opcional: si se pasa, se usa ese umbral en lugar del
     * configurado (que por default es 90). No exige reauth — el archivador mueve
     * datos sin destruirlos (siguen disponibles como CSV en el mismo servidor),
     * y cada corrida queda auditada.
     */
    @PostMapping("/run-now")
    public ResponseEntity<Map<String, Object>> runNow(
            @RequestParam(value = "cutoffDays", required = false) Integer cutoffDays,
            @RequestBody(required = false) Map<String, Object> body) {
        Integer effectiveDays = cutoffDays;
        if (effectiveDays == null && body != null && body.get("cutoffDays") != null) {
            Object v = body.get("cutoffDays");
            if (v instanceof Number n) effectiveDays = n.intValue();
            else if (v instanceof String s && !s.isBlank()) {
                try { effectiveDays = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
            }
        }
        try {
            int archived = service.triggerArchiveNow(effectiveDays);
            return ResponseEntity.ok(Map.of("archived", archived));
        } catch (RuntimeException e) {
            // Devuelve diagnóstico real en lugar de body vacío; útil para operación.
            return ResponseEntity.status(500).body(Map.of(
                    "error", e.getClass().getSimpleName(),
                    "message", String.valueOf(e.getMessage()),
                    "cause", e.getCause() == null ? "" : String.valueOf(e.getCause())
            ));
        }
    }

    @GetMapping("/folders/{folder}/files")
    public List<FileSummary> listFiles(@PathVariable("folder") String folder) {
        return service.listFiles(folder);
    }

    @GetMapping("/folders/{folder}/files/{fileName}")
    public ResponseEntity<byte[]> download(@PathVariable("folder") String folder,
                                           @PathVariable("fileName") String fileName) {
        byte[] bytes = service.readCsv(folder, fileName);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + folder + "_" + fileName + "\"")
                .body(bytes);
    }

    @GetMapping("/folders/{folder}/download-zip")
    public ResponseEntity<byte[]> downloadZip(@PathVariable("folder") String folder) {
        byte[] zip = service.zipFolder(folder);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + folder + ".zip\"")
                .body(zip);
    }

    // Borrados como POST para atravesar proxies/CDNs que descartan body en DELETE.
    @PostMapping("/folders/{folder}/files/{fileName}/delete")
    public ResponseEntity<Void> deleteFile(@PathVariable("folder") String folder,
                                           @PathVariable("fileName") String fileName,
                                           @RequestBody Map<String, String> body) {
        service.deleteFile(folder, fileName, body.get("password"), body.get("mfaCode"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/folders/{folder}/delete")
    public ResponseEntity<Void> deleteFolder(@PathVariable("folder") String folder,
                                             @RequestBody Map<String, String> body) {
        service.deleteFolder(folder, body.get("password"), body.get("mfaCode"));
        return ResponseEntity.noContent().build();
    }
}
