package com.admindi.backend.repository;

import com.admindi.backend.model.UserActivationTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserActivationTokenRepository extends JpaRepository<UserActivationTokenEntity, String> {

    Optional<UserActivationTokenEntity> findByTokenHash(String tokenHash);

    List<UserActivationTokenEntity> findByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(String userId);

    /** Cleanup: borra tokens expirados hace más del umbral (housekeeping opcional). */
    @Modifying
    @Transactional
    @Query("DELETE FROM UserActivationTokenEntity t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(LocalDateTime cutoff);

    /** Borrado masivo al tombstonear/hard-deletear un user. */
    @Modifying
    @Transactional
    @Query("DELETE FROM UserActivationTokenEntity t WHERE t.userId = :userId")
    int deleteByUserId(String userId);
}
