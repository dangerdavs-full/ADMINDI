package com.admindi.backend.controller;

public class MfaResponse {
    private String secretImageUri;
    private String rawSecret;

    public MfaResponse(String secretImageUri, String rawSecret) {
        this.secretImageUri = secretImageUri;
        this.rawSecret = rawSecret;
    }

    public String getSecretImageUri() {
        return secretImageUri;
    }

    public void setSecretImageUri(String secretImageUri) {
        this.secretImageUri = secretImageUri;
    }

    public String getRawSecret() {
        return rawSecret;
    }

    public void setRawSecret(String rawSecret) {
        this.rawSecret = rawSecret;
    }
}
