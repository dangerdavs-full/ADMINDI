package com.admindi.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "permission_templates")
public class PermissionTemplateEntity {

    @Id
    private String id;

    private String name;
    private String description;
    
    @Column(name = "is_system")
    private Boolean isSystem;

    // To keep it simple in JPA without custom types, we store as JSON String 
    // or map to a structured type if Jackson is configured. 
    // For V1, we will map JSONB as String.
    @Column(columnDefinition = "jsonb")
    private String permissions;

    public PermissionTemplateEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getIsSystem() { return isSystem; }
    public void setIsSystem(Boolean isSystem) { this.isSystem = isSystem; }
    public String getPermissions() { return permissions; }
    public void setPermissions(String permissions) { this.permissions = permissions; }
}
