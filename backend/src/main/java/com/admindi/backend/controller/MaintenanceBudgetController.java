package com.admindi.backend.controller;

import com.admindi.backend.model.MaintenanceBudgetEntity;
import com.admindi.backend.service.MaintenanceBudgetService;
import com.admindi.backend.service.ReauthService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * REST para el módulo de Presupuestos de mantenimiento (Etapa 2).
 *
 * Roles:
 *   * Subir: MAINTENANCE_PROVIDER y REAL_ESTATE_AGENT (únicos autores).
 *   * Aprobar/Rechazar: OWNER — reauth MFA + password. V52: SUPER_ADMIN fuera.
 *   * Listar/Ver/Descargar: OWNER, PROPERTY_ADMIN, ACCOUNTANT, MAINTENANCE_PROVIDER,
 *     REAL_ESTATE_AGENT.
 *
 * n8n: cuando un agente/proveedor sube presupuesto, el service emite OWNER_TASK_PENDING
 * (evento mínimo), y n8n se encarga del workflow de WhatsApp "entra a tu plataforma".
 */
@RestController
@RequestMapping("/api/maintenance/budgets")
public class MaintenanceBudgetController {

    private final MaintenanceBudgetService service;
    private final ReauthService reauthService;

    public MaintenanceBudgetController(MaintenanceBudgetService service, ReauthService reauthService) {
        this.service = service;
        this.reauthService = reauthService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT','MAINTENANCE_PROVIDER','REAL_ESTATE_AGENT')")
    public ResponseEntity<List<MaintenanceBudgetEntity>> list() {
        return ResponseEntity.ok(service.listForOwnerContext());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT','MAINTENANCE_PROVIDER','REAL_ESTATE_AGENT')")
    public ResponseEntity<MaintenanceBudgetEntity> get(@PathVariable String id) {
        return ResponseEntity.ok(service.get(id));
    }

    /**
     * Solo autores pueden cargar presupuesto: MAINTENANCE_PROVIDER (mantenimiento)
     * y REAL_ESTATE_AGENT (agente inmobiliario). El dueño NO sube, solo revisa.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MAINTENANCE_PROVIDER','REAL_ESTATE_AGENT')")
    public ResponseEntity<MaintenanceBudgetEntity> submit(
            @RequestParam(required = false) String propertyId,
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) BigDecimal amount,
            @RequestParam(required = false) String currency,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(service.submit(propertyId, title, description, amount, currency, file));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<MaintenanceBudgetEntity> approve(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        String password = (String) body.get("password");
        String mfaCode = (String) body.get("mfaCode");
        reauthService.verifyReauth(password, mfaCode, "MAINTENANCE_BUDGET_APPROVE");
        String note = (String) body.getOrDefault("note", null);
        return ResponseEntity.ok(service.decide(id, true, note));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<MaintenanceBudgetEntity> reject(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        String password = (String) body.get("password");
        String mfaCode = (String) body.get("mfaCode");
        reauthService.verifyReauth(password, mfaCode, "MAINTENANCE_BUDGET_REJECT");
        String note = (String) body.getOrDefault("note", null);
        return ResponseEntity.ok(service.decide(id, false, note));
    }

    /** Descarga del archivo original; mismo scope de lectura que GET detalle. */
    @GetMapping("/{id}/file")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT','MAINTENANCE_PROVIDER','REAL_ESTATE_AGENT')")
    public ResponseEntity<Object> downloadFile(@PathVariable String id) {
        MaintenanceBudgetEntity b = service.get(id);
        if (b.getFileUrl() == null || b.getFileUrl().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        String path = b.getFileUrl();
        if (path.startsWith("/")) path = path.substring(1);
        File f = Paths.get(path).toFile();
        if (!f.exists()) return ResponseEntity.notFound().build();
        String contentType = b.getFileContentType() != null ? b.getFileContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String name = b.getFileName() != null ? b.getFileName() : ("budget-" + id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new FileSystemResource(f));
    }
}
