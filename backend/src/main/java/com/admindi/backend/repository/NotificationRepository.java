package com.admindi.backend.repository;

import com.admindi.backend.model.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<NotificationEntity, String> {
    List<NotificationEntity> findByUserIdOrderByCreatedAtDesc(String userId);
    List<NotificationEntity> findByUserIdAndReadFalseOrderByCreatedAtDesc(String userId);
    long countByUserIdAndReadFalse(String userId);

    /**
     * Idempotencia para schedulers: ¿ya existe una notificación de este eventType para este usuario
     * creada después del instante `since`? Se usa en digests diarios/mensuales para no duplicar
     * envíos si el job se ejecuta más de una vez (crash + restart dentro de la ventana).
     */
    @Query("SELECT COUNT(n) > 0 FROM NotificationEntity n " +
           "WHERE n.userId = :userId " +
           "AND n.eventType = :eventType " +
           "AND n.createdAt >= :since")
    boolean existsRecentForUser(@Param("userId") String userId,
                                @Param("eventType") String eventType,
                                @Param("since") LocalDateTime since);
}
