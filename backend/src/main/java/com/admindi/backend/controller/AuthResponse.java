package com.admindi.backend.controller;

import java.util.Map;

public class AuthResponse {
    
    private String token;
    private String role;
    private boolean mustChangePassword;
    private String name;
    private boolean requiresOrgSelection = false;
    private Map<String, String> organizations;

    public AuthResponse() {}

    public AuthResponse(String token, String role) {
        this.token = token;
        this.role = role;
        this.mustChangePassword = false;
    }

    public AuthResponse(String token, String role, boolean mustChangePassword, String name) {
        this.token = token;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
        this.name = name;
    }

    public AuthResponse(String token, String role, boolean mustChangePassword, String name, boolean requiresOrgSelection, Map<String, String> orgs) {
        this.token = token;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
        this.name = name;
        this.requiresOrgSelection = requiresOrgSelection;
        this.organizations = orgs;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public boolean isRequiresOrgSelection() { return requiresOrgSelection; }
    public void setRequiresOrgSelection(boolean requiresOrgSelection) { this.requiresOrgSelection = requiresOrgSelection; }
    
    public Map<String, String> getOrganizations() { return organizations; }
    public void setOrganizations(Map<String, String> orgs) { this.organizations = orgs; }
}
