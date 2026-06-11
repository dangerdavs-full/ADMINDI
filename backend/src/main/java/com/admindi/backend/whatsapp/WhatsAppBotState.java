package com.admindi.backend.whatsapp;

/**
 * Estados posibles del chatbot de WhatsApp por número de teléfono.
 *
 * Transiciones controladas por {@code WhatsAppBotOrchestrator}. Cada estado
 * asume un tipo específico de entrada del usuario; si la entrada no coincide,
 * el bot muestra ayuda y permanece en el mismo estado.
 */
public enum WhatsAppBotState {

    /** Primera interacción del inquilino — se le pide fijar un NIP 4-6 dígitos. */
    ASKING_PIN_SETUP,

    /** Volvió — se le pide el NIP que configuró previamente. */
    ASKING_PIN,

    /** Menú principal: elige qué hacer. */
    MENU,

    /** Inquilino con varios inmuebles: elige sobre cuál operar en esta sesión. */
    SELECT_PROPERTY,

    /** Cuenta reconocida pero pendiente de activar contraseña en el portal. */
    ACCOUNT_NEEDS_ACTIVATION,

    // ─── Flujo comprobante SPEI ───────────────────────────────────────────
    /** Esperando foto del comprobante (o palabra clave para captura manual). */
    PROOF_WAITING_IMAGE,
    /** Captura manual paso a paso cuando el inquilino no puede enviar foto. */
    PROOF_MANUAL_ENTRY,
    /** Esperando que el inquilino confirme los datos (OCR o captura manual). */
    PROOF_CONFIRMING_DATA,

    // ─── Flujo ticket mantenimiento ────────────────────────────────────────
    /** Esperando descripción (texto libre) del problema. */
    TICKET_WAITING_DESC,
    /** Esperando selección de urgencia (baja/media/alta/urgente). */
    TICKET_WAITING_URGENCY,
    /** Esperando fotos del problema; LISTO marca el fin. */
    TICKET_WAITING_PHOTOS,
    /** Esperando selección de propiedad cuando el inquilino tiene múltiples expedientes. */
    TICKET_WAITING_PROPERTY,
    /** Confirmación final antes de crear el ticket. */
    TICKET_CONFIRMING,

    // ─── Flujo consultas ──────────────────────────────────────────────────
    /** Mostrando info — el usuario puede volver al menú. */
    QUERY_VIEWING,

    /** Demasiados intentos fallidos de NIP o sesión bloqueada. */
    LOCKED,

    /** Número no vinculado a ningún TENANT — no avanzamos. */
    UNRECOGNIZED,

    // ─── Flujo dueño: validar comprobantes (CASH + SPEI manual) ───────────
    /** Menú del dueño. */
    OWNER_MENU,
    /** Lista numerada de comprobantes pendientes. */
    OWNER_PROOF_PICK,
    /** Confirmar aprobar o rechazar el comprobante elegido. */
    OWNER_PROOF_DECIDE,
    /** Motivo breve de rechazo (opcional). */
    OWNER_PROOF_REJECT_REASON,

    // ─── Flujo dueño: informe de pagos del mes ─────────────────────────────
    /** Eligiendo el mes a consultar (lista o mes escrito MM-AAAA). */
    OWNER_REPORT_MONTH,
    /** Eligiendo alcance: resumen de todos los inmuebles o detalle por arrendatario. */
    OWNER_REPORT_SCOPE,
    /** Eligiendo el arrendatario para el detalle. */
    OWNER_REPORT_TENANT_PICK
}
