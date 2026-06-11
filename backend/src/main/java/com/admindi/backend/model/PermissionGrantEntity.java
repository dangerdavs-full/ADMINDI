package com.admindi.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "permission_grants")
public class PermissionGrantEntity {

    @Id
    private String id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "owner_id")
    private String ownerId;

    @Column(name = "template_id")
    private String templateId;

    @Column(name = "override_permissions", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String overridePermissions;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt;

    @Column(name = "granted_by")
    private String grantedBy;

    public PermissionGrantEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public String getOverridePermissions() { return overridePermissions; }
    public void setOverridePermissions(String overridePermissions) { this.overridePermissions = overridePermissions; }
    public LocalDateTime getGrantedAt() { return grantedAt; }
    public void setGrantedAt(LocalDateTime grantedAt) { this.grantedAt = grantedAt; }
    public String getGrantedBy() { return grantedBy; }
    public void setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; }
}
