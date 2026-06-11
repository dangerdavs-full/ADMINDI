package com.admindi.backend.dto;

public class ShortfallSubmitResultDTO {
    private InvoiceDTO invoice;
    private boolean agreementRequired;
    private String message;

    public ShortfallSubmitResultDTO() {}

    public InvoiceDTO getInvoice() { return invoice; }
    public void setInvoice(InvoiceDTO invoice) { this.invoice = invoice; }
    public boolean isAgreementRequired() { return agreementRequired; }
    public void setAgreementRequired(boolean agreementRequired) { this.agreementRequired = agreementRequired; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}