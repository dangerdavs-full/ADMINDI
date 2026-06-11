package com.admindi.backend.repository;

import com.admindi.backend.model.InvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<InvoiceEntity, String> {
    List<InvoiceEntity> findByOwnerId(String ownerId);
    List<InvoiceEntity> findByTenantProfileId(String tenantProfileId);
    Optional<InvoiceEntity> findByTenantProfileIdAndMonthYear(String tenantProfileId, String monthYear);
    List<InvoiceEntity> findByStatus(String status);
    List<InvoiceEntity> findBySettlementStatus(String settlementStatus);

    /**
     * Perf — PaymentReminderScheduler: facturas que vencen exactamente en una
     * fecha. Reemplaza findAll() + filtro en memoria sobre toda la tabla en el
     * tick diario. Respaldado por índice en due_date (V70).
     */
    List<InvoiceEntity> findByDueDate(LocalDate dueDate);
}
