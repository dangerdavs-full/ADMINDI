package com.admindi.backend.whatsapp;

import com.admindi.backend.model.UserEntity;
import com.admindi.backend.security.TenantContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Puente para invocar servicios de dominio (que usan {@link SecurityContextHolder}
 * y {@link TenantContext}) desde el chatbot de WhatsApp, que NO está dentro de
 * un request HTTP autenticado con JWT.
 *
 * El flujo de webhook entrante de Twilio pasa por {@code TwilioWebhookController}
 * que está marcado {@code permitAll} — no hay {@code Authentication} propagado.
 * Este bridge simula un contexto de seguridad con el {@link UserEntity} TENANT
 * identificado por el teléfono, ejecuta la acción y limpia en {@code finally}.
 *
 * Seguridad:
 *  - La autoridad {@code ROLE_TENANT} es la que ya tiene el user real en DB.
 *  - El contexto se limpia SIEMPRE en finally, incluso ante excepciones, para
 *    que ningún otro thread herede credenciales de otro usuario.
 *  - Se setea {@code TenantContext.setCurrentOwner(user.ownerId)} para que
 *    {@code resolveOwnerId()} funcione sin depender del JWT claim.
 */
@Component
public class BotSecurityBridge {

    /**
     * Ejecuta {@code action} con el contexto de seguridad del user como si
     * fuera un request autenticado. Usar solo desde código del bot, nunca
     * desde flujos HTTP normales.
     */
    public <T> T runAs(UserEntity user, Supplier<T> action) {
        if (user == null) throw new IllegalArgumentException("user no puede ser null");

        SecurityContext previous = SecurityContextHolder.getContext();
        String previousOwner = TenantContext.getCurrentOwner();

        try {
            SecurityContext fresh = SecurityContextHolder.createEmptyContext();
            // Principal = UserEntity para que los servicios que usan
            // getPrincipal() (y no solo getName()) puedan resolver el ID sin
            // volver a consultar la DB. getName() sigue devolviendo el
            // username por override de UserEntity.getUsername().
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    user,
                    null,
                    user.getAuthorities());
            fresh.setAuthentication(auth);
            SecurityContextHolder.setContext(fresh);

            String ownerId = user.getOwnerId();
            if (ownerId != null && !ownerId.isBlank()) {
                TenantContext.setCurrentOwner(ownerId);
            }

            return action.get();
        } finally {
            // Restaurar el contexto previo (típicamente el original del thread
            // pool del servlet si lo hubiera) y limpiar TenantContext.
            SecurityContextHolder.setContext(previous == null
                    ? SecurityContextHolder.createEmptyContext() : previous);
            if (previousOwner == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentOwner(previousOwner);
            }
        }
    }

    public void runAsVoid(UserEntity user, Runnable action) {
        runAs(user, () -> { action.run(); return null; });
    }

    /**
     * Contexto para el dueño: {@code TenantContext} apunta a su propio {@code id}
     * (organización), no a {@code ownerId} de staff.
     */
    public <T> T runAsOwner(UserEntity owner, Supplier<T> action) {
        if (owner == null) throw new IllegalArgumentException("owner no puede ser null");
        if (owner.getRole() != com.admindi.backend.model.Role.OWNER) {
            throw new IllegalArgumentException("runAsOwner requiere role OWNER");
        }
        return runAs(owner, () -> {
            TenantContext.setCurrentOwner(owner.getId());
            return action.get();
        });
    }

    public void runAsOwnerVoid(UserEntity owner, Runnable action) {
        runAsOwner(owner, () -> { action.run(); return null; });
    }
}
