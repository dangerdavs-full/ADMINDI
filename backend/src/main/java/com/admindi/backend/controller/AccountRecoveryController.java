package com.admindi.backend.controller;

import com.admindi.backend.service.AccountRecoveryService;
import com.admindi.backend.service.AccountRecoveryService.RecoveryRequest;
import com.admindi.backend.service.AccountRecoveryService.RecoveryResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/recovery")
public class AccountRecoveryController {

    private final AccountRecoveryService recoveryService;

    public AccountRecoveryController(AccountRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    /**
     * POST /api/admin/recovery
     * 
     * Executes an account recovery action (password reset, MFA reset, PIN reset,
     * or full recovery).
     * 
     * Access:
     * - SUPER_ADMIN: can recover any account (except other SUPER_ADMINs)
     * - OWNER: can recover PROPERTY_ADMIN, ACCOUNTANT, TENANT within own context
     * - PIN_RESET only applies to TENANT role (WhatsApp bot NIP — V55).
     * 
     * Body: {
     *   "targetUsername": "...",
     *   "type": "PASSWORD_RESET|MFA_RESET|PIN_RESET|FULL_RECOVERY",
     *   "reason": "...",
     *   "password": "...",   // contraseña del actor (reauth)
     *   "mfaCode": "..."     // código MFA del actor (reauth)
     * }
     * 
     * Returns: { "tempPassword": "...", "mfaReset": bool, "passwordReset": bool, "pinReset": bool, "sessionsRevoked": N }
     * Note: tempPassword is only returned once for PASSWORD_RESET or FULL_RECOVERY.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OWNER')")
    public ResponseEntity<RecoveryResult> executeRecovery(@RequestBody RecoveryRequest request) {
        RecoveryResult result = recoveryService.executeRecovery(request);
        return ResponseEntity.ok(result);
    }
}
