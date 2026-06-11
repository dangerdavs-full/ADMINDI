package com.admindi.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * V49 / Bloque 4: snapshot agregado del expediente del inmueble.
 *
 * <p>Tres propósitos en la misma carga:</p>
 * <ol>
 *   <li>Ficha viva del inmueble (metadatos base).</li>
 *   <li>Estado contable resumido (ingresos cobrados, egresos pagados, balance).</li>
 *   <li>Colecciones operativas: expedientes de inquilinos, timeline, facturas,
 *       egresos (con URLs de presupuesto/comprobante) y leases.</li>
 * </ol>
 *
 * <p>Ver {@code docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §5}.</p>
 */
public class PropertyExpedienteDTO {

    private String propertyId;
    private String ownerId;
    private String name;
    private String status;
    private String addressLine;
    private LocalDateTime generatedAt;

    private BigDecimal totalIncomeCollected;
    private BigDecimal totalExpensesPaid;
    private BigDecimal netBalance;
    private int openInvoices;
    private int paidInvoices;
    private int activeTenants;
    private int archivedTenants;

    private List<TenantProfileSummary> activeTenantProfiles;
    private List<LeaseSummary> leases;
    private List<InvoiceDTO> invoices;
    private List<ExpenseSummary> expenses;
    private List<PropertyMovementDTO> timeline;

    /**
     * Proyección mínima de {@code TenantProfileEntity} para el expediente.
     * Evita serializar el entity JPA completo (cross-referencia con lease/user/property
     * y proxies LAZY que Jackson no puede resolver fuera de la sesión).
     */
    public record TenantProfileSummary(
            String id,
            String userId,
            String propertyId,
            BigDecimal rentAmount,
            int paymentDay,
            LocalDateTime archivedAt) {}

    /**
     * Proyección mínima de {@code LeaseEntity}. El lease mantiene FK lazy a
     * propiedad y usuario; devolvemos sólo los ids para no arrastrar el grafo.
     */
    public record LeaseSummary(
            String id,
            String tenantUserId,
            String propertyId,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal monthlyRent,
            BigDecimal depositAmount,
            int paymentDay,
            String documentUrl) {}

    /**
     * Proyección mínima de {@code ExpenseEntity} que conserva los campos clave
     * del Bloque 4 (budgetFileId, paymentProofFileId) sin arrastrar metadatos.
     */
    public record ExpenseSummary(
            String id,
            String type,
            String description,
            String status,
            String paymentSettlementStatus,
            BigDecimal approvedAmount,
            BigDecimal paidAmount,
            BigDecimal outstandingAmount,
            LocalDateTime createdAt,
            LocalDateTime paidAt,
            String budgetFileId,
            String paymentProofFileId,
            String propertyId,
            String linkedResourceType,
            String linkedResourceId) {}

    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String addressLine) { this.addressLine = addressLine; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public BigDecimal getTotalIncomeCollected() { return totalIncomeCollected; }
    public void setTotalIncomeCollected(BigDecimal v) { this.totalIncomeCollected = v; }
    public BigDecimal getTotalExpensesPaid() { return totalExpensesPaid; }
    public void setTotalExpensesPaid(BigDecimal v) { this.totalExpensesPaid = v; }
    public BigDecimal getNetBalance() { return netBalance; }
    public void setNetBalance(BigDecimal netBalance) { this.netBalance = netBalance; }
    public int getOpenInvoices() { return openInvoices; }
    public void setOpenInvoices(int openInvoices) { this.openInvoices = openInvoices; }
    public int getPaidInvoices() { return paidInvoices; }
    public void setPaidInvoices(int paidInvoices) { this.paidInvoices = paidInvoices; }
    public int getActiveTenants() { return activeTenants; }
    public void setActiveTenants(int activeTenants) { this.activeTenants = activeTenants; }
    public int getArchivedTenants() { return archivedTenants; }
    public void setArchivedTenants(int archivedTenants) { this.archivedTenants = archivedTenants; }
    public List<TenantProfileSummary> getActiveTenantProfiles() { return activeTenantProfiles; }
    public void setActiveTenantProfiles(List<TenantProfileSummary> v) { this.activeTenantProfiles = v; }
    public List<LeaseSummary> getLeases() { return leases; }
    public void setLeases(List<LeaseSummary> leases) { this.leases = leases; }
    public List<InvoiceDTO> getInvoices() { return invoices; }
    public void setInvoices(List<InvoiceDTO> invoices) { this.invoices = invoices; }
    public List<ExpenseSummary> getExpenses() { return expenses; }
    public void setExpenses(List<ExpenseSummary> expenses) { this.expenses = expenses; }
    public List<PropertyMovementDTO> getTimeline() { return timeline; }
    public void setTimeline(List<PropertyMovementDTO> timeline) { this.timeline = timeline; }
}
