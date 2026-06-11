package com.admindi.backend.service;

import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.PaymentAgreementEntity;
import com.admindi.backend.model.PaymentEntity;
import com.admindi.backend.model.TenantArchiveSnapshotEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.TransferProofSubmission;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.PaymentAgreementRepository;
import com.admindi.backend.repository.PaymentRepository;
import com.admindi.backend.repository.TenantArchiveSnapshotRepository;
import com.admindi.backend.repository.TransferProofSubmissionRepository;
import com.admindi.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Construye y persiste el snapshot financiero inmutable de un expediente al archivarlo.
 * No muta invoices ni payments: solo lee y resume.
 */
@Service
public class TenantArchiveSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(TenantArchiveSnapshotService.class);

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final TransferProofSubmissionRepository proofRepository;
    private final PaymentAgreementRepository agreementRepository;
    private final TenantArchiveSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public TenantArchiveSnapshotService(InvoiceRepository invoiceRepository,
                                        PaymentRepository paymentRepository,
                                        TransferProofSubmissionRepository proofRepository,
                                        PaymentAgreementRepository agreementRepository,
                                        TenantArchiveSnapshotRepository snapshotRepository,
                                        UserRepository userRepository) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.proofRepository = proofRepository;
        this.agreementRepository = agreementRepository;
        this.snapshotRepository = snapshotRepository;
        this.userRepository = userRepository;
        this.objectMapper = new ObjectMapper();
    }

    public TenantArchiveSnapshotEntity buildAndPersist(TenantProfileEntity profile,
                                                       String actorUserId,
                                                       String actorRole) {
        List<InvoiceEntity> invoices = invoiceRepository.findByTenantProfileId(profile.getId());
        List<PaymentEntity> payments = paymentRepository.findByTenantProfileId(profile.getId());
        List<TransferProofSubmission> proofs = proofRepository.findByTenantProfileId(profile.getId());
        List<PaymentAgreementEntity> agreements = agreementRepository.findByTenantProfileId(profile.getId());

        int monthsPaid = 0;
        int monthsWithDebt = 0;
        BigDecimal totalOwed = BigDecimal.ZERO;
        BigDecimal totalLateFee = BigDecimal.ZERO;

        Map<String, Map<String, Object>> byMonth = new LinkedHashMap<>();
        for (InvoiceEntity inv : invoices) {
            String status = inv.getStatus() == null ? "" : inv.getStatus();
            if ("PAID".equals(status)) {
                monthsPaid++;
            } else if (!"VOIDED".equalsIgnoreCase(status) && !"CANCELLED".equalsIgnoreCase(status)) {
                BigDecimal outstanding = inv.getOutstandingAmount() == null
                        ? BigDecimal.ZERO : inv.getOutstandingAmount();
                if (outstanding.signum() > 0) {
                    monthsWithDebt++;
                    totalOwed = totalOwed.add(outstanding);
                }
            }
            if (inv.getAppliedLateFee() != null) {
                totalLateFee = totalLateFee.add(inv.getAppliedLateFee());
            }

            Map<String, Object> row = new HashMap<>();
            row.put("invoiceId", inv.getId());
            row.put("monthYear", inv.getMonthYear());
            row.put("status", status);
            row.put("totalAmount", inv.getTotalAmount());
            row.put("paidAmount", inv.getPaidAmount());
            row.put("outstandingAmount", inv.getOutstandingAmount());
            row.put("appliedLateFee", inv.getAppliedLateFee());
            row.put("proofUrl", inv.getProofOfPaymentUrl());
            byMonth.put(inv.getMonthYear() != null ? inv.getMonthYear() : inv.getId(), row);
        }

        BigDecimal totalPaid = BigDecimal.ZERO;
        for (PaymentEntity p : payments) {
            if (p.getAmount() != null) totalPaid = totalPaid.add(p.getAmount());
        }

        int activeAgreements = 0;
        for (PaymentAgreementEntity a : agreements) {
            if (a.getStatus() == null) continue;
            switch (a.getStatus()) {
                case REQUESTED, APPROVED, ACTIVE -> activeAgreements++;
                default -> { /* ignore terminal/other */ }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("perMonth", byMonth);
        payload.put("evidences", proofs.stream().map(TransferProofSubmission::getFileUrl).filter(u -> u != null).toList());
        payload.put("agreements", agreements.stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("status", a.getStatus() == null ? null : a.getStatus().name());
            return m;
        }).toList());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            logger.warn("[Snapshot] JSON serialize failed for profile {}: {}", profile.getId(), e.getMessage());
            payloadJson = "{\"error\":\"serialize_failed\"}";
        }

        TenantArchiveSnapshotEntity snap = new TenantArchiveSnapshotEntity();
        snap.setId(UUID.randomUUID().toString());
        snap.setOwnerId(profile.getOwnerId());
        snap.setPropertyId(profile.getPropertyId());
        snap.setTenantUserId(profile.getUserId());
        snap.setTenantProfileId(profile.getId());
        UserEntity tenant = userRepository.findById(profile.getUserId()).orElse(null);
        if (tenant != null) {
            snap.setTenantName(tenant.getName());
            snap.setTenantEmail(tenant.getContactEmail());
        }
        snap.setMonthsPaidCount(monthsPaid);
        snap.setMonthsWithDebtCount(monthsWithDebt);
        snap.setTotalPaidAmount(totalPaid);
        snap.setTotalOwedAmount(totalOwed);
        snap.setAppliedLateFeeTotal(totalLateFee);
        snap.setActiveAgreementsCount(activeAgreements);
        snap.setEvidencesCount(proofs.size());
        snap.setPayloadJson(payloadJson);
        snap.setArchivedByUserId(actorUserId);
        snap.setArchivedByRole(actorRole);
        snap.setArchivedAt(LocalDateTime.now());
        return snapshotRepository.save(snap);
    }
}
