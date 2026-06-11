package com.admindi.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "property_movements")
public class PropertyMovementEntity {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "property_id", nullable = false)
    private String propertyId;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "actor_user_id")
    private String actorUserId;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    @Column(name = "metadata_json", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String metadataJson;

    @Column(name = "attachment_file_id")
    private String attachmentFileId;

    public PropertyMovementEntity() {}

    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getPropertyId() { return propertyId; } public void setPropertyId(String v) { this.propertyId = v; }
    public String getOwnerId() { return ownerId; } public void setOwnerId(String v) { this.ownerId = v; }
    public String getResourceType() { return resourceType; } public void setResourceType(String v) { this.resourceType = v; }
    public String getResourceId() { return resourceId; } public void setResourceId(String v) { this.resourceId = v; }
    public String getActorUserId() { return actorUserId; } public void setActorUserId(String v) { this.actorUserId = v; }
    public String getActorRole() { return actorRole; } public void setActorRole(String v) { this.actorRole = v; }
    public String getEventType() { return eventType; } public void setEventType(String v) { this.eventType = v; }
    public String getTitle() { return title; } public void setTitle(String v) { this.title = v; }
    public String getDescription() { return description; } public void setDescription(String v) { this.description = v; }
    public LocalDateTime getOccurredAt() { return occurredAt; } public void setOccurredAt(LocalDateTime v) { this.occurredAt = v; }
    public String getMetadataJson() { return metadataJson; } public void setMetadataJson(String v) { this.metadataJson = v; }
    public String getAttachmentFileId() { return attachmentFileId; } public void setAttachmentFileId(String v) { this.attachmentFileId = v; }
}
