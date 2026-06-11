package com.admindi.backend.controller;

import com.admindi.backend.ai.BanxicoAdaptiveAi;
import com.admindi.backend.model.BanxicoScrapeFailureEntity;
import com.admindi.backend.model.BanxicoScrapeSchemaEntity;
import com.admindi.backend.repository.BanxicoScrapeFailureRepository;
import com.admindi.backend.repository.BanxicoScrapeSchemaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Endpoints SUPER_ADMIN para inspeccionar y operar el estado del scraper
 * Banxico CEP adaptativo.
 *
 * Funcionalidades:
 *  - Listar versiones de schema y cuál está activa.
 *  - Listar failures (resueltos y pendientes).
 *  - Forzar re-inferencia con un HTML subido manualmente (útil si el
 *    superadmin detecta un cambio en Banxico antes que el sistema).
 *  - Revertir a un schema anterior si la adaptación automática introduce
 *    regresiones.
 */
@RestController
@RequestMapping("/api/admin/banxico")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BanxicoMonitorController {

    private final BanxicoScrapeSchemaRepository schemaRepo;
    private final BanxicoScrapeFailureRepository failureRepo;
    private final BanxicoAdaptiveAi adaptiveAi;

    public BanxicoMonitorController(BanxicoScrapeSchemaRepository schemaRepo,
                                     BanxicoScrapeFailureRepository failureRepo,
                                     BanxicoAdaptiveAi adaptiveAi) {
        this.schemaRepo = schemaRepo;
        this.failureRepo = failureRepo;
        this.adaptiveAi = adaptiveAi;
    }

    @GetMapping("/schemas")
    public ResponseEntity<List<Map<String, Object>>> listSchemas() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BanxicoScrapeSchemaEntity s : schemaRepo.findAllByOrderByVersionDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("version", s.getVersion());
            row.put("active", s.isActive());
            row.put("source", s.getSource());
            row.put("createdAt", s.getCreatedAt());
            row.put("lastSuccessAt", s.getLastSuccessAt());
            row.put("deactivatedAt", s.getDeactivatedAt());
            row.put("selectorsJson", s.getSelectorsJson());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/failures")
    public ResponseEntity<Map<String, Object>> listFailures() {
        List<BanxicoScrapeFailureEntity> all = failureRepo.findTop50ByOrderByDetectedAtDesc();
        long unresolved = failureRepo.countByResolvedByAiAtIsNull();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("unresolvedCount", unresolved);
        response.put("items", all);
        return ResponseEntity.ok(response);
    }

    /**
     * Fuerza una re-inferencia con un HTML subido manualmente. Útil cuando el
     * SUPER_ADMIN ya identificó un cambio y quiere adelantarse al auto-detect.
     *
     * Body: { "html": "...", "url": "opcional" }
     */
    @PostMapping("/reinfer")
    public ResponseEntity<Map<String, Object>> forceReinfer(@RequestBody Map<String, String> body) {
        String html = body.get("html");
        String url = body.getOrDefault("url", "manual://super-admin");
        if (html == null || html.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "html_required"));
        }
        Optional<BanxicoScrapeSchemaEntity> newSchema = adaptiveAi.reinferSchema(html, url);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", newSchema.isPresent());
        if (newSchema.isPresent()) {
            response.put("schemaId", newSchema.get().getId());
            response.put("version", newSchema.get().getVersion());
        } else {
            response.put("error", "ai_could_not_regenerate_schema");
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Activa manualmente un schema histórico (rollback). Útil si una
     * adaptación automática introdujo regresiones y el SUPER_ADMIN prefiere
     * volver a una versión anterior mientras investiga.
     */
    @PostMapping("/schemas/{id}/activate")
    public ResponseEntity<Map<String, Object>> activateSchema(@PathVariable String id) {
        BanxicoScrapeSchemaEntity target = schemaRepo.findById(id).orElse(null);
        if (target == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "schema_not_found"));
        }
        schemaRepo.findFirstByActiveTrue().ifPresent(old -> {
            if (!old.getId().equals(id)) {
                old.setActive(false);
                old.setDeactivatedAt(LocalDateTime.now());
                schemaRepo.save(old);
            }
        });
        target.setActive(true);
        target.setDeactivatedAt(null);
        target.setSource("MANUAL");
        schemaRepo.save(target);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "activeSchemaId", target.getId(),
                "version", target.getVersion()));
    }
}
