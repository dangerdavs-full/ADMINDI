package com.admindi.backend.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bridges the gap between the permission strings stored in
 * {@code permission_templates.permissions} (lowercase/colon convention, e.g.
 * {@code "properties:read"}, {@code "leases:write"}) and the authority names that
 * {@code @PreAuthorize("hasAuthority(...)")} annotations in the controllers check
 * (uppercase/underscore convention, e.g. {@code PROPERTY_VIEW}, {@code LEASE_CREATE}).
 *
 * <h3>Why this exists</h3>
 * The legacy annotations were written before the template system was seeded with the
 * lowercase convention (V9 / V40). Each Fase 1-3 endpoint that adopted the approval
 * flow discovered the mismatch one at a time and patched it locally with
 * {@code "hasAuthority('properties:delete') or hasAuthority('PROPERTY_DELETE')"}. That
 * strategy scales poorly and creates several problems:
 *
 * <ul>
 *   <li>Every new endpoint has to remember to dual-guard, easy to forget.</li>
 *   <li>Reading the annotation doesn't tell you which convention is canonical.</li>
 *   <li>Adding a new template row still requires editing N annotations.</li>
 * </ul>
 *
 * <h3>What this does</h3>
 * When the JWT authentication filter copies the {@code permissions} claim into the
 * {@link org.springframework.security.core.GrantedAuthority} set, each permission is
 * expanded into its legacy aliases in addition to itself. The original lowercase form
 * stays in the set (so annotations using {@code hasAuthority('properties:read')} still
 * work), while the uppercase aliases make the existing annotations match.
 *
 * <p>Mapping is conservative: only well-known templates get aliased. Custom permission
 * strings pass through untouched. Nothing here grants elevation beyond what the owner
 * configured in the template — it's a <em>name translation</em>, not an expansion of
 * scope.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>The map is immutable and computed once (static). No allocation per request
 *       beyond the result set.</li>
 *   <li>Both {@code properties:*} and {@code units:*} map into the {@code PROPERTY_*}
 *       aliases because the legacy codebase treats units as a sub-resource of property
 *       (the unit controller annotations reuse {@code PROPERTY_CREATE/UPDATE/VIEW}).
 *       If we later split them, adjust the mapping here.</li>
 *   <li>{@code leases:write} aliases to {@code LEASE_CREATE} <em>and</em>
 *       {@code LEASE_UPDATE} because termination reuses {@code LEASE_CREATE} in the
 *       current controllers. Again, easy to split later.</li>
 * </ul>
 */
public final class PermissionAliasResolver {

    /**
     * {@code lowercase-colon -> list of UPPER_UNDERSCORE aliases}. Keep entries sorted
     * by resource to ease review. An empty list means no alias (permission is used as-is).
     */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            // Properties
            Map.entry("properties:read",   List.of("PROPERTY_VIEW")),
            Map.entry("properties:write",  List.of("PROPERTY_CREATE", "PROPERTY_UPDATE")),
            Map.entry("properties:delete", List.of("PROPERTY_DELETE")),

            // Units share the PROPERTY_* authorities in the current controllers.
            Map.entry("units:read",   List.of("PROPERTY_VIEW")),
            Map.entry("units:write",  List.of("PROPERTY_CREATE", "PROPERTY_UPDATE")),
            Map.entry("units:delete", List.of("PROPERTY_DELETE")),

            // Tenants
            Map.entry("tenants:read",  List.of("TENANT_VIEW")),
            Map.entry("tenants:write", List.of("TENANT_CREATE", "TENANT_UPDATE")),

            // Leases — LEASE_CREATE doubles as the terminate guard in LeaseController
            // (see PUT /{id}/terminate). When we split that, add LEASE_TERMINATE below.
            Map.entry("leases:read",  List.of("LEASE_VIEW")),
            Map.entry("leases:write", List.of("LEASE_CREATE", "LEASE_UPDATE")),

            // Invoices
            Map.entry("invoices:read",  List.of("INVOICE_VIEW")),
            Map.entry("invoices:write", List.of("INVOICE_CREATE", "INVOICE_UPDATE")),

            // Staff
            Map.entry("staff:read",  List.of("STAFF_VIEW")),
            Map.entry("staff:write", List.of("STAFF_CREATE", "STAFF_UPDATE")),

            // Reports — current templates don't distinguish view vs. export, so a
            // read-only permission grants both in the UI. If finance needs to gate
            // export separately, introduce "reports:export" in the template.
            Map.entry("reports:read", List.of("REPORT_VIEW", "REPORT_EXPORT"))
    );

    private PermissionAliasResolver() {}

    /**
     * Returns the legacy uppercase aliases for a single template permission string.
     * Empty list for unknown permissions — callers should still include the original.
     */
    public static List<String> aliasesFor(String permission) {
        if (permission == null || permission.isBlank()) return List.of();
        return ALIASES.getOrDefault(permission, List.of());
    }

    /**
     * Expands a collection of template permissions into the full authority set: the
     * original permissions plus every legacy alias we know about. Order is preserved
     * (original strings first) and duplicates are dropped. Used by the JWT filter.
     */
    public static Set<String> expand(Collection<String> permissions) {
        if (permissions == null || permissions.isEmpty()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>(permissions.size() * 2);
        for (String p : permissions) {
            if (p == null || p.isBlank()) continue;
            out.add(p);
            out.addAll(aliasesFor(p));
        }
        return out;
    }
}
