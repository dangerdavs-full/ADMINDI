package com.admindi.backend.model;

import jakarta.persistence.*;


@Entity
@Table(name = "units")
public class UnitEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private PropertyEntity property;

    @Column(nullable = false)
    private String name; // Ej: Depto 4B

    @Column(nullable = false)
    private String type; // departamento, local, oficina, bodega

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UnitOccupancyStatus status;

    private Double squareMeters;
    private Integer bedrooms;
    private Integer bathrooms;
    private String floorCode;

    @Column(columnDefinition = "text")
    private String notes;

    public UnitEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public PropertyEntity getProperty() { return property; }
    public void setProperty(PropertyEntity property) { this.property = property; }
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
