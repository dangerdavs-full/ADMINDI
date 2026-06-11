package com.admindi.backend.service;

import com.admindi.backend.model.FileUploadClaimEntity;
import com.admindi.backend.repository.FileUploadClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Capa de <em>propiedad</em> (claim) para archivos subidos vía
 * {@link FileStorageService}. Su responsabilidad es mantener una asociación
 * fuerte entre <em>quién subió un archivo</em> y <em>qué recurso puede
 * consumirlo</em>, cerrando el hueco de IDOR que existía cuando los
 * controllers aceptaban paths opacos por body.
 *
 * <p>No toca bytes: esto sigue siendo responsabilidad de
 * {@link FileStorageService}. Aquí solo se guarda metadata en DB.</p>
 *
 * <h3>Uso esperado</h3>
 * <pre>
 *   // En el controller de upload:
 *   String path = fileStorage.store(multipart, category);
 *   fileOwnership.registerClaim(path, uploaderUserId, category);
 *
 *   // En el servicio que consume el path:
 *   fileOwnership.assertUploader(path, expectedUploaderUserId);
 *   fileOwnership.markConsumed(path, "MaintenanceTicket", ticketId);
 * </pre>
 */
@Service
public class FileOwnershipService {

    private static final Logger logger = LoggerFactory.getLogger(FileOwnershipService.class);

    private final FileUploadClaimRepository repository;

    public FileOwnershipService(FileUploadClaimRepository repository) {
        this.repository = repository;
    }

    /**
     * Registra una claim nueva para el archivo recién subido. Si por alguna
     * razón (replay del mismo path, bug) ya existe una claim con el mismo
     * path, se deja la original (primera escritura gana) para evitar que un
     * atacante pise claims ajenas.
     */
    @Transactional
    public FileUploadClaimEntity registerClaim(String filePath, String uploaderUserId, String category) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath vacío al registrar claim");
        }
        if (uploaderUserId == null || uploaderUserId.isBlank()) {
            throw new IllegalArgumentException("uploaderUserId vacío al registrar claim");
        }
        Optional<FileUploadClaimEntity> existing = repository.findByFilePath(filePath);
        if (existing.isPresent()) {
            FileUploadClaimEntity e = existing.get();
            if (!uploaderUserId.equals(e.getUploaderUserId())) {
                // Esto no debería pasar con UUIDs del storage; si pasa es un bug
                // o un abuso — se loggea WARN y se ignora el nuevo uploader.
                logger.warn("[FileOwnership] claim colisiona para path={} existingUploader={} attemptedUploader={}",
                        filePath, e.getUploaderUserId(), uploaderUserId);
            }
            return e;
        }
        FileUploadClaimEntity claim = new FileUploadClaimEntity();
        claim.setId(UUID.randomUUID().toString());
        claim.setFilePath(filePath);
        claim.setUploaderUserId(uploaderUserId);
        claim.setCategory(category);
        claim.setCreatedAt(LocalDateTime.now());
        return repository.save(claim);
    }

    /**
     * Verifica que {@code filePath} haya sido subido por {@code expectedUploaderUserId}.
     * Lanza 403 si no hay claim o si el uploader no coincide. Pensado para
     * servicios que reciben un path opaco por API y necesitan estar seguros
     * de que el caller es realmente el autor del archivo.
     *
     * <p>Nota: {@code null} o vacío en el path NO se consideran un ataque —
     * el caller decide si eso es aceptable (muchos endpoints permiten no
     * adjuntar comprobante). Solo se valida cuando el path llega con valor.</p>
     */
    @Transactional(readOnly = true)
    public void assertUploader(String filePath, String expectedUploaderUserId) {
        if (filePath == null || filePath.isBlank()) return;
        FileUploadClaimEntity claim = repository.findByFilePath(filePath)
                .orElseThrow(() -> {
                    logger.warn("[FileOwnership] claim faltante path={} expectedUploader={}",
                            filePath, expectedUploaderUserId);
                    return new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "El archivo referenciado no tiene registro de propiedad.");
                });
        if (!claim.getUploaderUserId().equals(expectedUploaderUserId)) {
            logger.warn("[FileOwnership] uploader mismatch path={} expected={} actual={}",
                    filePath, expectedUploaderUserId, claim.getUploaderUserId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El archivo referenciado no pertenece al usuario actual.");
        }
        if (claim.getConsumedAt() != null) {
            // Defensa anti-replay: si ya se consumió para otro recurso es
            // sospechoso. Se loggea WARN pero NO se bloquea siempre: hay flujos
            // donde un mismo comprobante podría re-verse (p.ej. reintentos del
            // mismo recurso). `markConsumed` es idempotente y solo fija el
            // primer consumo; los siguientes se validan por path+uploader.
            logger.debug("[FileOwnership] path={} ya consumido por {}:{}",
                    filePath, claim.getConsumedResourceType(), claim.getConsumedResourceId());
        }
    }

    /**
     * Marca el archivo como consumido por {@code (resourceType, resourceId)}.
     * Si ya estaba marcado con el mismo destino, es no-op (idempotente). Si
     * estaba marcado con otro destino, se deja el original y se loggea WARN.
     */
    @Transactional
    public void markConsumed(String filePath, String resourceType, String resourceId) {
        if (filePath == null || filePath.isBlank()) return;
        FileUploadClaimEntity claim = repository.findByFilePath(filePath).orElse(null);
        if (claim == null) return;
        if (claim.getConsumedAt() != null) {
            boolean same = resourceType != null
                    && resourceType.equals(claim.getConsumedResourceType())
                    && resourceId != null
                    && resourceId.equals(claim.getConsumedResourceId());
            if (!same) {
                logger.warn("[FileOwnership] re-consumo distinto path={} previo={}:{} nuevo={}:{}",
                        filePath, claim.getConsumedResourceType(), claim.getConsumedResourceId(),
                        resourceType, resourceId);
            }
            return;
        }
        claim.setConsumedAt(LocalDateTime.now());
        claim.setConsumedResourceType(resourceType);
        claim.setConsumedResourceId(resourceId);
        repository.save(claim);
    }
}
