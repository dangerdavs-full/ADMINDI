package com.admindi.backend.repository;

import com.admindi.backend.model.AiUsageLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AiUsageLogRepository extends JpaRepository<AiUsageLogEntity, String> {

    /**
     * Suma del costo (USD) de todas las llamadas exitosas del user desde {@code since}.
     * Usado por {@code ClaudeService} para verificar el presupuesto diario antes de
     * hacer una llamada nueva.
     */
    @Query("SELECT COALESCE(SUM(l.costUsd), 0) FROM AiUsageLogEntity l " +
           "WHERE l.userId = :userId " +
           "AND l.success = true " +
           "AND l.createdAt >= :since")
    BigDecimal sumCostByUserSince(@Param("userId") String userId,
                                  @Param("since") LocalDateTime since);

    List<AiUsageLogEntity> findTop100ByUserIdOrderByCreatedAtDesc(String userId);

    List<AiUsageLogEntity> findTop100ByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
