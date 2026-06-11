package com.admindi.backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class TokenBlacklistService {

    private static final Logger logger = LoggerFactory.getLogger(TokenBlacklistService.class);
    private final StringRedisTemplate redisTemplate;
    private static final Duration TTL = Duration.ofHours(24);

    @Autowired
    public TokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void revokeToken(String jti) {
        if (jti != null) {
            try {
                redisTemplate.opsForValue().set("revoked:" + jti, "true", TTL);
            } catch (Exception e) {
                logger.error("CRITICAL: Redis unreachable for token revocation. jti={}", jti, e);
            }
        }
    }

    public boolean isRevoked(String jti) {
        if (jti == null) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey("revoked:" + jti));
        } catch (Exception e) {
            // FAIL-CLOSED: if Redis is down, reject the token. 
            // An attacker cannot bypass revocation by taking down Redis.
            logger.error("CRITICAL: Redis unreachable for revocation check. Rejecting token (fail-closed). jti={}", jti, e);
            return true;
        }
    }
}
