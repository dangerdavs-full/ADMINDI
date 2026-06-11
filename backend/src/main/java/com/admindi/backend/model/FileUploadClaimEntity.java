package com.admindi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Registro de propiedad (<em>claim</em>) de cada archivo subido a la plataforma.
 *
 * <p>Cada vez que un usuario autenticado sube un archivo a
 * {@code /api/owner/workflow/files/upload} (o al equivalente de agente /
 * proveedor), el controller guarda una fila aquí con el {@code uploaderUserId}
 * y el path devuelto por {@code FileStorageService}. Cuando un servicio
 * posteriormente consume ese path (p.ej. {@code submitSpeiProof},
 * {@code ownerPayAndClose}, evidencia de mantenimiento) verifica contra esta
 * tabla que el uploader sea el esperado.</p>
 *
 * <p>Esta separación entre <em>almacenamiento</em> (FileStorageService) y
 * <em>propiedad</em> (este entity) es intencional: permite cambiar el storage
 * físico (local, S3, etc.) sin tocar la lógica de ownership.</p>
 *
 * <h3>Idempotencia y anti-replay</h3>
 * <p>Una claim consumida ({@code consumedAt != null}) no puede reutilizarse:
 * el mismo comprobante SPEI no debería cerrar dos tickets/comisiones. El
 * servicio que consume debe llamar a {@code markConsumed} inmediatamente
 * después de asociar el archivo a su recurso de destino.</p>
 */
@Entity
@Table(name = "file_upload_claims")
public class FileUploadClaimEntity {

    @Id
    private String id;

    /** Path interno devuelto por {@code FileStorageService.store()} — único. */
    @Column(name = "file_path", nullable = false, unique = true, length = 512)
    private String filePath;

    @Column(name = "uploader_user_id", nullable = false, length = 36)
    private String uploaderUserId;

    /** Carpeta lógica (spei-proof, maintenance-evidence, vacancy-photos, etc.). */
    @Column(length = 80)
    private String category;

    /**
     * Tipo de recurso al que se espera vincular el archivo (opcional).
     * Se setea cuando el controller ya conoce el destino al momento del upload
     * (p.ej. "spei-proof para invoice X"); permite detectar intentos de
     * re-aprovechar un upload en un dominio distinto.
     */
    @Column(name = "expected_resource_type", length = 80)
    private String expectedResourceType;

    @Column(name = "expected_resource_id", length = 80)
    private String expectedResourceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Se setea la primera vez que la claim es aceptada por un servicio. */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "consumed_resource_type", length = 80)
    private String consumedResourceType;

    @Column(name = "consumed_resource_id", length = 80)
    private String consumedResourceId;

    public FileUploadClaimEntity() { }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getUploaderUserId() { return uploaderUserId; }
    public void setUploaderUserId(String uploaderUserId) { this.uploaderUserId = uploaderUserId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getExpectedResourceType() { return expectedResourceType; }
    public void setExpectedResourceType(String expectedResourceType) { this.expectedResourceType = expectedResourceType; }
    public String getExpectedResourceId() { return expectedResourceId; }
    public void setExpectedResourceId(String expectedResourceId) { this.expectedResourceId = expectedResourceId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
    public String getConsumedResourceType() { return consumedResourceType; }
    public void setConsumedResourceType(String consumedResourceType) { this.consumedResourceType = consumedResourceType; }
    public String getConsumedResourceId() { return consumedResourceId; }
    public void setConsumedResourceId(String consumedResourceId) { this.consumedResourceId = consumedResourceId; }
}
