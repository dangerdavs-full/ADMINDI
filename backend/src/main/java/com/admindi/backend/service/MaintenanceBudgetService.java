package com.admindi.backend.service;

import com.admindi.backend.model.MaintenanceBudgetEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.MaintenanceBudgetRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Presupuestos de mantenimiento. Autores (únicos que suben):
 *   * MAINTENANCE_PROVIDER (mantenimiento)
 *   * REAL_ESTATE_AGENT (agente inmobiliario)
 *
 * El dueño no sube: revisa, aprueba o rechaza (reauth en el controller).
 * Whitelist de tipos de archivo: defensa en profundidad — evitamos ejecutables,
 * scripts o HTML maliciosos en el adjunto del dueño.
 */
@Service
public class MaintenanceBudgetService {

    private static final Logger logger = LoggerFactory.getLogger(MaintenanceBudgetService.class);

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "application/csv",
            "application/vnd.oasis.opendocument.spreadsheet"
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "xls", "xlsx", "csv", "ods"
    );

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024; // 10 MiB

    private final MaintenanceBudgetRepository repo;
    private final UserRepository userRepository;
    private final StorageService storageService;

    public MaintenanceBudgetService(MaintenanceBudgetRepository repo,
                                    UserRepository userRepository,
                                    StorageService storageService) {
        this.repo = repo;
        this.userRepository = userRepository;
        this.storageService = storageService;
    }

    @Transactional
    public MaintenanceBudgetEntity submit(String propertyId, String title, String description,
                                          BigDecimal amount, String currency, MultipartFile file) {
        UserEntity actor = currentUser();
        String ownerId = TenantContext.resolveOwnerId(userRepository);

        if (file == null || file.isEmpty()) {
            throw new RuntimeException("Archivo requerido (PDF o Excel).");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new RuntimeException("Archivo demasiado grande (máx 10 MB).");
        }
        String rawContentType = file.getContentType();
        String contentType = rawContentType == null ? "" : rawContentType.toLowerCase();
        String rawOriginal = file.getOriginalFilename();
        String original = rawOriginal == null ? "" : rawOriginal;
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_CONTENT_TYPES.contains(contentType) && !ALLOWED_EXTENSIONS.contains(ext)) {
            throw new RuntimeException("Tipo de archivo no permitido. Use PDF o Excel.");
        }
        if (title == null || title.isBlank()) {
            throw new RuntimeException("Título obligatorio.");
        }

        String url = storageService.store(file, "maintenance-budgets");

        if (!isAuthor(actor)) {
            throw new RuntimeException("Solo mantenimiento o agentes inmobiliarios pueden subir presupuestos.");
        }

        MaintenanceBudgetEntity b = new MaintenanceBudgetEntity();
        b.setId(UUID.randomUUID().toString());
        b.setOwnerId(ownerId);
        b.setPropertyId(propertyId);
        b.setProviderUserId(actor.getId());
        b.setTitle(title);
        b.setDescription(description);
        b.setAmount(amount);
        if (currency != null && !currency.isBlank()) b.setCurrency(currency);
        b.setStatus(MaintenanceBudgetEntity.STATUS_SUBMITTED);
        b.setFileUrl(url);
        b.setFileName(original);
        b.setFileContentType(contentType);
        b.setFileSizeBytes(file.getSize());
        b.setSubmittedByUserId(actor.getId());
        b.setSubmittedAt(LocalDateTime.now());
        repo.save(b);

        logger.info("[BUDGET] submitted id={} owner={} by={} file={} ({} bytes)",
                b.getId(), ownerId, actor.getUsername(), original, file.getSize());
        return b;
    }

    @Transactional
    public MaintenanceBudgetEntity decide(String id, boolean approve, String note) {
        UserEntity actor = currentUser();
        MaintenanceBudgetEntity b = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Presupuesto no encontrado."));

        // Authorization: OWNER must be in same context; SUPER_ADMIN puede cross-owner.
        if (actor.getRole() != Role.SUPER_ADMIN) {
            String ctxOwner = TenantContext.resolveOwnerId(userRepository);
            if (!ctxOwner.equals(b.getOwnerId())) {
                throw new RuntimeException("Este presupuesto pertenece a otra organización.");
            }
        }
        if (!MaintenanceBudgetEntity.STATUS_SUBMITTED.equals(b.getStatus())) {
            throw new RuntimeException("El presupuesto ya fue resuelto (" + b.getStatus() + ").");
        }
        b.setStatus(approve ? MaintenanceBudgetEntity.STATUS_APPROVED : MaintenanceBudgetEntity.STATUS_REJECTED);
        b.setDecidedAt(LocalDateTime.now());
        b.setDecidedByUserId(actor.getId());
        b.setDecisionNote(note);
        repo.save(b);
        return b;
    }

    public List<MaintenanceBudgetEntity> listForOwnerContext() {
        UserEntity actor = currentUser();
        if (actor.getRole() == Role.SUPER_ADMIN) {
            return repo.findAll();
        }
        // Los autores (mantenimiento / agente inmobiliario) solo ven sus propios envíos.
        if (isAuthor(actor)) {
            return repo.findByProviderUserIdOrderBySubmittedAtDesc(actor.getId());
        }
        // Dueño / admin / contador ven todo lo del owner de su contexto.
        String ownerId = TenantContext.resolveOwnerId(userRepository);
        return repo.findByOwnerIdOrderBySubmittedAtDesc(ownerId);
    }

    public MaintenanceBudgetEntity get(String id) {
        UserEntity actor = currentUser();
        MaintenanceBudgetEntity b = repo.findById(id).orElseThrow(() -> new RuntimeException("No encontrado."));
        if (actor.getRole() == Role.SUPER_ADMIN) return b;
        String ownerId = TenantContext.resolveOwnerId(userRepository);
        boolean sameOwner = ownerId.equals(b.getOwnerId());
        boolean authorOwnsIt = isAuthor(actor) && actor.getId().equals(b.getProviderUserId());
        if (!sameOwner && !authorOwnsIt) {
            throw new RuntimeException("No autorizado.");
        }
        return b;
    }

    private UserEntity currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByLoginIdentifier(email).orElseThrow();
    }

    /**
     * Autores autorizados para subir presupuestos: mantenimiento y agente inmobiliario.
     * El dueño NO es autor; solo revisa/aprueba/rechaza.
     */
    private boolean isAuthor(UserEntity u) {
        if (u == null || u.getRole() == null) return false;
        return Arrays.asList(Role.MAINTENANCE_PROVIDER, Role.REAL_ESTATE_AGENT).contains(u.getRole());
    }
}
