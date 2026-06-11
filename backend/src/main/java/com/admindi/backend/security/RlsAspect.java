package com.admindi.backend.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;

@Aspect
@Component
public class RlsAspect {

    private static final Logger logger = LoggerFactory.getLogger(RlsAspect.class);

    // Strict regex: only alphanumeric + hyphens allowed (covers UUID and fixed IDs like "admin-0000-0000")
    private static final java.util.regex.Pattern SAFE_ID_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9\\-]{1,255}$");

    @PersistenceContext
    private EntityManager entityManager;

    @Around("execution(* org.springframework.data.repository.Repository+.*(..)) || execution(* com.admindi.backend.repository..*.*(..))")
    public Object applyRls(ProceedingJoinPoint pjp) throws Throwable {
        String ownerId = TenantContext.getCurrentOwner();

        boolean applied = false;
        if (ownerId != null && !ownerId.equals("null")) {
            // Validate ownerId format to prevent SQL injection
            if (!SAFE_ID_PATTERN.matcher(ownerId).matches()) {
                logger.error("RLS blocked: invalid ownerId format detected: {}", ownerId);
                throw new SecurityException("Invalid owner context");
            }

            Session session = entityManager.unwrap(Session.class);
            session.doWork(connection -> {
                // Use parameterized SET via format string with validated input
                try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('app.current_owner', ?, true)")) {
                    ps.setString(1, ownerId);
                    ps.execute();
                }
            });
            applied = true;
        }

        try {
            return pjp.proceed();
        } finally {
            if (applied) {
                Session session = entityManager.unwrap(Session.class);
                session.doWork(connection -> {
                    try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('app.current_owner', '', true)")) {
                        ps.execute();
                    }
                });
            }
        }
    }
}
