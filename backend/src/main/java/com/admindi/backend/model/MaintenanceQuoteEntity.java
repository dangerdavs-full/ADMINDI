package com.admindi.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "maintenance_quotes")
public class MaintenanceQuoteEntity {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "ticket_id", nullable = false)
    private String ticketId;

    @Column(name = "provider_id")
    private String providerId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Notas libres escritas por el proveedor tras la visita al inmueble.
     *
     * <p>Uso típico: "encontré que la fuga también afectó el rodapié, agregué
     * concepto de pintura al presupuesto" o "el tenant no estaba cuando
     * llegué, reagendamos para mañana" — información que complementa la
     * cotización pero no es un "concepto técnico" del {@link #description}.
     * Siempre opcional (NULL-able) para no forzar al proveedor a llenarlo
     * cuando no haya nada que aclarar.</p>
     */
    @Column(name = "visit_notes", columnDefinition = "TEXT")
    private String visitNotes;

    @Column(name = "evidence_file_id")
    private String evidenceFileId;

    @Column(nullable = false, length = 20)
    private String status = "SUBMITTED"; // SUBMITTED, APPROVED, REJECTED

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt = LocalDateTime.now();

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    public MaintenanceQuoteEntity() {}

    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getTicketId() { return ticketId; } public void setTicketId(String v) { this.ticketId = v; }
    public String getProviderId() { return providerId; } public void setProviderId(String v) { this.providerId = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { this.amount = v; }
    public String getDescription() { return description; } public void setDescription(String v) { this.description = v; }
    public String getVisitNotes() { return visitNotes; } public void setVisitNotes(String v) { this.visitNotes = v; }
    public String getEvidenceFileId() { return evidenceFileId; } public void setEvidenceFileId(String v) { this.evidenceFileId = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public LocalDateTime getSubmittedAt() { return submittedAt; } public void setSubmittedAt(LocalDateTime v) { this.submittedAt = v; }
    public LocalDateTime getApprovedAt() { return approvedAt; } public void setApprovedAt(LocalDateTime v) { this.approvedAt = v; }
}
