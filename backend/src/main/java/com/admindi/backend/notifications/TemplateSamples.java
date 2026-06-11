package com.admindi.backend.notifications;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Datos de ejemplo (sample payloads) para cada {@code eventType} con plantilla
 * WhatsApp aprobada por Meta. Se usan exclusivamente desde el panel de
 * <strong>smoke test</strong> (SUPER_ADMIN) para:
 *
 * <ul>
 *   <li>Previsualizar con valores realistas el cuerpo final que Twilio renderizará
 *       antes de activar una plantilla en producción.</li>
 *   <li>Disparar un envío real al propio SUPER_ADMIN como prueba end-to-end
 *       (Twilio responds OK → plantilla de verdad aprobada y configurada).</li>
 * </ul>
 *
 * <h3>Contrato</h3>
 * <p>Cada sample respeta <strong>exactamente</strong> el mismo orden y cantidad
 * de slots que la plantilla Meta-aprobada, tal como están documentados en
 * {@code docs/WHATSAPP_TEMPLATES.md}. Si la plantilla cambia (v2/v3), actualizar aquí
 * <em>primero</em> antes de resubir a Meta.</p>
 *
 * <h3>Datos ficticios, nunca reales</h3>
 * <p>Los nombres, CLABEs y claves de rastreo son ficticios; ningún dato es
 * reutilizable en producción. Si en el futuro se quieren samples parametrizados
 * (por propiedad real, etc.), pasar de {@code Map<eventType, Map>} a una factory
 * que consulte la DB — por ahora, mock estático es suficiente.</p>
 */
public final class TemplateSamples {

    /** Nombres ficticios reutilizados entre plantillas. */
    private static final String SAMPLE_OWNER_NAME   = "Israel";
    private static final String SAMPLE_TENANT_NAME  = "David";
    private static final String SAMPLE_AGENT_NAME   = "Laura Martínez";
    private static final String SAMPLE_PROVIDER_NAME = "Plomería López";
    private static final String SAMPLE_PROSPECT_NAME = "Jorge Ramírez";
    private static final String SAMPLE_PROPERTY_NAME = "Depto Reforma 201";
    private static final String SAMPLE_PORTAL_OWNER  = "https://app.admindi.mx/owner";
    private static final String SAMPLE_PORTAL_TENANT = "https://app.admindi.mx/tenant";
    private static final String SAMPLE_PORTAL_AGENT  = "https://app.admindi.mx/agent";
    private static final String SAMPLE_PORTAL_PROVIDER = "https://app.admindi.mx/provider";

    private TemplateSamples() { /* utility */ }

