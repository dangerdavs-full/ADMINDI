package com.admindi.backend.whatsapp;

import com.admindi.backend.model.TenantWhatsappPinEntity;
import com.admindi.backend.repository.TenantWhatsappPinRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Gestión del NIP del inquilino para el chatbot de WhatsApp.
 *
 * Reglas de seguridad:
 *  - Hash con BCrypt (strength configurable, default 12).
 *  - Sólo dígitos, longitud 4-6.
 *  - Bloqueo automático tras N intentos fallidos consecutivos.
 *  - Bloqueo temporal (30 min default). El SUPER_ADMIN u OWNER dentro de su
 *    contexto puede desbloquear/resetear vía {@code AccountRecoveryService}.
 *  - Jamás loggear el NIP en claro, ni siquiera en error messages.
 */
@Service
public class WhatsAppPinService {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppPinService.class);
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");

    private final TenantWhatsappPinRepository pinRepo;
    private final BCryptPasswordEncoder encoder;

    @Value("${whatsapp.pin.min-length:4}")
    private int minLength;

    @Value("${whatsapp.pin.max-length:6}")
    private int maxLength;

    @Value("${whatsapp.pin.max-attempts:5}")
    private int maxAttempts;

    @Value("${whatsapp.pin.lockout-minutes:30}")
    private int lockoutMinutes;

    public WhatsAppPinService(TenantWhatsappPinRepository pinRepo,
                              @Value("${whatsapp.pin.bcrypt-strength:12}") int strength) {
        this.pinRepo = pinRepo;
        this.encoder = new BCryptPasswordEncoder(strength);
    }

    /**
     * ¿El user tiene NIP configurado?
     */
    public boolean hasPinConfigured(String userId) {
        if (userId == null) return false;
        return pinRepo.findByUserId(userId).isPresent();
    }

    /**
     * Crea o reemplaza el NIP. Si ya existía, limpia intentos y lockout.
     *
     * @throws IllegalArgumentException si el pin no cumple el formato.
     */
    @Transactional
    public void setPin(String userId, String rawPin) {
        validateFormat(rawPin);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId requerido");
        }

        TenantWhatsappPinEntity entity = pinRepo.findByUserId(userId)
                .orElseGet(() -> {
                    TenantWhatsappPinEntity fresh = new TenantWhatsappPinEntity();
                    fresh.setUserId(userId);
                    return fresh;
                });

        entity.setPinHash(encoder.encode(rawPin));
        entity.setFailedAttempts(0);
        entity.setLockedUntil(null);
        pinRepo.save(entity);

        logger.info("[WHATSAPP-PIN] NIP set for user={} (length={} digits)",
                userId, rawPin.length());
    }

    /**
     * Verifica un NIP contra el hash almacenado.
     *
     * Efectos secundarios:
     *  - Si el NIP es correcto, resetea el contador y actualiza last_used_at.
     *  - Si es incorrecto, incrementa failed_attempts y aplica lockout si
     *    se alcanza max-attempts.
     *
     * @return true si el NIP coincide y el user no está bloqueado.
     */
    @Transactional
    public VerifyResult verify(String userId, String rawPin) {
        if (userId == null || rawPin == null) return VerifyResult.NOT_CONFIGURED;
        Optional<TenantWhatsappPinEntity> opt = pinRepo.findByUserId(userId);
        if (opt.isEmpty()) return VerifyResult.NOT_CONFIGURED;

        TenantWhatsappPinEntity entity = opt.get();

        if (isCurrentlyLocked(entity)) {
            return VerifyResult.LOCKED;
        }

        // Si el formato es obviamente inválido, cuenta como fallo para no abrir
        // ventana a sondeo automatizado.
        if (!isValidFormat(rawPin)) {
            return registerFailure(entity);
        }

        boolean matches;
        try {
            matches = encoder.matches(rawPin, entity.getPinHash());
        } catch (Exception ex) {
            logger.warn("[WHATSAPP-PIN] bcrypt match failed for user={}: {}",
                    userId, ex.getClass().getSimpleName());
            return VerifyResult.ERROR;
        }

        if (matches) {
            entity.setFailedAttempts(0);
            entity.setLockedUntil(null);
            entity.setLastUsedAt(LocalDateTime.now());
            pinRepo.save(entity);
            return VerifyResult.OK;
        }

        return registerFailure(entity);
    }

    /**
     * Libera el NIP: borra el hash para forzar re-configuración en la próxima
     * interacción por WhatsApp. Llamado desde AccountRecoveryService.PIN_RESET.
     */
    @Transactional
    public void reset(String userId) {
        if (userId == null) return;
        pinRepo.findByUserId(userId).ifPresent(pinRepo::delete);
        logger.info("[WHATSAPP-PIN] NIP reset for user={}", userId);
    }

    /**
     * Útil para el panel superadmin: saber si un user está actualmente
     * bloqueado (para mostrar hora de desbloqueo).
     */
    public boolean isLocked(String userId) {
        if (userId == null) return false;
        return pinRepo.findByUserId(userId)
                .map(this::isCurrentlyLocked)
                .orElse(false);
    }

    public Optional<LocalDateTime> getLockedUntil(String userId) {
        if (userId == null) return Optional.empty();
        return pinRepo.findByUserId(userId)
                .map(TenantWhatsappPinEntity::getLockedUntil);
    }

    public int getMinLength() { return minLength; }
    public int getMaxLength() { return maxLength; }

    public enum VerifyResult {
        OK,
        MISMATCH,
        LOCKED,
        NOT_CONFIGURED,
        ERROR
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private boolean isCurrentlyLocked(TenantWhatsappPinEntity entity) {
        LocalDateTime until = entity.getLockedUntil();
        return until != null && until.isAfter(LocalDateTime.now());
    }

    private VerifyResult registerFailure(TenantWhatsappPinEntity entity) {
        int attempts = entity.getFailedAttempts() + 1;
        entity.setFailedAttempts(attempts);
        if (attempts >= maxAttempts) {
            entity.setLockedUntil(LocalDateTime.now().plusMinutes(lockoutMinutes));
            logger.warn("[WHATSAPP-PIN] user={} locked for {} min after {} failed attempts",
                    entity.getUserId(), lockoutMinutes, attempts);
            pinRepo.save(entity);
            return VerifyResult.LOCKED;
        }
        pinRepo.save(entity);
        return VerifyResult.MISMATCH;
    }

    private void validateFormat(String rawPin) {
        if (!isValidFormat(rawPin)) {
            throw new IllegalArgumentException(
                    "El NIP debe ser numérico entre " + minLength + " y " + maxLength + " dígitos.");
        }
    }

    private boolean isValidFormat(String rawPin) {
        return rawPin != null
                && rawPin.length() >= minLength
                && rawPin.length() <= maxLength
                && DIGITS_ONLY.matcher(rawPin).matches();
    }
}
