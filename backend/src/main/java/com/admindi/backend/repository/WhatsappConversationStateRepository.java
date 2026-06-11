package com.admindi.backend.repository;

import com.admindi.backend.model.WhatsappConversationStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WhatsappConversationStateRepository
        extends JpaRepository<WhatsappConversationStateEntity, String> {

    Optional<WhatsappConversationStateEntity> findByPhoneE164(String phoneE164);

    @Modifying
    @Query("DELETE FROM WhatsappConversationStateEntity w WHERE w.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
