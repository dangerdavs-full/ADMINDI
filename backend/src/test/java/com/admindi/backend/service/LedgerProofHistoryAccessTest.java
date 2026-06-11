package com.admindi.backend.service;

import com.admindi.backend.ai.AiAccountingService;
import com.admindi.backend.dto.TransferProofDTO;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.TransferProofStatus;
import com.admindi.backend.model.TransferProofSubmission;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.PaymentAgreementRepository;
import com.admindi.backend.repository.PaymentRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.TransferProofSubmissionRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerProofHistoryAccessTest {

    @Mock InvoiceRepository invoiceRepository;
    @Mock TenantProfileRepository profileRepository;
    @Mock UserRepository userRepository;
    @Mock FileStorageService fileStorageService;
    @Mock PaymentRepository paymentRepository;
    @Mock TransferProofSubmissionRepository proofRepository;
    @Mock DomainEventDispatcher dispatcher;
    @Mock BanxicoCepAdapter cepAdapter;
    @Mock PropertyMovementService propertyMovementService;
    @Mock LeaseRepository leaseRepository;
    @Mock PaymentAgreementRepository paymentAgreementRepository;
    @Mock ReportingPeriodService reportingPeriodService;
    @Mock PropertyRepository propertyRepository;
    @Mock AiAccountingService aiAccountingService;
    @Mock PaymentProofArchiver proofArchiver;
    @Mock BanxicoInstitutionCatalogService banxicoInstitutionCatalogService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void tenantCanReadOnlyOwnTerminalProofHistory() {
        LedgerService service = newService();
        UserEntity tenant = tenant("user-1", "tenant-user");
        TenantProfileEntity profile = profile("tp-1", "user-1", "owner-1", "prop-1");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "tenant-user",
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_TENANT"))));
        TenantContext.setCurrentOwner("owner-1");

        when(userRepository.findByLoginIdentifier("tenant-user")).thenReturn(Optional.of(tenant));
        when(profileRepository.findByIdAndUserId("tp-1", "user-1")).thenReturn(Optional.of(profile));
        when(invoiceRepository.findById(anyString())).thenReturn(Optional.empty());
        when(proofRepository.findByTenantProfileId("tp-1")).thenReturn(List.of(
                proof("proof-1", "inv-1", "tp-1", TransferProofStatus.REJECTED_BY_OWNER, 1,
                        LocalDateTime.parse("2026-04-22T10:00:00"), LocalDateTime.parse("2026-04-22T10:10:00")),
                proof("proof-2", "inv-2", "tp-1", TransferProofStatus.PENDING_OWNER_VALIDATION, 2,
                        LocalDateTime.parse("2026-04-22T11:00:00"), null),
                proof("proof-3", "inv-3", "tp-1", TransferProofStatus.VALIDATED, 3,
                        LocalDateTime.parse("2026-04-22T12:00:00"), LocalDateTime.parse("2026-04-22T12:05:00"))
        ));

        List<TransferProofDTO> history = service.getProofsHistory(null, "tp-1");

        assertEquals(2, history.size(), "Solo deben regresar estados terminales.");
        assertEquals("proof-3", history.get(0).getId(), "El mas reciente debe aparecer primero.");
        assertEquals("VALIDATED", history.get(0).getStatus());
        assertEquals(3, history.get(0).getAttemptNumber());
        assertEquals("proof-1", history.get(1).getId());
        assertEquals("REJECTED_BY_OWNER", history.get(1).getStatus());
        assertEquals(1, history.get(1).getAttemptNumber());
    }

    @Test
    void tenantCannotReadForeignTenantProfileHistory() {
        LedgerService service = newService();
        UserEntity tenant = tenant("user-1", "tenant-user");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "tenant-user",
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_TENANT"))));
        TenantContext.setCurrentOwner("owner-1");

        when(userRepository.findByLoginIdentifier("tenant-user")).thenReturn(Optional.of(tenant));
        when(profileRepository.findByIdAndUserId("tp-foreign", "user-1")).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.getProofsHistory(null, "tp-foreign"));
        assertTrue(ex.getMessage().toLowerCase().contains("expediente"));
    }

    private LedgerService newService() {
        return new LedgerService(
                invoiceRepository,
                profileRepository,
                userRepository,
                fileStorageService,
                paymentRepository,
                proofRepository,
                dispatcher,
                cepAdapter,
                propertyMovementService,
                leaseRepository,
                paymentAgreementRepository,
                reportingPeriodService,
                propertyRepository,
                aiAccountingService,
                proofArchiver,
                banxicoInstitutionCatalogService
        );
    }

    private UserEntity tenant(String id, String username) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setLoginUsername(username);
        user.setRole(Role.TENANT);
        return user;
    }

    private TenantProfileEntity profile(String id, String userId, String ownerId, String propertyId) {
        TenantProfileEntity profile = new TenantProfileEntity();
        profile.setId(id);
        profile.setUserId(userId);
        profile.setOwnerId(ownerId);
        profile.setPropertyId(propertyId);
        return profile;
    }

    private TransferProofSubmission proof(String id, String invoiceId, String tenantProfileId,
                                          TransferProofStatus status, int attemptNumber,
                                          LocalDateTime submittedAt, LocalDateTime reviewedAt) {
        TransferProofSubmission proof = new TransferProofSubmission();
        proof.setId(id);
        proof.setInvoiceId(invoiceId);
        proof.setTenantProfileId(tenantProfileId);
        proof.setOwnerId("owner-1");
        proof.setStatus(status);
        proof.setPaymentType("SPEI");
        proof.setAttemptNumber(attemptNumber);
        proof.setAmount(java.math.BigDecimal.TEN);
        proof.setSubmittedAt(submittedAt);
        proof.setReviewedAt(reviewedAt);
        return proof;
    }
}
