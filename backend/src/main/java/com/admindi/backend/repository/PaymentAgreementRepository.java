package com.admindi.backend.repository;

import com.admindi.backend.model.PaymentAgreementEntity;
import com.admindi.backend.model.PaymentAgreementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PaymentAgreementRepository extends JpaRepository<PaymentAgreementEntity, String> {
    List<PaymentAgreementEntity> findByOwnerId(String ownerId);
    List<PaymentAgreementEntity> findByTenantProfileId(String tenantProfileId);
    List<PaymentAgreementEntity> findByOwnerIdAndStatus(String ownerId, PaymentAgreementStatus status);
    List<PaymentAgreementEntity> findByStatus(PaymentAgreementStatus status);
    List<PaymentAgreementEntity> findByInvoiceId(String invoiceId);
}
