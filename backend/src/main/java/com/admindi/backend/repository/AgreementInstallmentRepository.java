package com.admindi.backend.repository;

import com.admindi.backend.model.AgreementInstallmentEntity;
import com.admindi.backend.model.InstallmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface AgreementInstallmentRepository extends JpaRepository<AgreementInstallmentEntity, String> {
    List<AgreementInstallmentEntity> findByAgreementId(String agreementId);
    List<AgreementInstallmentEntity> findByStatusAndDueDateBefore(InstallmentStatus status, LocalDate date);
}
