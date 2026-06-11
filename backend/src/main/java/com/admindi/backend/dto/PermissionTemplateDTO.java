package com.admindi.backend.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Forma API de {@code permission_templates}.
 *
 * <h2>Por qué existe este DTO</h2>
 * <p>La entidad {@code PermissionTemplateEntity} mapea la columna JSONB
 * {@code permissions} como {@code String} puro — es una simplificación pragmática
 * en la capa de persistencia (evita usar converters de JPA para tipos JSONB de
 * Postgres). Pero exponer la entidad directamente al cliente hace que Jackson
 * serialice ese campo como un <em>string que contiene JSON</em>:</p>
 *
 * <pre>{@code { "permissions": "[\"properties:read\",\"invoices:read\"]" }}</pre>
 *
 * <p>El frontend espera un array real (y eso es lo que una API REST honesta
 * debe entregar), así que este DTO se interpone: al serializar convierte el
 * JSON interno en {@code List<String>}, al deserializar vuelve a producir el
 * JSON válido que se persiste en la columna.</p>
 *
 * <p>Nota sobre {@code isSystem}: se expone como objeto {@code Boolean} con
 * getter {@code getIsSystem()} (mismo patrón que la entidad) para que Jackson
 * emita la clave JSON {@code "isSystem"} y conserve el contrato ya consumido
 * por el cliente. Si se usara {@code boolean} primitivo con getter
 * {@code isSystem()}, Jackson recortaría el prefijo y la clave quedaría como
 * {@code "system"}, rompiendo el frontend.</p>
 */
public class PermissionTemplateDTO {

    private String id;
    private String name;
    private String description;
    private Boolean isSystem;
    private List<String> permissions = new ArrayList<>();

    public PermissionTemplateDTO() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getIsSystem() { return isSystem; }
    public void setIsSystem(Boolean isSystem) { this.isSystem = isSystem; }

    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) {
        this.permissions = permissions != null ? permissions : new ArrayList<>();
    }
}
