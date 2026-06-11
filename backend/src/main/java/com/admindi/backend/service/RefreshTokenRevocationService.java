package com.admindi.backend.service;

import com.admindi.backend.model.RefreshTokenSessionEntity;
import com.admindi.backend.repository.RefreshTokenSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RefreshTokenRevocationService {

    private final RefreshTokenSessionRepository refreshTokenSessionRepository;

    @Autowired
    public RefreshTokenRevocationService(RefreshTokenSessionRepository refreshTokenSessionRepository) {
        this.refreshTokenSessionRepository = refreshTokenSessionRepository;
    }

    /** Revoke all refresh sessions for a user (e.g. account deactivated). */
    @Transactional
    public void revokeAllRefreshSessionsForUser(String userId) {
        List<RefreshTokenSessionEntity> sessions = refreshTokenSessionRepository.findByUserId(userId);
        for (RefreshTokenSessionEntity s : sessions) {
            s.setRevoked(true);
            refreshTokenSessionRepository.save(s);
        }
    }
}
