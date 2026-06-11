package com.admindi.backend.repository;

import com.admindi.backend.model.NotificationPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreferenceEntity, String> {
    List<NotificationPreferenceEntity> findByUserId(String userId);
    Optional<NotificationPreferenceEntity> findByUserIdAndEventTypeAndChannel(String userId, String eventType, String channel);
}
