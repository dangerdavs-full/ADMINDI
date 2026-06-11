package com.admindi.backend.repository;

import com.admindi.backend.model.RefreshTokenSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenSessionRepository extends JpaRepository<RefreshTokenSessionEntity, String> {
    Optional<RefreshTokenSessionEntity> findByTokenHash(String tokenHash);
    List<RefreshTokenSessionEntity> findByUserId(String userId);

    /** Purge sessions that are expired OR revoked older than cutoff */
    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshTokenSessionEntity s WHERE s.expiresAt < :cutoff OR (s.revoked = true AND s.issuedAt < :cutoff)")
    int deleteExpiredAndRevoked(LocalDateTime cutoff);

    @Modifying
    @Transactional
    void deleteByUserId(String userId);
}
