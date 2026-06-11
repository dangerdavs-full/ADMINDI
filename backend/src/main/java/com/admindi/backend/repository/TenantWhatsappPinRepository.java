package com.admindi.backend.repository;

import com.admindi.backend.model.TenantWhatsappPinEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantWhatsappPinRepository extends JpaRepository<TenantWhatsappPinEntity, String> {

    Optional<TenantWhatsappPinEntity> findByUserId(String userId);
}
