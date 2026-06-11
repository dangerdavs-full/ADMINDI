package com.admindi.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "platform_provider_assignments",
       uniqueConstraints = @UniqueConstraint(columnNames = {"provider_id", "owner_id"}))
public class PlatformProviderAssignmentEntity {

    @Id
    private String id;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column
    private boolean active = true;

    /** PLATFORM = catalog; PRIVATE = owner-linked contractor */
    @Column(name = "assignment_source", nullable = false, length = 20)
    private String assignmentSource = "PLATFORM";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public LocalDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(LocalDateTime assignedAt) { this.assignedAt = assignedAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getAssignmentSource() { return assignmentSource; }
    public void setAssignmentSource(String assignmentSource) { this.assignmentSource = assignmentSource; }
}
