package com.admindi.backend.dto;

import java.time.LocalDateTime;

public class PropertyFileDTO {
    private String id;
    private String propertyId;
    private String category;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private LocalDateTime uploadedAt;
    private String downloadUrl;
    private String uploadedBy;
    private String uploaderRole;
    private String label;
    private String note;

    public PropertyFileDTO() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public String getUploaderRole() { return uploaderRole; }
    public void setUploaderRole(String uploaderRole) { this.uploaderRole = uploaderRole; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
