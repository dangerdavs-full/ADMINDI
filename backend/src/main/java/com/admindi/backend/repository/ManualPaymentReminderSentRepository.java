package com.admindi.backend.repository;

import com.admindi.backend.model.ManualPaymentReminderSentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface ManualPaymentReminderSentRepository
        extends JpaRepository<ManualPaymentReminderSentEntity, String> {

    /**
     * Cuenta los envíos manuales a un inquilino en la ventana temporal dada.
     * Se usa para aplicar el rate limit (máximo 2 envíos por 24 horas).
     *
     * <p>Usamos {@code @Query} explícito en vez de derivación por nombre porque Spring Data
     * interpreta mal la combinación {@code SentAtAfter} junto con otros criterios cuando
     * el nombre crece; el JPQL deja la intención inequívoca y elimina la ambigüedad.
     */
    @Query("SELECT COUNT(m) FROM ManualPaymentReminderSentEntity m " +
           "WHERE m.tenantUserId = :tenantUserId AND m.sentAt > :since")
    long countByTenantUserIdAndSentAtAfter(
            @Param("tenantUserId") String tenantUserId,
            @Param("since") LocalDateTime since);
}
