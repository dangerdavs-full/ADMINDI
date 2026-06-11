package com.admindi.backend.dto;

import com.admindi.backend.model.PropertyStatus;
import java.time.LocalDateTime;

public class PropertyDTO {
    private String id;
    private String ownerId;
    private String name;
    private String address;
    private String type;
    private String predial;
    private String description;
    private PropertyStatus status;
    private boolean active;
    private int unitCount;
    private LocalDateTime createdAt;

    public PropertyDTO() {}

    public PropertyDTO(String id, String ownerId, String name, String address, String type,
                       String predial, String description, PropertyStatus status,
                       boolean active, int unitCount, LocalDateTime createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.address = address;
        this.type = type;
        this.predial = predial;
        this.description = description;
        this.status = status;
        this.active = active;
        this.unitCount = unitCount;
        this.createdAt = createdAt;
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
    public int getUnitCount() { return unitCount; }
    public void setUnitCount(int unitCount) { this.unitCount = unitCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
