package com.admindi.backend.controller;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.repository.AuditEventRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Real audit endpoint for SUPER_ADMIN only.
 * Supports filtering by eventType, actorId, actorRole, ownerId, resourceType, date range.
 * Paginated.
 */
@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AuditController {

    private final AuditEventRepository auditRepo;

    public AuditController(AuditEventRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    @GetMapping
    public ResponseEntity<Page<AuditEventEntity>> listAuditEvents(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String actorRole,
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Specification<AuditEventEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (eventType != null && !eventType.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("eventType")),
                        "%" + eventType.toLowerCase() + "%"));
            }
            if (actorId != null && !actorId.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("actorId")),
                        "%" + actorId.toLowerCase() + "%"));
            }
            if (actorRole != null && !actorRole.isBlank()) {
                predicates.add(cb.equal(root.get("actorRole"), actorRole));
            }
            if (ownerId != null && !ownerId.isBlank()) {
                predicates.add(cb.equal(root.get("ownerId"), ownerId));
            }
            if (resourceType != null && !resourceType.isBlank()) {
                predicates.add(cb.equal(root.get("resourceType"), resourceType));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AuditEventEntity> results = auditRepo.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp")));

        return ResponseEntity.ok(results);
    }
}
