package com.admindi.backend.service;

import com.admindi.backend.dto.PermissionTemplateDTO;
import com.admindi.backend.model.PermissionGrantEntity;
import com.admindi.backend.model.PermissionTemplateEntity;
import com.admindi.backend.repository.PermissionGrantRepository;
import com.admindi.backend.repository.PermissionTemplateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PermissionService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final PermissionTemplateRepository templateRepo;
    private final PermissionGrantRepository grantRepo;
    private final ObjectMapper objectMapper;

    @Autowired
    public PermissionService(PermissionTemplateRepository templateRepo,
                             PermissionGrantRepository grantRepo,
                             ObjectMapper objectMapper) {
        this.templateRepo = templateRepo;
        this.grantRepo = grantRepo;
        this.objectMapper = objectMapper;
    }

    // --- Templates (DTO-facing API) ---

    public List<PermissionTemplateDTO> listTemplateDtos() {
        return templateRepo.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public PermissionTemplateDTO getTemplateDto(String id) {
        return toDto(getTemplate(id));
    }

    public PermissionTemplateDTO createTemplate(PermissionTemplateDTO dto) {
        PermissionTemplateEntity entity = new PermissionTemplateEntity();
        String id = dto.getId();
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        entity.setId(id);
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setIsSystem(Boolean.TRUE.equals(dto.getIsSystem()));
        entity.setPermissions(serializePermissions(dto.getPermissions()));
        return toDto(templateRepo.save(entity));
    }

    public PermissionTemplateDTO updateTemplate(String id, PermissionTemplateDTO dto) {
        PermissionTemplateEntity existing = getTemplate(id);
        if (Boolean.TRUE.equals(existing.getIsSystem())) {
            throw new RuntimeException("Cannot modify system templates");
        }
        if (dto.getName() != null) existing.setName(dto.getName());
        if (dto.getDescription() != null) existing.setDescription(dto.getDescription());
        if (dto.getPermissions() != null) {
            existing.setPermissions(serializePermissions(dto.getPermissions()));
        }
        return toDto(templateRepo.save(existing));
    }

    // --- Templates (internal, entity-facing) ---

    public List<PermissionTemplateEntity> listTemplates() {
        return templateRepo.findAll();
    }

    public PermissionTemplateEntity getTemplate(String id) {
        return templateRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Template not found: " + id));
    }

    public void deleteTemplate(String id) {
        PermissionTemplateEntity existing = getTemplate(id);
        if (Boolean.TRUE.equals(existing.getIsSystem())) {
            throw new RuntimeException("Cannot delete system templates");
        }
        templateRepo.deleteById(id);
    }

    // --- Grants ---

    public List<PermissionGrantEntity> getGrantsForOwner(String ownerId) {
        return grantRepo.findByOwnerId(ownerId);
    }

    public List<PermissionGrantEntity> getGrantsForUser(String userId) {
        return grantRepo.findByUserId(userId);
    }

    public PermissionGrantEntity grantPermission(String userId, String ownerId, String templateId, String grantedBy) {
        PermissionGrantEntity grant = new PermissionGrantEntity();
        grant.setId(UUID.randomUUID().toString());
        grant.setUserId(userId);
        grant.setOwnerId(ownerId);
        grant.setTemplateId(templateId);
        grant.setGrantedAt(LocalDateTime.now());
        grant.setGrantedBy(grantedBy);
        return grantRepo.save(grant);
    }

    public PermissionGrantEntity getGrant(String grantId) {
        return grantRepo.findById(grantId)
                .orElseThrow(() -> new RuntimeException("Grant not found: " + grantId));
    }

    public void revokeGrant(String grantId) {
        if (!grantRepo.existsById(grantId)) {
            throw new RuntimeException("Grant not found: " + grantId);
        }
        grantRepo.deleteById(grantId);
    }

    /**
     * Resolve effective permissions for a user within an owner context.
     * Looks up their grant, gets the template permissions, applies overrides.
     */
    public List<String> resolvePermissions(String userId, String ownerId) {
        List<PermissionGrantEntity> grants = grantRepo.findByUserIdAndOwnerId(userId, ownerId);
        if (grants.isEmpty()) {
            return List.of();
        }

        PermissionGrantEntity grant = grants.get(0);

        if (grant.getOverridePermissions() != null && !grant.getOverridePermissions().isEmpty()) {
            return parseJsonPermissions(grant.getOverridePermissions());
        }

        if (grant.getTemplateId() != null) {
            PermissionTemplateEntity template = templateRepo.findById(grant.getTemplateId()).orElse(null);
            if (template != null && template.getPermissions() != null) {
                return parseJsonPermissions(template.getPermissions());
            }
        }

        return List.of();
    }

    // --- Internal conversions ---

    private PermissionTemplateDTO toDto(PermissionTemplateEntity entity) {
        PermissionTemplateDTO dto = new PermissionTemplateDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setIsSystem(Boolean.TRUE.equals(entity.getIsSystem()));
        dto.setPermissions(parseJsonPermissions(entity.getPermissions()));
        return dto;
    }

    /**
     * Parsea el JSON almacenado en {@code permission_templates.permissions} /
     * {@code permission_grants.override_permissions} como una lista de strings.
     *
     * <p>Se usa Jackson (no manipulación de strings) porque:
     * <ol>
     *   <li>Soporta escape sequences, unicode, caracteres especiales en nombres
     *       de permisos (ej. comas literales) sin romperse.</li>
     *   <li>Rechaza JSON malformado explícitamente en vez de entregar basura.</li>
     * </ol>
     *
     * <p>Devuelve lista vacía para {@code null}, cadena vacía o JSON inválido —
     * un permiso ausente es equivalente a "sin permisos", no debe tirar el
     * login ni la emisión de JWTs.</p>
     */
    private List<String> parseJsonPermissions(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, STRING_LIST);
            return parsed != null ? parsed : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Serializa una lista de permisos como JSON array para persistirla en la
     * columna JSONB. Un {@code null} se normaliza a lista vacía para no dejar
     * NULL en una columna que siempre debe contener un array JSON válido.
     */
    private String serializePermissions(List<String> permissions) {
        List<String> safe = permissions != null ? permissions : new ArrayList<>();
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize permissions to JSON", e);
        }
    }
}
