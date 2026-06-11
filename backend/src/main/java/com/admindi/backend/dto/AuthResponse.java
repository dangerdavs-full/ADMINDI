package com.admindi.backend.dto;

import java.util.Map;

public class AuthResponse {
    private String token;
    private String name;
    private String contextId;
    private boolean mustChangePassword;
    private String role;
    private boolean onboardingCompleted;
    
    // Legacy mapping properties for frontend
    private boolean requiresOrgSelection;
    private Map<String, String> organizations;
    private boolean mfaSetupRequired;

    public AuthResponse(String token, String contextId, boolean mustChangePassword, String role, String name, boolean onboardingCompleted) {
        this.token = token;
        this.contextId = contextId;
        this.mustChangePassword = mustChangePassword;
        this.role = role;
        this.name = name;
        this.onboardingCompleted = onboardingCompleted;
        this.requiresOrgSelection = false;
    }
    
    public AuthResponse(String token, Map<String, String> organizations, String name) {
        this.token = token;
        this.organizations = organizations;
        this.requiresOrgSelection = true;
        this.name = name;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public boolean isOnboardingCompleted() { return onboardingCompleted; }
    public void setOnboardingCompleted(boolean onboardingCompleted) { this.onboardingCompleted = onboardingCompleted; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isRequiresOrgSelection() { return requiresOrgSelection; }
    public void setRequiresOrgSelection(boolean req) { this.requiresOrgSelection = req; }
    public Map<String, String> getOrganizations() { return organizations; }
    public void setOrganizations(Map<String, String> orgs) { this.organizations = orgs; }
    public boolean isMfaSetupRequired() { return mfaSetupRequired; }
    public void setMfaSetupRequired(boolean mfaSetupRequired) { this.mfaSetupRequired = mfaSetupRequired; }
    
    private String mfaChallengeToken;
    public String getMfaChallengeToken() { return mfaChallengeToken; }
    public void setMfaChallengeToken(String mfaChallengeToken) { this.mfaChallengeToken = mfaChallengeToken; }

    private String refreshToken;
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
}
