package com.admindi.backend.dto;

/**
 * Request body para POST /api/tenants/{profileId}/send-manual-reminder.
 *
 * <p>La reauth se valida server-side con {@link com.admindi.backend.service.ReauthService}.
 * El campo {@code mfaCode} es opcional si el actor no tiene MFA habilitado, aunque en
 * producción todos los dueños/admins SÍ tienen MFA obligatorio.
 */
public class ManualReminderRequestDTO {

    private String password;
    private String mfaCode;

    public ManualReminderRequestDTO() {}

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getMfaCode() { return mfaCode; }
    public void setMfaCode(String mfaCode) { this.mfaCode = mfaCode; }
}
