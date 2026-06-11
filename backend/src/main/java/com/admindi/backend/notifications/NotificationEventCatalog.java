package com.admindi.backend.notifications;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catálogo de eventos VISIBLES al usuario en la UI de preferencias (Etapa 1+).
 *
 * Reglas:
 *  - OWNER_CREATED NO aparece (bootstrap interno / auditoría).
 *  - Sólo eventos con utilidad operativa para el destinatario.
 *  - Cada evento declara explícitamente su {@code audience}: los roles que
 *    pueden recibirlo como destinatarios. La UI filtra localmente usando ese
 *    campo para evitar ruido (ej: un TENANT no debe ver OWNER_MONTHLY_REPORT).
 *  - SUPER_ADMIN se incluye como audiencia en TODOS los eventos visibles para
 *    poder auditar y configurar sus propias preferencias si, como usuario,
 *    llega a ser destinatario de algún evento interno.
 *
 * Si un evento interno llega al dispatcher y NO está en este catálogo, el
 * dispatcher igual crea el IN_APP obligatorio cuando aplica, pero los canales
 * EMAIL/WHATSAPP quedan en default salvo decisión explícita del dispatcher.
 */
public final class NotificationEventCatalog {

    /** Audiencias canónicas (roles del dominio que pueden recibir eventos). */
    public static final String AUD_OWNER = "OWNER";
    public static final String AUD_TENANT = "TENANT";
    public static final String AUD_PROPERTY_ADMIN = "PROPERTY_ADMIN";
    public static final String AUD_ACCOUNTANT = "ACCOUNTANT";
    public static final String AUD_REAL_ESTATE_AGENT = "REAL_ESTATE_AGENT";
    public static final String AUD_MAINTENANCE_PROVIDER = "MAINTENANCE_PROVIDER";
    public static final String AUD_SUPER_ADMIN = "SUPER_ADMIN";

    /** Preset reutilizable: toda la cadena de gestión de portfolio del dueño. */
    private static final Set<String> PORTFOLIO_ADMIN = Set.of(
            AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_ACCOUNTANT, AUD_SUPER_ADMIN);

    /** Preset: solo decisiones operativas del dueño / su staff (sin contadores). */
    private static final Set<String> OWNER_OPS = Set.of(
            AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_SUPER_ADMIN);

    public record EventSpec(String eventType, String label, String group, Set<String> audience) {}

