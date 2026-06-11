package com.admindi.backend.repository;

import com.admindi.backend.model.PaymentReminderSentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentReminderSentRepository extends JpaRepository<PaymentReminderSentEntity, String> {

    /**
     * Consulta explícita en JPQL: el parser de nombres de método de Spring Data
     * no distingue "DaysBefore" como una sola propiedad (intenta resolver "days"
     * y "before" por separado), así que usamos @Query para evitar ambigüedad.
     */
    @Query("SELECT COUNT(p) > 0 FROM PaymentReminderSentEntity p " +
           "WHERE p.invoiceId = :invoiceId " +
           "AND p.daysBefore = :daysBefore " +
           "AND p.channel = :channel " +
           "AND p.recipientUserId = :recipientUserId")
    boolean existsByInvoiceIdAndDaysBeforeAndChannelAndRecipientUserId(
            @Param("invoiceId") String invoiceId,
            @Param("daysBefore") int daysBefore,
            @Param("channel") String channel,
            @Param("recipientUserId") String recipientUserId);
}
