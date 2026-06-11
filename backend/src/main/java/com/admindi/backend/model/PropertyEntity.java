package com.admindi.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "properties")
public class PropertyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = true)
    private String address;

    @Column(length = 50)
    private String type; // comercial, habitacional, mixto, industrial

    @Column(length = 100)
    private String predial;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PropertyStatus status = PropertyStatus.AVAILABLE;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public PropertyEntity() {}

    public PropertyEntity(String ownerId, String name, String address, PropertyStatus status) {
        this.ownerId = ownerId;
        this.name = name;
        this.address = address;
        this.status = status != null ? status : PropertyStatus.AVAILABLE;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getPredial() { return predial; }
    public void setPredial(String predial) { this.predial = predial; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public PropertyStatus getStatus() { return status; }
    public void setStatus(PropertyStatus status) { this.status = status; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
