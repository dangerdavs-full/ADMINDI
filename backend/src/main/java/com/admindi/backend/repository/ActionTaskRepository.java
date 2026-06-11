package com.admindi.backend.repository;

import com.admindi.backend.model.ActionTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ActionTaskRepository extends JpaRepository<ActionTaskEntity, String> {
    List<ActionTaskEntity> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);
    List<ActionTaskEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, List<String> statuses);
    List<ActionTaskEntity> findByUserIdOrderByCreatedAtDesc(String userId);
    long countByUserIdAndStatus(String userId, String status);

    List<ActionTaskEntity> findByResourceTypeAndResourceId(String resourceType, String resourceId);

    List<ActionTaskEntity> findByResourceTypeAndResourceIdAndStatus(String resourceType, String resourceId, String status);
}