    private static final List<EventSpec> VISIBLE_EVENTS = List.of(
        // Bienvenida / cuenta
        new EventSpec("OWNER_WELCOME", "Bienvenida al crear tu cuenta de dueño", "Cuenta",
                Set.of(AUD_OWNER, AUD_SUPER_ADMIN)),
        new EventSpec("TENANT_WELCOME", "Bienvenida al crear expediente de arrendatario", "Cuenta",
                Set.of(AUD_TENANT, AUD_SUPER_ADMIN)),
        // Alta de agente inmobiliario — mismo contrato que OWNER_WELCOME: el
        // correo lleva las credenciales reales (usuario + contraseña temporal
        // con mustChangePassword=true, equivalente a contraseña de un solo uso
        // porque queda invalidada al primer login) y el WhatsApp es un teaser
        // via plantilla admindi_agent_welcome_v1 que avisa "revisa tu correo".
        new EventSpec("AGENT_WELCOME", "Bienvenida al crear tu cuenta de agente", "Cuenta",
                Set.of(AUD_REAL_ESTATE_AGENT, AUD_SUPER_ADMIN)),
        // Alta de staff / proveedor de mantenimiento — correo con credenciales
        // reales (usuario + contraseña temporal con mustChangePassword=true).
        // Sin plantilla WhatsApp aprobada: si el destinatario ya tenía
        // conversación activa en las últimas 24h recibe body libre; fuera de
        // esa ventana solo sale el correo.
        new EventSpec("STAFF_WELCOME", "Bienvenida al crear tu cuenta de staff / proveedor", "Cuenta",
                Set.of(AUD_PROPERTY_ADMIN, AUD_ACCOUNTANT,
                        AUD_MAINTENANCE_PROVIDER, AUD_SUPER_ADMIN)),
        // ACCOUNT_ACTIVATION queda registrado en el catálogo sólo para permitir
        // la consumición de tokens legacy ya emitidos (flujo descontinuado). El
        // alta de cuentas nuevas ya NO dispara este evento — todos los roles no
        // dueño/no tenant reciben su contraseña temporal por correo (AGENT_WELCOME
        // o STAFF_WELCOME según rol). Se mantiene en el catálogo para que
        // cualquier token pendiente emitido antes de esta migración pueda aún
        // consumirse hasta su TTL natural; se puede retirar por completo una
        // vez que se verifique que no hay tokens PENDING en user_activation_tokens.
        new EventSpec("ACCOUNT_ACTIVATION", "Activación de cuenta (legacy)", "Cuenta",
                Set.of(AUD_PROPERTY_ADMIN, AUD_ACCOUNTANT, AUD_REAL_ESTATE_AGENT,
                        AUD_MAINTENANCE_PROVIDER, AUD_SUPER_ADMIN)),
        // Recuperación de cuenta disparada por SUPER_ADMIN u OWNER desde el
        // panel administrativo. Entrega al destinatario (usuario recuperado)
        // la información del reset: nueva contraseña temporal (si aplica),
        // estado del MFA, motivo y cantidad de sesiones revocadas. forceAllChannels
        // al despachar porque es operativamente crítico — el user necesita
        // enterarse aunque tenga notificaciones apagadas.
        new EventSpec("ACCOUNT_RECOVERED", "Recuperación de cuenta ejecutada por administrador", "Cuenta",
                Set.of(AUD_OWNER, AUD_TENANT, AUD_PROPERTY_ADMIN, AUD_ACCOUNTANT,
                        AUD_REAL_ESTATE_AGENT, AUD_MAINTENANCE_PROVIDER, AUD_SUPER_ADMIN)),
        new EventSpec("OWNER_PROFILE_UPDATED", "Actualización de perfil del dueño (contacto/CLABE)", "Cuenta",
                Set.of(AUD_OWNER, AUD_SUPER_ADMIN)),
        new EventSpec("OWNER_CONTACT_UPDATED", "Actualización de datos de contacto", "Cuenta",
                Set.of(AUD_OWNER, AUD_SUPER_ADMIN)),

        // Recordatorios de pago para arrendatarios
        new EventSpec("TENANT_PAYMENT_REMINDER_5D", "Recordatorio 5 días antes del pago", "Recordatorios",
                Set.of(AUD_TENANT, AUD_SUPER_ADMIN)),
        new EventSpec("TENANT_PAYMENT_REMINDER_3D", "Recordatorio 3 días antes del pago", "Recordatorios",
                Set.of(AUD_TENANT, AUD_SUPER_ADMIN)),
        new EventSpec("TENANT_PAYMENT_REMINDER_2D", "Recordatorio 2 días antes del pago", "Recordatorios",
                Set.of(AUD_TENANT, AUD_SUPER_ADMIN)),
        new EventSpec("TENANT_PAYMENT_REMINDER_1D", "Recordatorio 1 día antes del pago", "Recordatorios",
                Set.of(AUD_TENANT, AUD_SUPER_ADMIN)),

        // Ciclo de inmueble
        new EventSpec("PROPERTY_CREATED", "Alta de inmueble", "Inmuebles", PORTFOLIO_ADMIN),
        new EventSpec("PROPERTY_UPDATED", "Actualización de inmueble", "Inmuebles", PORTFOLIO_ADMIN),
        new EventSpec("PROPERTY_DELETE_REQUESTED", "Solicitud de eliminación de inmueble", "Inmuebles", OWNER_OPS),
        new EventSpec("PROPERTY_DELETE_APPROVED", "Eliminación de inmueble aprobada", "Inmuebles", OWNER_OPS),
        new EventSpec("PROPERTY_DELETE_REJECTED", "Eliminación de inmueble rechazada", "Inmuebles", OWNER_OPS),
        new EventSpec("PROPERTY_FILE_DELETE_REQUESTED", "Solicitud de eliminación de archivo", "Inmuebles", OWNER_OPS),
        new EventSpec("PROPERTY_FILE_DELETE_APPROVED", "Eliminación de archivo aprobada", "Inmuebles", OWNER_OPS),
        new EventSpec("PROPERTY_FILE_DELETE_REJECTED", "Eliminación de archivo rechazada", "Inmuebles", OWNER_OPS),
        new EventSpec("PROPERTY_FILE_DELETED", "Archivo del inmueble eliminado", "Inmuebles", OWNER_OPS),

        // Inquilinos / expedientes
        new EventSpec("TENANT_CREATED", "Nuevo expediente de inquilino", "Inquilinos", PORTFOLIO_ADMIN),
        new EventSpec("TENANT_UPDATED", "Actualización de expediente de inquilino", "Inquilinos", PORTFOLIO_ADMIN),
        new EventSpec("TENANT_EXPEDIENTE_ARCHIVED", "Expediente archivado (baja)", "Inquilinos", OWNER_OPS),
        new EventSpec("TENANT_ARCHIVE_REQUESTED", "Solicitud de archivo de expediente", "Inquilinos", OWNER_OPS),
        new EventSpec("TENANT_ARCHIVE_APPROVED", "Archivo de expediente aprobado", "Inquilinos", OWNER_OPS),
        new EventSpec("TENANT_ARCHIVE_REJECTED", "Archivo de expediente rechazado", "Inquilinos", OWNER_OPS),

        // Contratos / leases (afectan a dueño, staff y al propio inquilino)
        new EventSpec("LEASE_TERMINATE_REQUESTED", "Solicitud de terminación de contrato", "Contratos",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_TENANT, AUD_SUPER_ADMIN)),
        new EventSpec("LEASE_TERMINATE_APPROVED", "Terminación de contrato aprobada", "Contratos",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_TENANT, AUD_SUPER_ADMIN)),
        new EventSpec("LEASE_TERMINATE_REJECTED", "Terminación de contrato rechazada", "Contratos",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_TENANT, AUD_SUPER_ADMIN)),

        // Pagos / conciliación
        new EventSpec("PAYMENT_AUTO_VALIDATED", "Pago validado automáticamente", "Pagos",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_ACCOUNTANT, AUD_TENANT, AUD_SUPER_ADMIN)),
        new EventSpec("PAYMENT_MANUAL_OVERRIDE", "Pago con override manual", "Pagos", PORTFOLIO_ADMIN),
        new EventSpec("PAYMENT_CEP_REJECTED", "CEP rechazó comprobante", "Pagos",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_ACCOUNTANT, AUD_TENANT, AUD_SUPER_ADMIN)),
        new EventSpec("TRANSFER_PROOF_INCOMPLETE", "Comprobante SPEI incompleto", "Pagos",
                Set.of(AUD_TENANT, AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_SUPER_ADMIN)),
        new EventSpec("TRANSFER_CONFIRMED", "Transferencia SPEI confirmada (para el dueño)", "Pagos",
                PORTFOLIO_ADMIN),

        // V57 — Flujo de pago en efectivo: inquilino sube, dueño valida
        new EventSpec("CASH_PAYMENT_PENDING_OWNER", "Comprobante de pago en efectivo por validar", "Pagos",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_ACCOUNTANT, AUD_SUPER_ADMIN)),
        new EventSpec("CASH_PAYMENT_APPROVED", "Pago en efectivo aprobado", "Pagos",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_ACCOUNTANT, AUD_TENANT, AUD_SUPER_ADMIN)),
        new EventSpec("CASH_PAYMENT_REJECTED", "Pago en efectivo rechazado", "Pagos",
                Set.of(AUD_OWNER, AUD_TENANT, AUD_SUPER_ADMIN)),
        new EventSpec("CASH_PAYMENT_EXPIRED", "Comprobante en efectivo expirado sin validación", "Pagos",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_TENANT, AUD_SUPER_ADMIN)),
        new EventSpec("SPEI_MAX_ATTEMPTS_REACHED", "Máximo de intentos SPEI alcanzado — captura manual", "Pagos",
                Set.of(AUD_TENANT, AUD_OWNER, AUD_SUPER_ADMIN)),

        // Resúmenes operativos para el dueño (Bloque B)
        new EventSpec("OWNER_UNPAID_TENANTS_DIGEST", "Resumen diario de inquilinos con pago vencido", "Resúmenes",
                Set.of(AUD_OWNER, AUD_SUPER_ADMIN)),
        new EventSpec("OWNER_MONTHLY_REPORT", "Reporte mensual del portafolio", "Resúmenes",
                Set.of(AUD_OWNER, AUD_SUPER_ADMIN)),

        // Mantenimiento
        new EventSpec("MAINTENANCE_TICKET_ASSIGNED", "Mantenimiento asignado a proveedor", "Mantenimiento",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_MAINTENANCE_PROVIDER, AUD_SUPER_ADMIN)),
        new EventSpec("MAINTENANCE_PROVIDER_NEEDED", "Mantenimiento sin proveedor", "Mantenimiento", OWNER_OPS),
        new EventSpec("MAINTENANCE_QUOTE_APPROVED", "Cotización de mantenimiento aprobada", "Mantenimiento",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_MAINTENANCE_PROVIDER, AUD_SUPER_ADMIN)),
        new EventSpec("MAINTENANCE_QUOTE_REJECTED", "Cotización de mantenimiento rechazada", "Mantenimiento",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_MAINTENANCE_PROVIDER, AUD_SUPER_ADMIN)),

        // "Poner en renta" — staff solicita al dueño arrancar la cadena de agentes
        new EventSpec("VACANCY_START_REQUESTED", "Staff pidió poner inmueble en renta — requiere tu autorización", "Comercial", OWNER_OPS),
        new EventSpec("VACANCY_START_APPROVED", "Solicitud de puesta en renta aprobada", "Comercial", OWNER_OPS),
        new EventSpec("VACANCY_START_REJECTED", "Solicitud de puesta en renta rechazada", "Comercial", OWNER_OPS),

        // Comercial / vacancia (legacy)
        new EventSpec("VACANCY_AGENT_ASSIGNED", "Vacancia con agente", "Comercial",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_REAL_ESTATE_AGENT, AUD_SUPER_ADMIN)),
        new EventSpec("VACANCY_AGENT_NEEDED", "Vacancia sin agente", "Comercial", OWNER_OPS),
        new EventSpec("COMMERCIAL_ACTIVITY_LOGGED", "Actividad comercial registrada", "Comercial",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_REAL_ESTATE_AGENT, AUD_SUPER_ADMIN)),
        new EventSpec("COMMISSION_APPROVED", "Comisión comercial aprobada", "Comercial",
                Set.of(AUD_OWNER, AUD_REAL_ESTATE_AGENT, AUD_SUPER_ADMIN)),

        // ── Fase 2: flujo unificado de agentes inmobiliarios y de mantenimiento ──
        // Vacancia — ciclo completo del agente inmobiliario
        new EventSpec("PROPERTY_VACANCY_OPENED", "Inmueble desocupado — invitación al agente", "Agentes",
                Set.of(AUD_OWNER, AUD_REAL_ESTATE_AGENT, AUD_SUPER_ADMIN)),
        new EventSpec("VACANCY_AGENT_REJECTED", "Agente rechazó la vacancia — cadena avanza", "Agentes",
                Set.of(AUD_OWNER, AUD_SUPER_ADMIN)),
        new EventSpec("VACANCY_CHAIN_EXHAUSTED", "Ningún agente aceptó la vacancia", "Agentes", OWNER_OPS),
        new EventSpec("VACANCY_AGENT_TIMEOUT", "Agente no respondió en 72h — cadena avanza", "Agentes",
                Set.of(AUD_OWNER, AUD_REAL_ESTATE_AGENT, AUD_SUPER_ADMIN)),
        new EventSpec("VACANCY_PHOTOS_UPLOADED", "Agente subió fotos del inmueble disponible", "Agentes",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_SUPER_ADMIN)),

        // Prospecto de arrendatario
        new EventSpec("PROSPECT_PROPOSED", "Prospecto de arrendatario propuesto por el agente", "Agentes",
                Set.of(AUD_OWNER, AUD_SUPER_ADMIN)),
        new EventSpec("PROSPECT_REMINDER", "Recordatorio al dueño sobre prospecto pendiente", "Agentes",
                Set.of(AUD_OWNER, AUD_SUPER_ADMIN)),
        new EventSpec("PROSPECT_OWNER_ACCEPTED", "Dueño aceptó prospecto — coordinar firma", "Agentes",
                Set.of(AUD_REAL_ESTATE_AGENT, AUD_OWNER, AUD_SUPER_ADMIN)),
        new EventSpec("PROSPECT_OWNER_REJECTED", "Dueño rechazó prospecto — buscar otro", "Agentes",
                Set.of(AUD_REAL_ESTATE_AGENT, AUD_OWNER, AUD_SUPER_ADMIN)),

        // Cierre de contrato y comisión inmobiliaria
        new EventSpec("CONTRACT_SIGNED_COMMISSION_DUE", "Contrato firmado — comisión pendiente de pago", "Agentes",
                Set.of(AUD_OWNER, AUD_REAL_ESTATE_AGENT, AUD_ACCOUNTANT, AUD_SUPER_ADMIN)),
        new EventSpec("COMMISSION_SPEI_PENDING_MANUAL", "SPEI no validado — agente debe confirmar manualmente", "Agentes",
                Set.of(AUD_REAL_ESTATE_AGENT, AUD_OWNER, AUD_SUPER_ADMIN)),
        new EventSpec("COMMISSION_PAID", "Comisión del agente liquidada", "Agentes",
                Set.of(AUD_OWNER, AUD_REAL_ESTATE_AGENT, AUD_ACCOUNTANT, AUD_SUPER_ADMIN)),

        // Mantenimiento — autorización del dueño y pago
        new EventSpec("MAINTENANCE_TICKET_AWAITING_OWNER_AUTH", "Ticket espera autorización del dueño", "Mantenimiento",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_TENANT, AUD_SUPER_ADMIN)),
        new EventSpec("MAINTENANCE_TICKET_REJECTED_BY_OWNER", "Dueño rechazó el ticket de mantenimiento", "Mantenimiento",
                Set.of(AUD_TENANT, AUD_OWNER, AUD_SUPER_ADMIN)),
        new EventSpec("MAINTENANCE_PROVIDER_REJECTED", "Proveedor rechazó el ticket — cadena avanza", "Mantenimiento",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_MAINTENANCE_PROVIDER, AUD_SUPER_ADMIN)),
        new EventSpec("MAINTENANCE_QUOTE_UPLOADED", "Proveedor subió cotización / presupuesto", "Mantenimiento",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_SUPER_ADMIN)),
        new EventSpec("MAINTENANCE_PAYMENT_REQUIRED", "Pago pendiente al proveedor de mantenimiento", "Mantenimiento",
                Set.of(AUD_OWNER, AUD_PROPERTY_ADMIN, AUD_ACCOUNTANT, AUD_SUPER_ADMIN)),

        // CLABE del agente
        new EventSpec("AGENT_BANK_ACCOUNT_VALIDATED", "CLABE de agente validada por Banxico", "Agentes",
                Set.of(AUD_REAL_ESTATE_AGENT, AUD_MAINTENANCE_PROVIDER, AUD_SUPER_ADMIN)),
        new EventSpec("AGENT_BANK_ACCOUNT_FAILED", "CLABE de agente no pudo ser validada", "Agentes",
                Set.of(AUD_REAL_ESTATE_AGENT, AUD_MAINTENANCE_PROVIDER, AUD_SUPER_ADMIN))
    );

    /** Eventos internos/auditoría que NUNCA deben aparecer en preferencias visibles. */
    private static final Set<String> HIDDEN = Set.of(
        "OWNER_CREATED",
        "OWNER_PURGED",
        "OWNER_DEACTIVATED",
        "OWNER_ROUTING_UPDATED"
    );

    private NotificationEventCatalog() {}

    public static List<EventSpec> visibleEvents() {
        return VISIBLE_EVENTS;
    }

    public static Set<String> visibleEventTypes() {
        return Set.copyOf(VISIBLE_EVENTS.stream().map(EventSpec::eventType).toList());
    }

    /** true si el evento NO debe aparecer en la matriz de preferencias del usuario. */
    public static boolean isHiddenFromUser(String eventType) {
        if (eventType == null) return true;
        return HIDDEN.contains(eventType.trim().toUpperCase());
    }

    /**
     * Serialización para la API pública del catálogo. Cada entrada incluye
     * {@code audience} como lista de roles canónicos. La UI filtra localmente
     * por el rol del usuario autenticado para no inflar la matriz con eventos
     * irrelevantes.
     */
    public static List<Map<String, Object>> catalogAsMaps() {
        return VISIBLE_EVENTS.stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("eventType", e.eventType());
                    m.put("label", e.label());
                    m.put("group", e.group());
                    m.put("audience", List.copyOf(e.audience()));
                    return m;
                })
                .toList();
    }
}
