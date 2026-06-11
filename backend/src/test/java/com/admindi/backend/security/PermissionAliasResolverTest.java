package com.admindi.backend.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Safety net for {@link PermissionAliasResolver}. The class sits between the JWT filter
 * and every {@code @PreAuthorize} check in the app — a silent regression here locks out
 * every Property Admin from the dashboard, so the invariants are worth pinning.
 */
class PermissionAliasResolverTest {

    @Test
    void aliasesFor_fullAccessTemplatePermissions_expandToLegacyNames() {
        // The "Acceso Total" template (V9/V40) ships these strings. Each one must
        // produce at least one legacy UPPER_UNDERSCORE alias so the old @PreAuthorize
        // checks keep working without duplicating the guard at every endpoint.
        assertTrue(PermissionAliasResolver.aliasesFor("properties:read").contains("PROPERTY_VIEW"));
        assertTrue(PermissionAliasResolver.aliasesFor("properties:write").contains("PROPERTY_CREATE"));
        assertTrue(PermissionAliasResolver.aliasesFor("properties:write").contains("PROPERTY_UPDATE"));
        assertTrue(PermissionAliasResolver.aliasesFor("properties:delete").contains("PROPERTY_DELETE"));

        assertTrue(PermissionAliasResolver.aliasesFor("tenants:read").contains("TENANT_VIEW"));
        assertTrue(PermissionAliasResolver.aliasesFor("tenants:write").contains("TENANT_CREATE"));
        assertTrue(PermissionAliasResolver.aliasesFor("leases:read").contains("LEASE_VIEW"));
        assertTrue(PermissionAliasResolver.aliasesFor("leases:write").contains("LEASE_CREATE"));
        assertTrue(PermissionAliasResolver.aliasesFor("invoices:read").contains("INVOICE_VIEW"));
        assertTrue(PermissionAliasResolver.aliasesFor("reports:read").contains("REPORT_VIEW"));
        assertTrue(PermissionAliasResolver.aliasesFor("reports:read").contains("REPORT_EXPORT"));
    }

    @Test
    void aliasesFor_unknownPermission_returnsEmptyListNotNull() {
        // Unknown custom permissions must pass through untouched (empty alias list) so
        // owner-defined authorities aren't accidentally dropped or mutated.
        List<String> aliases = PermissionAliasResolver.aliasesFor("custom:weird-perm");
        assertNotNull(aliases);
        assertTrue(aliases.isEmpty());
    }

    @Test
    void aliasesFor_nullOrBlank_returnsEmptyList() {
        assertTrue(PermissionAliasResolver.aliasesFor(null).isEmpty());
        assertTrue(PermissionAliasResolver.aliasesFor("").isEmpty());
        assertTrue(PermissionAliasResolver.aliasesFor("   ").isEmpty());
    }

    @Test
    void expand_preservesOriginalPermissionsAndAddsAliases() {
        // Must keep the original lowercase strings AND add the legacy aliases.
        // Annotations using hasAuthority('properties:read') and hasAuthority('PROPERTY_VIEW')
        // should BOTH match after expansion.
        Set<String> expanded = PermissionAliasResolver.expand(
                List.of("properties:read", "properties:write"));

        assertTrue(expanded.contains("properties:read"), "original string must survive");
        assertTrue(expanded.contains("properties:write"), "original string must survive");
        assertTrue(expanded.contains("PROPERTY_VIEW"));
        assertTrue(expanded.contains("PROPERTY_CREATE"));
        assertTrue(expanded.contains("PROPERTY_UPDATE"));
    }

    @Test
    void expand_dropsDuplicatesAndBlanks() {
        Set<String> expanded = PermissionAliasResolver.expand(
                java.util.Arrays.asList("properties:read", "properties:read", "", null, "  "));

        // Only one of each distinct legitimate value.
        long reads = expanded.stream().filter("properties:read"::equals).count();
        assertEquals(1, reads);
        assertFalse(expanded.contains(""));
        assertFalse(expanded.contains("  "));
    }

    @Test
    void expand_nullOrEmptyInput_returnsEmptySet() {
        assertTrue(PermissionAliasResolver.expand(null).isEmpty());
        assertTrue(PermissionAliasResolver.expand(List.of()).isEmpty());
    }

    @Test
    void unitsPermissions_aliasIntoPropertyAuthorities() {
        // Current controllers reuse PROPERTY_* for unit endpoints; this mapping is what
        // makes that interop keep working.
        assertTrue(PermissionAliasResolver.aliasesFor("units:read").contains("PROPERTY_VIEW"));
        assertTrue(PermissionAliasResolver.aliasesFor("units:write").contains("PROPERTY_UPDATE"));
    }
}
