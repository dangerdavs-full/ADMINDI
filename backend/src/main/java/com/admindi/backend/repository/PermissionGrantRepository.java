package com.admindi.backend.repository;

import com.admindi.backend.model.PermissionGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PermissionGrantRepository extends JpaRepository<PermissionGrantEntity, String> {
    List<PermissionGrantEntity> findByUserIdAndOwnerId(String userId, String ownerId);
    List<PermissionGrantEntity> findByOwnerId(String ownerId);
    List<PermissionGrantEntity> findByUserId(String userId);
}
