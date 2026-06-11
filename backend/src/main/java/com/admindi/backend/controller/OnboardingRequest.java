package com.admindi.backend.controller;

public class OnboardingRequest {
    private Boolean usePlatformMaintenance;
    private Boolean usePlatformAgents;

    public Boolean getUsePlatformMaintenance() {
        return usePlatformMaintenance;
    }

    public void setUsePlatformMaintenance(Boolean usePlatformMaintenance) {
        this.usePlatformMaintenance = usePlatformMaintenance;
    }

    public Boolean getUsePlatformAgents() {
        return usePlatformAgents;
    }

    public void setUsePlatformAgents(Boolean usePlatformAgents) {
        this.usePlatformAgents = usePlatformAgents;
    }
}
