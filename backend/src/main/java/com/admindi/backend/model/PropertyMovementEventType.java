package com.admindi.backend.model;

public final class PropertyMovementEventType {

    private PropertyMovementEventType() {}

    public static final String PAYMENT_EXACT = "PAYMENT_EXACT";
    public static final String PAYMENT_PARTIAL = "PAYMENT_PARTIAL";
    public static final String PAYMENT_OVERPAY = "PAYMENT_OVERPAY";

    public static final String AGREEMENT_REQUESTED = "AGREEMENT_REQUESTED";
    public static final String AGREEMENT_APPROVED = "AGREEMENT_APPROVED";
    public static final String AGREEMENT_REJECTED = "AGREEMENT_REJECTED";
    public static final String AGREEMENT_BREACHED = "AGREEMENT_BREACHED";
    public static final String AGREEMENT_EVIDENCE = "AGREEMENT_EVIDENCE";

    public static final String MAINTENANCE_TICKET = "MAINTENANCE_TICKET";

    /** Proveedor acepta atender el ticket (obligatorio antes de cotizar cuando hubo asignacion). */
    public static final String MAINTENANCE_TICKET_ACCEPTED = "MAINTENANCE_TICKET_ACCEPTED";

    /** Registro de pago (parcial o total) del egreso de mantenimiento. */
    public static final String MAINTENANCE_EXPENSE_PAYMENT_RECORDED = "MAINTENANCE_EXPENSE_PAYMENT_RECORDED";

    /** Desacuerdo en settlement entre dueño y proveedor. */
    public static final String MAINTENANCE_SETTLEMENT_DISPUTE = "MAINTENANCE_SETTLEMENT_DISPUTE";
    public static final String MAINTENANCE_PROVIDER_ASSIGNED = "MAINTENANCE_PROVIDER_ASSIGNED";
    public static final String MAINTENANCE_PROVIDER_ASSIGNMENT_PENDING = "MAINTENANCE_PROVIDER_ASSIGNMENT_PENDING";
    public static final String MAINTENANCE_QUOTE = "MAINTENANCE_QUOTE";
    public static final String MAINTENANCE_APPROVED = "MAINTENANCE_APPROVED";
    public static final String MAINTENANCE_REJECTED = "MAINTENANCE_REJECTED";
    public static final String MAINTENANCE_PAID = "MAINTENANCE_PAID";

    public static final String VACANCY_OPENED = "VACANCY_OPENED";
    public static final String VACANCY_AGENT_ASSIGNED = "VACANCY_AGENT_ASSIGNED";
    public static final String VACANCY_AGENT_ASSIGNMENT_PENDING = "VACANCY_AGENT_ASSIGNMENT_PENDING";
    public static final String VACANCY_CLOSED = "VACANCY_CLOSED";

    public static final String VISIT_RECORDED = "VISIT_RECORDED";
    public static final String PHOTOS_UPLOADED = "PHOTOS_UPLOADED";
    public static final String COMMERCIAL_OBSERVATION = "COMMERCIAL_OBSERVATION";
    public static final String LISTING_ACTIVE = "LISTING_ACTIVE";
    public static final String COMMERCIAL_FOLLOW_UP = "COMMERCIAL_FOLLOW_UP";

    public static final String COMMERCIAL_ACTIVITY = "COMMERCIAL_ACTIVITY";
    public static final String COMMISSION_QUOTED = "COMMISSION_QUOTED";
    public static final String COMMISSION_APPROVED = "COMMISSION_APPROVED";
    public static final String COMMISSION_REJECTED = "COMMISSION_REJECTED";
    public static final String COMMISSION_PAYMENT_RECORDED = "COMMISSION_PAYMENT_RECORDED";
    public static final String COMMERCIAL_SETTLEMENT_DISPUTE = "COMMERCIAL_SETTLEMENT_DISPUTE";

    public static final String LEASE_TERMINATED = "LEASE_TERMINATED";

    /** Expediente abierto: alta integral de arrendatario + contrato ACTIVO sobre el inmueble. */
    public static final String TENANT_EXPEDIENTE_OPENED = "TENANT_EXPEDIENTE_OPENED";

    /** Baja operativa del expediente (archivo): contrato terminado, inmueble liberado, factura periodo anulada. */
    public static final String TENANT_EXPEDIENTE_ARCHIVED = "TENANT_EXPEDIENTE_ARCHIVED";

    public static final String PROPERTY_FILE_UPLOADED = "PROPERTY_FILE_UPLOADED";

    /**
     * File removed from the property's archive. Emitted whether the deletion was a direct
     * owner action (reauth) or the outcome of an approved
     * {@code PROPERTY_FILE_DELETE_REQUESTED} task. The movement row preserves the filename
     * and category even though the underlying blob/DB row are gone, so the property
     * timeline keeps an auditable record of "a document used to be here".
     */
    public static final String PROPERTY_FILE_DELETED = "PROPERTY_FILE_DELETED";
}