package com.admindi.backend.service;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    @Autowired
    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public void logEvent(String actorId, String actorRole, String eventType, String resourceType, String resourceId, String ownerId, String oldValues, String newValues, String ipAddress, String userAgent) {
        AuditEventEntity event = new AuditEventEntity();
        event.setId(UUID.randomUUID().toString());
        event.setTimestamp(LocalDateTime.now());
        event.setActorId(actorId);
        event.setActorRole(actorRole);
        event.setEventType(eventType);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setOwnerId(ownerId);
        event.setOldValues(oldValues);
        event.setNewValues(newValues);
        event.setIpAddress(ipAddress);
        event.setUserAgent(userAgent);
        auditEventRepository.save(event);
    }
}
