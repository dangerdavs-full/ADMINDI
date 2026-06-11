package com.admindi.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.admindi.backend.model.OwnerMembershipEntity;
import com.admindi.backend.model.OwnerMembershipId;

import java.util.List;
import java.util.Optional;

@Repository
public interface OwnerMembershipRepository extends JpaRepository<OwnerMembershipEntity, OwnerMembershipId> {
    List<OwnerMembershipEntity> findByUserId(String userId);
    List<OwnerMembershipEntity> findByOwnerId(String ownerId);

    Optional<OwnerMembershipEntity> findByUserIdAndOwnerId(String userId, String ownerId);
}
