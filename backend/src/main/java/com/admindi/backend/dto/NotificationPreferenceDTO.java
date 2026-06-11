package com.admindi.backend.dto;

/**
 * Preferencia de notificación por usuario, tipo de evento y canal.
 * Canales: {@code IN_APP} (inbox interno), {@code N8N}, {@code EMAIL}.
 */
public class NotificationPreferenceDTO {

    private String eventType;
    private String channel;
    private boolean enabled;

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
