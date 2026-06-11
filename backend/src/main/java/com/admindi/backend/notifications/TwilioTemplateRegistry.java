package com.admindi.backend.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registro único de Content SIDs de Twilio para plantillas WhatsApp aprobadas por Meta.
 *
 * Diseño (Fase 2 notificaciones):
 *  - Fuera de la ventana de 24h de conversación WhatsApp, Meta exige plantillas pre-aprobadas
 *    (HSM). Nuestras notificaciones salientes son 100% iniciadas por la plataforma, por lo que
 *    TODOS los envíos deben pasar por {@code contentSid}.
 *  - Este componente es la única fuente de verdad sobre qué Content SID corresponde a cada
 *    {@code eventType}. Si un SID no está configurado, el service no intentará usar plantilla
 *    y caerá al body libre (comportamiento legacy), que solo funciona dentro de la ventana
 *    de 24h — fuera de ella Twilio responde con error 63016 "template required".
 *  - Los SIDs se cargan desde {@code application.yml} → {@code application-secrets.yml}
 *    (vía perfil {@code secrets}) o desde variables de entorno {@code TWILIO_TEMPLATE_*}.
 *    NUNCA hardcodear SIDs en el repo.
 *
 * Regla de nomenclatura:
 *  - Las claves internas usan el {@code eventType} del dispatcher (ej. {@code OWNER_WELCOME}).
 *  - El mapping eventType → property YAML se hace en {@link #initMapping()} de forma explícita
 *    para evitar acoplar nombres de evento con nombres de property key.
 *
 * Seguridad:
 *  - El SID en sí NO es un secreto (es un identificador público), pero se trata como config
 *    sensible para no mezclarlo con el código.
 *  - No se loguea el SID completo; si hace falta trazar, se usa {@link #redactSid(String)}.
 */
@Component
public class TwilioTemplateRegistry {

    private static final Logger logger = LoggerFactory.getLogger(TwilioTemplateRegistry.class);

    @Value("${twilio.templates.enabled:false}")
    private boolean templatesEnabled;

    @Value("${twilio.templates.owner-welcome:}")
    private String ownerWelcome;

    @Value("${twilio.templates.tenant-welcome:}")
    private String tenantWelcome;

    @Value("${twilio.templates.owner-profile-updated:}")
    private String ownerProfileUpdated;

    @Value("${twilio.templates.payment-reminder-5d:}")
    private String paymentReminder5d;

    @Value("${twilio.templates.payment-reminder-3d:}")
    private String paymentReminder3d;

    @Value("${twilio.templates.payment-reminder-2d:}")
    private String paymentReminder2d;

    @Value("${twilio.templates.payment-reminder-1d:}")
    private String paymentReminder1d;

    /**
     * Plantilla WhatsApp para recordatorios MANUALES (Fase B2, botón en perfil del inquilino).
     * Mismo contrato de 6 slots que las plantillas de recordatorio automáticas; lo único que
     * cambia es el body (genérico "pago pendiente" sin ventana de días). Queda vacío hasta que
     * Meta apruebe la plantilla {@code admindi_payment_reminder_manual_v1}; mientras tanto, el
     * servicio hará fallback a body libre (válido solo dentro de la ventana de 24h).
     */
    @Value("${twilio.templates.payment-reminder-manual:}")
    private String paymentReminderManual;

    @Value("${twilio.templates.transfer-confirmed:}")
    private String transferConfirmed;

    /** Comprobante CASH o SPEI en cola manual — aviso al dueño (validar por WhatsApp o portal). */
    @Value("${twilio.templates.cash-payment-pending-owner:}")
    private String cashPaymentPendingOwner;

    @Value("${twilio.templates.cash-payment-approved:}")
    private String cashPaymentApproved;

    @Value("${twilio.templates.cash-payment-rejected:}")
    private String cashPaymentRejected;

    @Value("${twilio.templates.payment-auto-validated:}")
    private String paymentAutoValidated;

    @Value("${twilio.templates.unpaid-digest:}")
    private String unpaidDigest;

    @Value("${twilio.templates.monthly-report:}")
    private String monthlyReport;

    /**
     * Plantilla WhatsApp para el link de activación de cuentas recién creadas
     * (staff NO-agente: property_admin / accountant / maintenance_provider).
     * Contrato esperado: 2 slots → {{1}}=nombre del user, {{2}}=URL de
     * activación. Mientras Meta no aprueba la plantilla
     * {@code admindi_account_activation_v1} queda vacío y el service cae a
     * body libre (solo entrega dentro de ventana 24h de WhatsApp). Email
     * siempre sale por {@link com.admindi.backend.service.EmailService}.
     */
    @Value("${twilio.templates.account-activation:}")
    private String accountActivation;

    /**
     * Plantilla WhatsApp para bienvenida de agentes inmobiliarios recién
     * creados (role REAL_ESTATE_AGENT). Plantilla aprobada: {@code
     * admindi_new_agentprovider} (20-abr-2026). Contrato esperado: 3 slots
     * → {{1}}=nombre, {{2}}=correo registrado, {{3}}=URL del portal. NO
     * incluye contraseña temporal (política: WhatsApp no es canal de
     * credenciales — las credenciales reales viajan por correo).
     */
    @Value("${twilio.templates.agent-welcome:}")
    private String agentWelcome;

    /**
     * Plantilla WhatsApp para bienvenida de proveedores de mantenimiento y
     * staff no-agente (PROPERTY_ADMIN / ACCOUNTANT / MAINTENANCE_PROVIDER).
     * Comparte el SID con {@link #agentWelcome} — el body Meta-aprobado de
     * {@code admindi_new_agentprovider} es genérico y cubre ambas rutas de
     * alta. Mismo contrato de 3 slots.
     */
    @Value("${twilio.templates.staff-welcome:}")
    private String staffWelcome;

    // ── Fase 2 — agentes inmobiliarios y de mantenimiento ─────────────────────────────
    // Las plantillas siguen vacías hasta que Meta apruebe los SIDs correspondientes;
    // mientras tanto el service cae a body libre (válido solo en ventana 24h de WhatsApp)
    // y el email lleva siempre el contenido completo. El {@code eventType} debe coincidir
    // con el emitido por el dispatcher en cada paso del flujo.
    @Value("${twilio.templates.property-vacancy-opened:}")
    private String propertyVacancyOpened;
    @Value("${twilio.templates.prospect-proposed:}")
    private String prospectProposed;
    @Value("${twilio.templates.prospect-reminder:}")
    private String prospectReminder;
    @Value("${twilio.templates.contract-signed-commission-due:}")
    private String contractSignedCommissionDue;
    @Value("${twilio.templates.commission-paid:}")
    private String commissionPaid;
    @Value("${twilio.templates.ticket-awaiting-owner-auth:}")
    private String ticketAwaitingOwnerAuth;
    @Value("${twilio.templates.maintenance-quote-uploaded:}")
    private String maintenanceQuoteUploaded;
    @Value("${twilio.templates.maintenance-payment-required:}")
    private String maintenancePaymentRequired;
    @Value("${twilio.templates.maintenance-ticket-rejected-by-owner:}")
    private String maintenanceTicketRejectedByOwner;
    @Value("${twilio.templates.maintenance-provider-rejected:}")
    private String maintenanceProviderRejected;
    @Value("${twilio.templates.maintenance-ticket-assigned:}")
    private String maintenanceTicketAssigned;
    @Value("${twilio.templates.vacancy-agent-assigned:}")
    private String vacancyAgentAssigned;
    @Value("${twilio.templates.vacancy-agent-needed:}")
    private String vacancyAgentNeeded;
    @Value("${twilio.templates.vacancy-agent-rejected:}")
    private String vacancyAgentRejected;
    @Value("${twilio.templates.vacancy-agent-timeout:}")
    private String vacancyAgentTimeout;
    @Value("${twilio.templates.vacancy-photos-uploaded:}")
    private String vacancyPhotosUploaded;
    @Value("${twilio.templates.vacancy-chain-exhausted:}")
    private String vacancyChainExhausted;
    @Value("${twilio.templates.prospect-owner-accepted:}")
    private String prospectOwnerAccepted;
    @Value("${twilio.templates.prospect-owner-rejected:}")
    private String prospectOwnerRejected;
    @Value("${twilio.templates.commission-spei-pending-manual:}")
    private String commissionSpeiPendingManual;
    @Value("${twilio.templates.commission-approved:}")
    private String commissionApproved;
    @Value("${twilio.templates.agent-bank-account-validated:}")
    private String agentBankAccountValidated;
    @Value("${twilio.templates.agent-bank-account-failed:}")
    private String agentBankAccountFailed;

    private Map<String, String> eventTypeToSid = Collections.emptyMap();

    /**
     * Arma la tabla interna en {@code @PostConstruct}-safe (Spring llama getters/@Value
     * antes de usarnos). Se ejecuta la primera vez que alguien pide un SID.
     */
    private synchronized void ensureInitialized() {
        if (!eventTypeToSid.isEmpty()) return;
        Map<String, String> m = new LinkedHashMap<>();
        putIfPresent(m, "OWNER_WELCOME", ownerWelcome);
        putIfPresent(m, "TENANT_WELCOME", tenantWelcome);
        putIfPresent(m, "OWNER_PROFILE_UPDATED", ownerProfileUpdated);
        putIfPresent(m, "TENANT_PAYMENT_REMINDER_5D", paymentReminder5d);
        putIfPresent(m, "TENANT_PAYMENT_REMINDER_3D", paymentReminder3d);
        putIfPresent(m, "TENANT_PAYMENT_REMINDER_2D", paymentReminder2d);
        putIfPresent(m, "TENANT_PAYMENT_REMINDER_1D", paymentReminder1d);
        putIfPresent(m, "MANUAL_PAYMENT_REMINDER", paymentReminderManual);
        putIfPresent(m, "TRANSFER_CONFIRMED", transferConfirmed);
        putIfPresent(m, "CASH_PAYMENT_PENDING_OWNER", cashPaymentPendingOwner);
        putIfPresent(m, "CASH_PAYMENT_APPROVED", cashPaymentApproved);
        putIfPresent(m, "CASH_PAYMENT_REJECTED", cashPaymentRejected);
        putIfPresent(m, "PAYMENT_AUTO_VALIDATED", paymentAutoValidated);
        putIfPresent(m, "OWNER_UNPAID_TENANTS_DIGEST", unpaidDigest);
        putIfPresent(m, "OWNER_MONTHLY_REPORT", monthlyReport);
        putIfPresent(m, "ACCOUNT_ACTIVATION", accountActivation);
        putIfPresent(m, "AGENT_WELCOME", agentWelcome);
        putIfPresent(m, "STAFF_WELCOME", staffWelcome);
        putIfPresent(m, "PROPERTY_VACANCY_OPENED", propertyVacancyOpened);
        putIfPresent(m, "PROSPECT_PROPOSED", prospectProposed);
        putIfPresent(m, "PROSPECT_REMINDER", prospectReminder);
        putIfPresent(m, "CONTRACT_SIGNED_COMMISSION_DUE", contractSignedCommissionDue);
        putIfPresent(m, "COMMISSION_PAID", commissionPaid);
        putIfPresent(m, "MAINTENANCE_TICKET_AWAITING_OWNER_AUTH", ticketAwaitingOwnerAuth);
        putIfPresent(m, "MAINTENANCE_QUOTE_UPLOADED", maintenanceQuoteUploaded);
        putIfPresent(m, "MAINTENANCE_PAYMENT_REQUIRED", maintenancePaymentRequired);
        putIfPresent(m, "MAINTENANCE_TICKET_REJECTED_BY_OWNER", maintenanceTicketRejectedByOwner);
        putIfPresent(m, "MAINTENANCE_PROVIDER_REJECTED", maintenanceProviderRejected);
        putIfPresent(m, "MAINTENANCE_TICKET_ASSIGNED", maintenanceTicketAssigned);
        putIfPresent(m, "VACANCY_AGENT_ASSIGNED", vacancyAgentAssigned);
        putIfPresent(m, "VACANCY_AGENT_NEEDED", vacancyAgentNeeded);
        putIfPresent(m, "VACANCY_AGENT_REJECTED", vacancyAgentRejected);
        putIfPresent(m, "VACANCY_AGENT_TIMEOUT", vacancyAgentTimeout);
        putIfPresent(m, "VACANCY_PHOTOS_UPLOADED", vacancyPhotosUploaded);
        putIfPresent(m, "VACANCY_CHAIN_EXHAUSTED", vacancyChainExhausted);
        putIfPresent(m, "PROSPECT_OWNER_ACCEPTED", prospectOwnerAccepted);
        putIfPresent(m, "PROSPECT_OWNER_REJECTED", prospectOwnerRejected);
        putIfPresent(m, "COMMISSION_SPEI_PENDING_MANUAL", commissionSpeiPendingManual);
        putIfPresent(m, "COMMISSION_APPROVED", commissionApproved);
        putIfPresent(m, "AGENT_BANK_ACCOUNT_VALIDATED", agentBankAccountValidated);
        putIfPresent(m, "AGENT_BANK_ACCOUNT_FAILED", agentBankAccountFailed);
        this.eventTypeToSid = Collections.unmodifiableMap(m);

        if (templatesEnabled) {
            logger.info("[TWILIO-TPL] Template mapping initialized: enabled=true, configured={} event types with SID",
                    m.size());
        } else {
            logger.info("[TWILIO-TPL] Templates disabled (twilio.templates.enabled=false); fallback a body libre dentro de ventana 24h");
        }
    }

    private static void putIfPresent(Map<String, String> m, String key, String sid) {
        if (sid != null && !sid.isBlank() && sid.trim().startsWith("HX")) {
            m.put(key, sid.trim());
        }
    }

    /**
     * true si la feature global está activa Y el eventType tiene un SID válido (HX…).
     * Si devuelve false, el service NO debe intentar usar contentSid para este evento.
     */
    public boolean hasTemplateForEvent(String eventType) {
        if (!templatesEnabled) return false;
        if (eventType == null) return false;
        ensureInitialized();
        return eventTypeToSid.containsKey(eventType);
    }

    /**
     * Devuelve el Content SID para el eventType, o null si no hay mapping o templates
     * están deshabilitados. Los callers deben checar {@link #hasTemplateForEvent(String)}.
     */
    public String getContentSid(String eventType) {
        if (!hasTemplateForEvent(eventType)) return null;
        return eventTypeToSid.get(eventType);
    }

    public boolean isTemplatesEnabled() {
        return templatesEnabled;
    }

    /**
     * Snapshot inmutable {@code eventType → SID redactado} de todas las plantillas
     * registradas. Pensado para el panel admin de smoke test — NO devuelve el SID
     * completo (defensa contra exposición accidental en logs o UI abierta).
     */
    public Map<String, String> getRedactedSnapshot() {
        ensureInitialized();
        Map<String, String> out = new LinkedHashMap<>();
        eventTypeToSid.forEach((k, v) -> out.put(k, redactSid(v)));
        return Collections.unmodifiableMap(out);
    }

    /**
     * Lista canónica de todos los {@code eventType} soportados por el registry,
     * tengan SID configurado o no. Se usa en el panel admin para exponer los 35
     * slots esperados incluso cuando alguno todavía no tiene SID en algún entorno.
     *
     * <p>Al 17/04/2026 hay 34 plantillas Meta-aprobadas y cargadas, y 1 slot
     * pendiente (ACCOUNT_ACTIVATION) que seguirá usando body libre dentro de la
     * ventana de 24h hasta que Meta apruebe {@code admindi_account_activation_v1}.
     */
    public List<String> getKnownEventTypes() {
        return List.of(
                "OWNER_WELCOME", "TENANT_WELCOME", "OWNER_PROFILE_UPDATED",
                "TENANT_PAYMENT_REMINDER_5D", "TENANT_PAYMENT_REMINDER_3D",
                "TENANT_PAYMENT_REMINDER_2D", "TENANT_PAYMENT_REMINDER_1D",
                "MANUAL_PAYMENT_REMINDER", "TRANSFER_CONFIRMED",
                "OWNER_UNPAID_TENANTS_DIGEST", "OWNER_MONTHLY_REPORT",
                "ACCOUNT_ACTIVATION",
                "AGENT_WELCOME",
                "STAFF_WELCOME",
                // Fase 2 — agentes
                "PROPERTY_VACANCY_OPENED", "PROSPECT_PROPOSED", "PROSPECT_REMINDER",
                "CONTRACT_SIGNED_COMMISSION_DUE", "COMMISSION_PAID",
                "MAINTENANCE_TICKET_AWAITING_OWNER_AUTH",
                "MAINTENANCE_QUOTE_UPLOADED", "MAINTENANCE_PAYMENT_REQUIRED",
                "MAINTENANCE_TICKET_REJECTED_BY_OWNER", "MAINTENANCE_PROVIDER_REJECTED",
                "MAINTENANCE_TICKET_ASSIGNED",
                "VACANCY_AGENT_ASSIGNED", "VACANCY_AGENT_NEEDED",
                "VACANCY_AGENT_REJECTED", "VACANCY_AGENT_TIMEOUT",
                "VACANCY_PHOTOS_UPLOADED", "VACANCY_CHAIN_EXHAUSTED",
                "PROSPECT_OWNER_ACCEPTED", "PROSPECT_OWNER_REJECTED",
                "COMMISSION_SPEI_PENDING_MANUAL", "COMMISSION_APPROVED",
                "AGENT_BANK_ACCOUNT_VALIDATED", "AGENT_BANK_ACCOUNT_FAILED"
        );
    }

    /** Redacta un SID para logs (no es secreto, pero evitamos exponerlo completo). */
    public static String redactSid(String sid) {
        if (sid == null || sid.length() < 6) return "(n/d)";
        return sid.substring(0, 4) + "…" + sid.substring(sid.length() - 3);
    }
}
