package com.admindi.backend.qa;

/**
 * Credenciales y secretos solo para entornos no productivos (semilla Etapa 0).
 * Nunca usar en produccion.
 */
public final class QaEtapa0Constants {

    private QaEtapa0Constants() {}

    /** Contrasena comun usuarios QA Etapa 0 (BCrypt en BD). */
    public static final String QA_PASSWORD_PLAINTEXT = "QaEtapa0-Test-2024!";

    /**
     * Secreto Base32 compartido para MFA de prueba (superadmin QA + staff MFA).
     * Un solo codigo TOTP valido en ventana de tiempo sirve para todos si comparten secreto.
     */
    public static final String QA_MFA_SECRET_BASE32 = "JBSWY3DPEHPK3PXP";

    public static final String OWNER_ALPHA_ID = "e0e00000-0000-4000-8000-000000000001";
    public static final String OWNER_ALPHA_EMAIL = "qa.etapa0.owner.alpha@test.local";

    public static final String OWNER_BRAVO_ID = "e0e00000-0000-4000-8000-000000000002";
    public static final String OWNER_BRAVO_EMAIL = "qa.etapa0.owner.bravo@test.local";

    public static final String OWNER_ARCHIVE_ID = "e0e00000-0000-4000-8000-000000000003";
    public static final String OWNER_ARCHIVE_EMAIL = "qa.etapa0.owner.archive@test.local";

    public static final String TENANT_MULTI_EMAIL = "qa.etapa0.tenant.multi@test.local";

    public static final String TENANT_ARCHIVE_USER_EMAIL = "qa.etapa0.tenant.archive@test.local";

    public static final String SUPERADMIN_MFA_EMAIL = "qa.etapa0.superadmin@mfa.admindi.local";

    public static final String STAFF_PROP_ADMIN_EMAIL = "qa.etapa0.staff.propadmin@test.local";
    public static final String STAFF_ACCOUNTANT_EMAIL = "qa.etapa0.staff.accountant@test.local";
}
