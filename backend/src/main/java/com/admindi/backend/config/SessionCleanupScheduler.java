package com.admindi.backend.config;

import com.admindi.backend.repository.RefreshTokenSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Scheduled cleanup of expired and revoked refresh token sessions.
 * Runs every 6 hours to prevent unbounded accumulation of session rows.
 */
@Component
public class SessionCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanupScheduler.class);

    private final RefreshTokenSessionRepository refreshRepo;

    public SessionCleanupScheduler(RefreshTokenSessionRepository refreshRepo) {
        this.refreshRepo = refreshRepo;
    }

    @Scheduled(fixedRate = 6 * 60 * 60 * 1000) // every 6 hours
    public void purgeExpiredSessions() {
        LocalDateTime cutoff = LocalDateTime.now();
        int deleted = refreshRepo.deleteExpiredAndRevoked(cutoff);
        if (deleted > 0) {
            log.info("[ADMINDI] Session cleanup: purged {} expired/revoked sessions", deleted);
        }
    }
}
