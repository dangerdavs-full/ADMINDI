package com.admindi.backend.dto;

import com.admindi.backend.model.UnitOccupancyStatus;

public class UnitDTO {
    private String id;
    private String propertyId;
    private String propertyName;
    private String name;
    private String type;
    private UnitOccupancyStatus status;
    private Double squareMeters;
    private Integer bedrooms;
    private Integer bathrooms;
    private String floorCode;
    private String notes;

    public UnitDTO() {}

    public UnitDTO(String id, String propertyId, String propertyName, String name, String type,
                   UnitOccupancyStatus status, Double squareMeters, Integer bedrooms,
                   Integer bathrooms, String floorCode, String notes) {
        this.id = id;
        this.propertyId = propertyId;
        this.propertyName = propertyName;
        this.name = name;
        this.type = type;
        this.status = status;
        this.squareMeters = squareMeters;
        this.bedrooms = bedrooms;
        this.bathrooms = bathrooms;
        this.floorCode = floorCode;
        this.notes = notes;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public String getPropertyName() { return propertyName; }
    public void setPropertyName(String propertyName) { this.propertyName = propertyName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public UnitOccupancyStatus getStatus() { return status; }
    public void setStatus(UnitOccupancyStatus status) { this.status = status; }
    public Double getSquareMeters() { return squareMeters; }
    public void setSquareMeters(Double squareMeters) { this.squareMeters = squareMeters; }
    public Integer getBedrooms() { return bedrooms; }
    public void setBedrooms(Integer bedrooms) { this.bedrooms = bedrooms; }
    public Integer getBathrooms() { return bathrooms; }
    public void setBathrooms(Integer bathrooms) { this.bathrooms = bathrooms; }
    public String getFloorCode() { return floorCode; }
    public void setFloorCode(String floorCode) { this.floorCode = floorCode; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
