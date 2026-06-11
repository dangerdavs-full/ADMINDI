package com.admindi.backend.repository;

import com.admindi.backend.model.PaymentEntity;
import com.admindi.backend.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentRepository extends JpaRepository<PaymentEntity, String> {
    List<PaymentEntity> findByInvoiceId(String invoiceId);
    List<PaymentEntity> findByOwnerId(String ownerId);
    List<PaymentEntity> findByOwnerIdAndStatus(String ownerId, PaymentStatus status);
    List<PaymentEntity> findByTenantProfileId(String tenantProfileId);

    boolean existsByGatewayReferenceAndStatus(String gatewayReference, PaymentStatus status);
}
