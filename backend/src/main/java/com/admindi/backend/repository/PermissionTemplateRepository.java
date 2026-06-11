package com.admindi.backend.repository;

import com.admindi.backend.model.PermissionTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PermissionTemplateRepository extends JpaRepository<PermissionTemplateEntity, String> {
}
