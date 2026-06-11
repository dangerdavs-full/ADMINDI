package com.admindi.backend.security;

import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;

public class TenantContext {
    private static final ThreadLocal<String> CURRENT_OWNER = new ThreadLocal<>();

    public static String getCurrentOwner() {
        return CURRENT_OWNER.get();
    }

    public static void setCurrentOwner(String ownerId) {
        CURRENT_OWNER.set(ownerId);
    }

    public static void clear() {
        CURRENT_OWNER.remove();
    }

    /**
     * Active owner id for the request: JWT owner claim first, then legacy User.ownerId, then OWNER/SUPER_ADMIN root id.
     */
    public static String resolveOwnerId(UserRepository userRepository) {
        String fromJwt = getCurrentOwner();
        if (fromJwt != null && !fromJwt.isBlank()) {
            return fromJwt;
        }
        // V48: auth.getName() es el username (JWT subject = username). Se mantiene
        // el nombre local `username` para dejar explícito el cambio conceptual.
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        if (user.getOwnerId() != null && !user.getOwnerId().isBlank()) {
            return user.getOwnerId();
        }
        if (user.getRole() == Role.SUPER_ADMIN || user.getRole() == Role.OWNER) {
            return user.getId();
        }
        throw new RuntimeException("Sin contexto de organizacion.");
    }
}
