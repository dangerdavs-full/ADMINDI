package com.admindi.backend.security;

import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.OwnerMembershipRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService blacklistService;
    private final OwnerMembershipRepository ownerMembershipRepository;
    private final TenantProfileRepository tenantProfileRepository;

    @Autowired
    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsService userDetailsService,
                                   TokenBlacklistService blacklistService,
                                   OwnerMembershipRepository ownerMembershipRepository,
                                   TenantProfileRepository tenantProfileRepository) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.blacklistService = blacklistService;
        this.ownerMembershipRepository = ownerMembershipRepository;
        this.tenantProfileRepository = tenantProfileRepository;
    }

    private boolean tenantMayUseOwnerClaim(UserEntity user, String claimOwnerId) {
        if (claimOwnerId == null || claimOwnerId.isBlank() || "null".equalsIgnoreCase(claimOwnerId)) {
            return false;
        }
        if (ownerMembershipRepository.findByUserIdAndOwnerId(user.getId(), claimOwnerId).isPresent()) {
            return true;
        }
        if (claimOwnerId.equals(user.getOwnerId())) {
            return true;
        }
        return tenantProfileRepository.existsByUserIdAndOwnerIdAndArchivedAtIsNull(user.getId(), claimOwnerId);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String jwt = authHeader.substring(7);
            // V48: JWT subject = username (no email). UserDetailsService resuelve por username.
            String subjectUsername = jwtService.extractUsername(jwt);

            if (subjectUsername != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(subjectUsername);

                if (jwtService.isTokenValid(jwt, userDetails)) {

                    String jti = jwtService.extractClaim(jwt, claims -> claims.getId());
                    if (blacklistService.isRevoked(jti)) {
                        request.setAttribute("exception", "Token revoked");
                    } else {
                        String tokenType = jwtService.extractType(jwt);
                        String requestPath = request.getRequestURI();

                        if ("BASE".equals(tokenType) && requestPath.contains("/auth/switch-context")) {
                            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                                    "switch-context requires FULL access token (use select-context with BASE)");
                            return;
                        }

                        // BASE = pre-context token: contexts + select-context only (plan: FULL required for switch-context).
                        boolean baseAllowed = requestPath.contains("/auth/select-context")
                                || requestPath.contains("/auth/contexts")
                                || requestPath.contains("/auth/login")
                                || requestPath.contains("/auth/mfa/")
                                || requestPath.contains("/auth/change-password")
                                || requestPath.contains("/auth/logout")
                                || requestPath.contains("/reporting-period-bounds");
                        if ("BASE".equals(tokenType) && !baseAllowed) {
                            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Missing context selection");
                            return;
                        }

                        // MFA_CHALLENGE tokens are ONLY valid for MFA-related endpoints
                        if ("MFA_CHALLENGE".equals(tokenType) && !requestPath.contains("/auth/mfa/")) {
                            response.sendError(HttpServletResponse.SC_FORBIDDEN, "MFA challenge tokens cannot be used for API access");
                            return;
                        }

                        // REFRESH tokens cannot be used for API access — only for /auth/refresh
                        if ("REFRESH".equals(tokenType)) {
                            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Refresh tokens cannot be used for API access");
                            return;
                        }

                        if (!userDetails.isEnabled()) {
                            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Account disabled");
                            return;
                        }

                        if ("FULL".equals(tokenType) && userDetails instanceof UserEntity ue && ue.getRole() == Role.TENANT) {
                            String claimOwnerId = jwtService.extractClaim(jwt, claims -> claims.get("ownerId", String.class));
                            if (!tenantMayUseOwnerClaim(ue, claimOwnerId)) {
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "No access to this organization");
                                return;
                            }
                        }

                        String ownerId = jwtService.extractClaim(jwt, claims -> claims.get("ownerId", String.class));
                        if (ownerId != null) {
                            TenantContext.setCurrentOwner(ownerId);
                        }

                        Collection<? extends GrantedAuthority> authorities = userDetails.getAuthorities();
                        if ("FULL".equals(tokenType)) {
                            authorities = mergeJwtPermissionsIntoAuthorities(authorities, jwt);
                        }

                        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                userDetails, null, authorities
                        );
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                    }
                }
            }
        } catch (Exception ex) {
            // Un token malformado, viejo o con firma rota simplemente se ignora.
            // Esto permite que /auth/login u otros endpoints públicos funcionen sin devolver 403 o 500 por el token roto.
            log.debug("Ignored invalid token: {}", ex.getMessage());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Los permisos efectivos del contexto (plantilla + grant) viajan en el JWT FULL; Spring Security debe verlos como
     * {@link GrantedAuthority} para que {@code @PreAuthorize(hasAuthority(...))} coincida con el backend de permisos.
     *
     * <p>Cada permiso del JWT se expande vía {@link PermissionAliasResolver} a sus alias
     * legacy (UPPER_UNDERSCORE) además de mantenerse en su forma original
     * (lowercase/colon). Esto permite que anotaciones antiguas como
     * {@code hasAuthority('PROPERTY_VIEW')} sigan funcionando con templates que
     * almacenan {@code properties:read}, sin requerir tocar cada controller.
     */
    private Collection<? extends GrantedAuthority> mergeJwtPermissionsIntoAuthorities(
            Collection<? extends GrantedAuthority> base,
            String jwt) {
        List<String> jwtPerms = jwtService.extractClaim(jwt, claims -> {
            Object raw = claims.get("permissions");
            if (raw == null) {
                return List.<String>of();
            }
            if (raw instanceof List<?> list) {
                return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
            }
            return List.<String>of();
        });
        if (jwtPerms.isEmpty()) {
            return base;
        }
        LinkedHashSet<GrantedAuthority> merged = new LinkedHashSet<>();
        for (GrantedAuthority a : base) {
            merged.add(a);
        }
        for (String p : PermissionAliasResolver.expand(jwtPerms)) {
            merged.add(new SimpleGrantedAuthority(p));
        }
        return merged;
    }
}
