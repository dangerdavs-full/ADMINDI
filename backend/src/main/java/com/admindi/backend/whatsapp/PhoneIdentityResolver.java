package com.admindi.backend.whatsapp;

import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Resuelve el número de teléfono de WhatsApp a un {@link UserEntity} con role
 * TENANT activo.
 *
 * Limitación técnica: {@code users.phone} y {@code users.contact_phone} están
 * cifrados columnwise (AES-GCM, converter {@code EncryptedStringConverter}).
 * No se puede hacer {@code WHERE phone = ?} en SQL. Por eso cargamos todos los
 * tenants activos (N acotado) y descifra/comparamos en memoria.
 *
 * Optimización futura: si el tenant count crece a miles, agregar columna
 * {@code phone_hash_sha256} con HMAC determinista para indexar búsquedas.
 *
 * Seguridad:
 *  - Twilio envía {@code From} en formato "whatsapp:+5215512345678". Lo
 *    normalizamos a E.164 "+5215512345678" antes de comparar.
 *  - Los números del DB pueden haberse guardado de múltiples formas
 *    ("5512345678", "+525512345678", "+5215512345678"). Normalizamos ambos
 *    extremos antes de comparar.
 *  - Nunca loggeamos el teléfono completo (usamos redact).
 */
@Service
public class PhoneIdentityResolver {

    private static final Logger logger = LoggerFactory.getLogger(PhoneIdentityResolver.class);

    private final UserRepository userRepo;

    public PhoneIdentityResolver(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /**
     * Convierte "whatsapp:+52...", "+52...", "52...", "5512345678" a una
     * forma canónica "+<digitos>" útil para comparar. Devuelve vacío si la
     * entrada no tiene dígitos razonables.
     */
    public static String toE164(String raw) {
        if (raw == null) return "";
        String work = raw.trim();
        if (work.toLowerCase().startsWith("whatsapp:")) {
            work = work.substring("whatsapp:".length());
        }
        String digits = work.replaceAll("[^0-9]", "");
        if (digits.length() < 8) return "";
        return "+" + digits;
    }

    /**
     * Busca un TENANT activo cuyo teléfono coincida con el E.164 recibido.
     *
     * Compatibilidad MX: si el número llega como "+5215512345678" (con el
     * "1" que México agrega para móviles) pero la cuenta lo tiene como
     * "+525512345678", ambos matchean porque comparamos también la versión
     * sin el "1" insertado después del "52".
     */
    /**
     * Usuario activo vinculado al teléfono. Si el mismo número está en dueño e
     * inquilino (caso raro), prioriza OWNER para el flujo de validación de pagos.
     */
    public Optional<ResolvedPhoneUser> resolveActiveUser(String whatsAppFromRaw) {
        String target = toE164(whatsAppFromRaw);
        if (target.isBlank()) {
            logger.debug("[PHONE-RESOLVER] raw={} produced empty E.164", redact(whatsAppFromRaw));
            return Optional.empty();
        }
        Optional<UserEntity> owner = resolveOwnerByPhone(target);
        if (owner.isPresent()) {
            return Optional.of(new ResolvedPhoneUser(Role.OWNER, owner.get()));
        }
        return resolveTenantByPhone(whatsAppFromRaw).map(u -> new ResolvedPhoneUser(Role.TENANT, u));
    }

    public Optional<UserEntity> resolveTenantByPhone(String whatsAppFromRaw) {
        return matchUsersByPhone(whatsAppFromRaw, Role.TENANT, true);
    }

    /**
     * Teléfono reconocido pero la cuenta está desactivada (mensaje distinto a
     * "número no vinculado").
     */
    public Optional<UserEntity> resolveInactiveByPhone(String whatsAppFromRaw) {
        Optional<UserEntity> owner = matchUsersByPhone(whatsAppFromRaw, Role.OWNER, false);
        if (owner.isPresent()) return owner;
        return matchUsersByPhone(whatsAppFromRaw, Role.TENANT, false);
    }

    /**
     * Dueño activo cuyo teléfono o contact_phone coincide con el WhatsApp entrante.
     */
    public Optional<UserEntity> resolveOwnerByPhone(String whatsAppFromRaw) {
        return matchUsersByPhone(whatsAppFromRaw, Role.OWNER, true);
    }

    private Optional<UserEntity> matchUsersByPhone(String whatsAppFromRaw, Role role, boolean activeOnly) {
        String target = toE164(whatsAppFromRaw);
        if (target.isBlank()) {
            return Optional.empty();
        }
        String targetAlt = maybeStripMxMobilePrefix(target);

        List<UserEntity> candidates = activeOnly
                ? userRepo.findByRoleAndActiveTrue(role)
                : userRepo.findByRoleAndActiveFalse(role);

        for (UserEntity user : candidates) {
            if (matchesAny(user.getPhone(), target, targetAlt)) return Optional.of(user);
            if (matchesAny(user.getContactPhone(), target, targetAlt,
                    user.getContactCountryCode())) {
                return Optional.of(user);
            }
        }
        return Optional.empty();
    }

    public record ResolvedPhoneUser(Role role, UserEntity user) {}

    private boolean matchesAny(String candidate, String... targets) {
        if (candidate == null || candidate.isBlank()) return false;
        String digits = candidate.replaceAll("[^0-9+]", "");
        if (digits.isEmpty()) return false;
        String normalized = digits.startsWith("+") ? digits : "+" + digits;
        for (String t : targets) {
            if (t == null || t.isBlank()) continue;
            if (normalized.equals(t)) return true;
            if (normalized.equals(maybeStripMxMobilePrefix(t))) return true;
            if (maybeStripMxMobilePrefix(normalized).equals(t)) return true;
        }
        return false;
    }

    /**
     * WhatsApp en México reporta móviles como "+52155..." (con el "1" extra
     * después del código de país). Los registros pueden guardarse sin ese "1":
     * "+5255..." Este helper quita el "1" si aparece justo tras "+52".
     */
    private static String maybeStripMxMobilePrefix(String e164) {
        if (e164 == null) return "";
        if (e164.startsWith("+521") && e164.length() > 4) {
            return "+52" + e164.substring(4);
        }
        return e164;
    }

    private static String redact(String raw) {
        if (raw == null) return "(null)";
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 6) return "***";
        return digits.substring(0, Math.min(4, digits.length())) + "***"
                + digits.substring(digits.length() - 3);
    }
}
