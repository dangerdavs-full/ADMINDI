package com.admindi.backend.repository;

import com.admindi.backend.model.CepValidationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CepValidationAttemptRepository extends JpaRepository<CepValidationAttempt, String> {
    List<CepValidationAttempt> findByTransferProofId(String transferProofId);
}
