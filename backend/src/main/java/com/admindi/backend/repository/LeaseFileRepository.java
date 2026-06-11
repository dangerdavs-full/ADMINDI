package com.admindi.backend.repository;

import com.admindi.backend.model.LeaseFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeaseFileRepository extends JpaRepository<LeaseFileEntity, String> {
    List<LeaseFileEntity> findByLeaseId(String leaseId);
}