    /**
     * Devuelve un {@code LinkedHashMap} con los slots esperados para el eventType.
     * Si el eventType no es conocido, devuelve un map vacío (el dispatcher caerá
     * a body libre sin romper).
     */
    public static Map<String, String> sampleFor(String eventType) {
        Map<String, String> v = new LinkedHashMap<>();
        if (eventType == null) return v;
        switch (eventType) {
            // ── Fase 1 (Welcome / Recordatorios / Reportes) ──────────────────
            case "OWNER_WELCOME":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", "israel@ejemplo.com");
                v.put("3", "https://app.admindi.mx");
                break;
            case "TENANT_WELCOME":
                v.put("1", SAMPLE_TENANT_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", "david@ejemplo.com");
                v.put("4", "https://app.admindi.mx");
                break;
            case "OWNER_PROFILE_UPDATED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", "17/04/2026 10:15");
                v.put("3", "CLABE, teléfono de contacto");
                v.put("4", "https://app.admindi.mx");
                break;
            case "TENANT_PAYMENT_REMINDER_5D":
            case "TENANT_PAYMENT_REMINDER_3D":
            case "TENANT_PAYMENT_REMINDER_2D":
            case "TENANT_PAYMENT_REMINDER_1D":
            case "MANUAL_PAYMENT_REMINDER":
                v.put("1", SAMPLE_TENANT_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", "18,500.00");
                v.put("4", "22/04/2026");
                v.put("5", "BBVA");
                v.put("6", "012180001234567890");
                break;
            case "TRANSFER_CONFIRMED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", "17/04/2026 11:42");
                v.put("3", SAMPLE_PROPERTY_NAME);
                v.put("4", "David Rodríguez");
                v.put("5", "18,500.00");
                v.put("6", "MBAN01202604170000000123456789");
                v.put("7", "https://app.admindi.mx");
                break;
            case "CASH_PAYMENT_PENDING_OWNER":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", "David Rodríguez");
                v.put("3", SAMPLE_PROPERTY_NAME);
                v.put("4", "2026-04");
                v.put("5", "EFECTIVO");
                v.put("6", "18,500.00");
                v.put("7", "120");
                break;
            case "CASH_PAYMENT_APPROVED":
                v.put("1", SAMPLE_TENANT_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", "2026-04");
                v.put("4", "18,500.00");
                break;
            case "CASH_PAYMENT_REJECTED":
                v.put("1", SAMPLE_TENANT_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", "2026-04");
                v.put("4", "El monto no coincide con lo acordado");
                break;
            case "PAYMENT_AUTO_VALIDATED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", "David Rodríguez");
                v.put("4", "2026-04");
                v.put("5", "18,500.00");
                v.put("6", "MBAN01202604170000000123456789");
                break;
            case "OWNER_UNPAID_TENANTS_DIGEST":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", "3");
                v.put("3", "42,800.00");
                v.put("4", "David R. (Reforma 201), María L. (Insurgentes 45), Carlos G. (Tlalpan 120)");
                v.put("5", "https://app.admindi.mx/owner/delinquency");
                break;
            case "OWNER_MONTHLY_REPORT":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", "Marzo 2026");
                v.put("3", "87,500.00");
                v.put("4", "18,500.00");
                v.put("5", "12,340.00");
                v.put("6", "4 de 5 unidades ocupadas (80%)");
                v.put("7", "https://app.admindi.mx/owner/reports");
                break;
            case "ACCOUNT_ACTIVATION":
                v.put("1", SAMPLE_AGENT_NAME);
                v.put("2", "https://app.admindi.mx/activate?token=demo");
                break;
            case "AGENT_WELCOME":
            case "STAFF_WELCOME":
                v.put("1", SAMPLE_AGENT_NAME);
                v.put("2", "laura@ejemplo.com");
                v.put("3", "https://app.admindi.mx");
                break;

            // ── Fase 2 — Mantenimiento ───────────────────────────────────────
            case "MAINTENANCE_TICKET_AWAITING_OWNER_AUTH":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", "Fuga en tubería del baño");
                v.put("4", "ALTA");
                v.put("5", SAMPLE_PORTAL_OWNER);
                break;
            case "MAINTENANCE_TICKET_REJECTED_BY_OWNER":
                v.put("1", SAMPLE_TENANT_NAME);
                v.put("2", "Pintura de sala");
                v.put("3", SAMPLE_PROPERTY_NAME);
                v.put("4", "Fuera de cobertura del contrato");
                v.put("5", SAMPLE_PORTAL_TENANT);
                break;
            case "MAINTENANCE_PROVIDER_REJECTED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_PROVIDER_NAME);
                v.put("3", "Fuga en baño principal");
                v.put("4", SAMPLE_PROPERTY_NAME);
                v.put("5", "Notificamos al siguiente proveedor de tu lista: Mantenimientos Díaz.");
                v.put("6", SAMPLE_PORTAL_OWNER);
                break;
            case "MAINTENANCE_TICKET_ASSIGNED":
                v.put("1", SAMPLE_PROVIDER_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", "Fuga en baño");
                v.put("4", "ALTA");
                v.put("5", SAMPLE_PORTAL_PROVIDER);
                break;
            case "MAINTENANCE_QUOTE_UPLOADED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_PROVIDER_NAME);
                v.put("3", "Fuga en baño");
                v.put("4", SAMPLE_PROPERTY_NAME);
                v.put("5", "3,500.00");
                v.put("6", SAMPLE_PORTAL_OWNER);
                break;
            case "MAINTENANCE_PAYMENT_REQUIRED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_PROVIDER_NAME);
                v.put("3", "3,500.00");
                v.put("4", "Fuga en baño");
                v.put("5", "La plataforma absorbe 15% (crédito: $525.00).");
                v.put("6", SAMPLE_PORTAL_OWNER);
                break;

            // ── Fase 2 — Vacancia / cadena de agentes ────────────────────────
            case "PROPERTY_VACANCY_OPENED":
                v.put("1", SAMPLE_AGENT_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", "Reforma 201, CDMX");
                v.put("4", "18,500.00");
                v.put("5", SAMPLE_PORTAL_AGENT);
                break;
            case "VACANCY_AGENT_ASSIGNED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_AGENT_NAME);
                v.put("3", SAMPLE_PROPERTY_NAME);
                v.put("4", SAMPLE_PORTAL_OWNER);
                break;
            case "VACANCY_AGENT_NEEDED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", SAMPLE_PORTAL_OWNER);
                break;
            case "VACANCY_AGENT_REJECTED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_AGENT_NAME);
                v.put("3", SAMPLE_PROPERTY_NAME);
                v.put("4", "Pasamos al siguiente agente de tu lista: Carlos Díaz.");
                v.put("5", SAMPLE_PORTAL_OWNER);
                break;
            case "VACANCY_AGENT_TIMEOUT":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", "Pasamos al siguiente agente de la cadena.");
                v.put("4", SAMPLE_PORTAL_OWNER);
                break;
            case "VACANCY_PHOTOS_UPLOADED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_AGENT_NAME);
                v.put("3", "12");
                v.put("4", SAMPLE_PROPERTY_NAME);
                v.put("5", SAMPLE_PORTAL_OWNER);
                break;
            case "VACANCY_CHAIN_EXHAUSTED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", SAMPLE_PORTAL_OWNER);
                break;

            // ── Fase 2 — Prospectos ──────────────────────────────────────────
            case "PROSPECT_PROPOSED":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_AGENT_NAME);
                v.put("3", SAMPLE_PROPERTY_NAME);
                v.put("4", SAMPLE_PROSPECT_NAME);
                v.put("5", "55 1234 5678 · jorge@ejemplo.com");
                v.put("6", SAMPLE_PORTAL_OWNER);
                break;
            case "PROSPECT_REMINDER":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_PROSPECT_NAME);
                v.put("3", SAMPLE_PROPERTY_NAME);
                v.put("4", "2 días");
                v.put("5", SAMPLE_PORTAL_OWNER);
                break;
            case "PROSPECT_OWNER_ACCEPTED":
                v.put("1", SAMPLE_AGENT_NAME);
                v.put("2", SAMPLE_PROSPECT_NAME);
                v.put("3", SAMPLE_PROPERTY_NAME);
                v.put("4", SAMPLE_PORTAL_AGENT);
                break;
            case "PROSPECT_OWNER_REJECTED":
                v.put("1", SAMPLE_AGENT_NAME);
                v.put("2", SAMPLE_PROSPECT_NAME);
                v.put("3", "Ingresos no suficientes para el contrato");
                v.put("4", SAMPLE_PORTAL_AGENT);
                break;

            // ── Fase 2 — Contrato / comisiones ───────────────────────────────
            case "CONTRACT_SIGNED_COMMISSION_DUE":
                v.put("1", SAMPLE_OWNER_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", SAMPLE_AGENT_NAME);
                v.put("4", "8,325.00");
                v.put("5", SAMPLE_PORTAL_OWNER);
                break;
            case "COMMISSION_APPROVED":
                v.put("1", SAMPLE_AGENT_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", "8,325.00");
                v.put("4", SAMPLE_PORTAL_AGENT);
                break;
            case "COMMISSION_SPEI_PENDING_MANUAL":
                v.put("1", SAMPLE_AGENT_NAME);
                v.put("2", "8,325.00");
                v.put("3", "MBAN01202604180000000123456789");
                v.put("4", SAMPLE_PORTAL_AGENT);
                break;
            case "COMMISSION_PAID":
                v.put("1", SAMPLE_AGENT_NAME);
                v.put("2", SAMPLE_PROPERTY_NAME);
                v.put("3", "8,325.00");
                v.put("4", "18/04/2026");
                v.put("5", SAMPLE_PORTAL_AGENT);
                break;

            // ── Fase 2 — Cuenta bancaria agente ──────────────────────────────
            case "AGENT_BANK_ACCOUNT_VALIDATED":
                v.put("1", SAMPLE_AGENT_NAME);
                v.put("2", "7890");
                v.put("3", "BBVA");
                break;
            case "AGENT_BANK_ACCOUNT_FAILED":
                v.put("1", SAMPLE_AGENT_NAME);
                v.put("2", "La CLABE no tiene 18 dígitos válidos");
                v.put("3", SAMPLE_PORTAL_AGENT);
                break;

            default:
                // eventType desconocido → map vacío; dispatcher caerá a body libre.
                break;
        }
        return v;
    }

    /**
     * Descripción corta en español del evento para pintar en el panel admin.
     * Separada del {@link NotificationEventCatalog} para no crear un import
     * circular; las descripciones coinciden con el catálogo pero formateadas.
     */
    public static String humanDescription(String eventType) {
        if (eventType == null) return "";
        return switch (eventType) {
            case "OWNER_WELCOME"              -> "Bienvenida al dueño";
            case "TENANT_WELCOME"             -> "Bienvenida al arrendatario";
            case "OWNER_PROFILE_UPDATED"      -> "Perfil del dueño actualizado";
            case "TENANT_PAYMENT_REMINDER_5D" -> "Recordatorio pago (5 días)";
            case "TENANT_PAYMENT_REMINDER_3D" -> "Recordatorio pago (3 días)";
            case "TENANT_PAYMENT_REMINDER_2D" -> "Recordatorio pago (2 días)";
            case "TENANT_PAYMENT_REMINDER_1D" -> "Recordatorio pago (1 día)";
            case "MANUAL_PAYMENT_REMINDER"    -> "Recordatorio manual de pago";
            case "TRANSFER_CONFIRMED"         -> "SPEI confirmado al dueño";
            case "CASH_PAYMENT_PENDING_OWNER" -> "Comprobante por validar (dueño)";
            case "CASH_PAYMENT_APPROVED"      -> "Pago efectivo aprobado (inquilino)";
            case "CASH_PAYMENT_REJECTED"      -> "Pago efectivo rechazado (inquilino)";
            case "PAYMENT_AUTO_VALIDATED"     -> "SPEI auto-validado (dueño)";
            case "OWNER_UNPAID_TENANTS_DIGEST"-> "Resumen diario morosos";
            case "OWNER_MONTHLY_REPORT"       -> "Reporte mensual";
            case "ACCOUNT_ACTIVATION"         -> "Activación de cuenta (legacy)";
            case "AGENT_WELCOME"              -> "Bienvenida al agente";
            case "STAFF_WELCOME"              -> "Bienvenida al staff / proveedor";
            case "ACCOUNT_RECOVERED"          -> "Recuperación de cuenta (admin)";
            case "PROPERTY_VACANCY_OPENED"    -> "Vacancia abierta (al agente)";
            case "VACANCY_AGENT_ASSIGNED"     -> "Agente aceptó vacancia (al dueño)";
            case "VACANCY_AGENT_NEEDED"       -> "Dueño sin agente configurado";
            case "VACANCY_AGENT_REJECTED"     -> "Agente rechazó vacancia";
            case "VACANCY_AGENT_TIMEOUT"      -> "Agente no respondió en 72h";
            case "VACANCY_PHOTOS_UPLOADED"    -> "Agente subió fotos";
            case "VACANCY_CHAIN_EXHAUSTED"    -> "Cadena de agentes agotada";
            case "PROSPECT_PROPOSED"          -> "Prospecto propuesto al dueño";
            case "PROSPECT_REMINDER"          -> "Recordatorio decisión prospecto";
            case "PROSPECT_OWNER_ACCEPTED"    -> "Dueño aceptó prospecto";
            case "PROSPECT_OWNER_REJECTED"    -> "Dueño rechazó prospecto";
            case "CONTRACT_SIGNED_COMMISSION_DUE" -> "Contrato firmado, comisión pendiente";
            case "COMMISSION_APPROVED"        -> "Comisión aprobada por el dueño";
            case "COMMISSION_SPEI_PENDING_MANUAL" -> "SPEI comisión no validó (3 intentos)";
            case "COMMISSION_PAID"            -> "Comisión pagada";
            case "MAINTENANCE_TICKET_AWAITING_OWNER_AUTH" -> "Ticket espera autorización";
            case "MAINTENANCE_QUOTE_UPLOADED" -> "Cotización subida";
            case "MAINTENANCE_PAYMENT_REQUIRED" -> "Pago SPEI requerido";
            case "MAINTENANCE_TICKET_REJECTED_BY_OWNER" -> "Dueño rechazó ticket";
            case "MAINTENANCE_PROVIDER_REJECTED" -> "Proveedor rechazó ticket";
            case "MAINTENANCE_TICKET_ASSIGNED"-> "Ticket asignado a proveedor";
            case "AGENT_BANK_ACCOUNT_VALIDATED" -> "CLABE del agente validada";
            case "AGENT_BANK_ACCOUNT_FAILED"  -> "CLABE del agente con error";
            default -> eventType;
        };
    }
}
