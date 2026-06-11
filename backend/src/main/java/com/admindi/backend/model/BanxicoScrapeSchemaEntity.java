package com.admindi.backend.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Versión de selectores para parsear el HTML del Validador Banxico CEP.
 *
 * Cuando el parser actual ({@link BanxicoScrapeSchemaEntity#active}=true) falla
 * al extraer los campos, {@code BanxicoAdaptiveAi} pide a Claude regenerar los
 * selectores leyendo el HTML actual. Si pasan validación contra fixtures
 * conocidos, se inserta una nueva fila con {@code active=true} y la anterior
 * se desactiva. Sólo hay UN schema activo a la vez (índice único parcial en BD).
 */
@Entity
@Table(name = "banxico_scrape_schema")
public class BanxicoScrapeSchemaEntity {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private Integer version;

    /**
     * JSON con selectores por campo. Ej:
     *  {
     *    "claveRastreo": "tr:contains(Clave de rastreo) td:last-child",
     *    "monto": "#monto",
     *    ...
     *  }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selectors_json", columnDefinition = "jsonb", nullable = false)
    private String selectorsJson;

    @Column(nullable = false)
    private boolean active = false;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    /**
     * Origen del schema: SEED (manual bootstrap), AI_AUTO (Claude regeneró tras un
     * fallo), MANUAL (SUPER_ADMIN editó desde el panel).
     */
    @Column(nullable = false)
    private String source = "SEED";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    public BanxicoScrapeSchemaEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getSelectorsJson() { return selectorsJson; }
    public void setSelectorsJson(String selectorsJson) { this.selectorsJson = selectorsJson; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(LocalDateTime lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getDeactivatedAt() { return deactivatedAt; }
    public void setDeactivatedAt(LocalDateTime deactivatedAt) { this.deactivatedAt = deactivatedAt; }
}
