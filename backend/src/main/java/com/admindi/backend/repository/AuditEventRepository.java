package com.admindi.backend.repository;

import com.admindi.backend.model.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, String>,
        JpaSpecificationExecutor<AuditEventEntity> {
    List<AuditEventEntity> findByOwnerId(String ownerId);

    /**
     * Bulk DELETE via JPQL — NOT the derived {@code deleteBy...} variant.
     *
     * <p>Why this matters: the cascade in {@code OwnerCascadeDeletionService} purges
     * the target owner's audit trail, and the controller emits a final
     * {@code SUPERADMIN_USER_DELETE} right after. With the Spring Data derived
     * {@code deleteByOwnerId} (a SELECT + {@code deleteAll()}) the remove is placed
     * in Hibernate's action queue. At flush time Hibernate may reorder the queue
     * to run INSERTs before DELETEs, which makes the DELETE sweep away the
     * just-inserted audit row — the sysadmin's record of the action silently
     * disappears.
     *
     * <p>{@code @Modifying(flushAutomatically=true, clearAutomatically=true)} plus
     * an explicit JPQL {@code DELETE} sidesteps the action queue entirely: the SQL
     * runs at call time, before the controller adds its final audit row, so that
     * row survives the transaction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM AuditEventEntity a WHERE a.ownerId = :ownerId")
    void deleteByOwnerId(@Param("ownerId") String ownerId);

    List<AuditEventEntity> findByEventTypeContaining(String eventType);
}
