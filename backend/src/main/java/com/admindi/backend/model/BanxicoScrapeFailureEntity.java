package com.admindi.backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Cada vez que el parser actual no logra extraer los campos de la respuesta de
 * Banxico CEP, se crea un row aquí. Si la IA regenera un schema válido, se
 * actualiza {@link #resolvedByAiAt} y {@link #newSchemaId} apuntando al nuevo
 * schema. Si falla, queda {@link #aiError} para revisión manual del SUPER_ADMIN.
 */
@Entity
@Table(name = "banxico_scrape_failure")
public class BanxicoScrapeFailureEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String url;

    @Column(name = "html_snippet_hash", nullable = false)
    private String htmlSnippetHash;

    /**
     * Primeros ~4KB del HTML ofensivo para diagnóstico manual en el panel.
     * NO guardamos todo el HTML (puede traer PII del beneficiario o tokens de
     * sesión). Solo la parte estructural para que Claude pueda re-inferir.
     */
    @Column(name = "html_snippet_preview", length = 8192)
    private String htmlSnippetPreview;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt = LocalDateTime.now();

    @Column(name = "resolved_by_ai_at")
    private LocalDateTime resolvedByAiAt;

    @Column(name = "new_schema_id")
    private String newSchemaId;

    @Column(name = "ai_error", length = 2048)
    private String aiError;

    @Column(name = "notified_super_admin", nullable = false)
    private boolean notifiedSuperAdmin = false;

    public BanxicoScrapeFailureEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getHtmlSnippetHash() { return htmlSnippetHash; }
    public void setHtmlSnippetHash(String htmlSnippetHash) { this.htmlSnippetHash = htmlSnippetHash; }
    public String getHtmlSnippetPreview() { return htmlSnippetPreview; }
    public void setHtmlSnippetPreview(String htmlSnippetPreview) { this.htmlSnippetPreview = htmlSnippetPreview; }
    public LocalDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(LocalDateTime detectedAt) { this.detectedAt = detectedAt; }
    public LocalDateTime getResolvedByAiAt() { return resolvedByAiAt; }
    public void setResolvedByAiAt(LocalDateTime resolvedByAiAt) { this.resolvedByAiAt = resolvedByAiAt; }
    public String getNewSchemaId() { return newSchemaId; }
    public void setNewSchemaId(String newSchemaId) { this.newSchemaId = newSchemaId; }
    public String getAiError() { return aiError; }
    public void setAiError(String aiError) { this.aiError = aiError; }
    public boolean isNotifiedSuperAdmin() { return notifiedSuperAdmin; }
    public void setNotifiedSuperAdmin(boolean notifiedSuperAdmin) { this.notifiedSuperAdmin = notifiedSuperAdmin; }
}
