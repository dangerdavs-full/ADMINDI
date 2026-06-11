package com.admindi.backend.controller;

import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guardrail for {@link SuperAdminUserController#computeAuditOwnerScope(UserEntity)}.
 *
 * <p>This invariant broke once in production: the cascade {@code deleteByOwnerId} on
 * {@code audit_events} ran in the same transaction as the final
 * {@code SUPERADMIN_USER_DELETE} insert, and Hibernate reordered the flush so the
 * DELETE swept away the INSERT. The sysadmin's record of the action simply
 * disappeared from the DB — no trace of who purged which owner.
 *
 * <p>The semantic fix is: when the deleted user IS the owner, the audit row is
 * <em>the sysadmin's action record</em>, not a row scoped to the dead owner — so
 * its {@code owner_id} must be null. This test keeps that contract pinned.
 */
class SuperAdminUserControllerAuditScopeTest {

    @Test
    void ownerTarget_auditScopeIsNull_soItSurvivesCascade() {
        UserEntity owner = new UserEntity();
        owner.setId("owner-123");
        owner.setOwnerId("owner-123");
        owner.setRole(Role.OWNER);

        String scope = SuperAdminUserController.computeAuditOwnerScope(owner);

        assertNull(scope,
                "OWNER hard-delete audit must be ownerless or the cascade's "
                        + "auditEventRepository.deleteByOwnerId() in the same tx will "
                        + "remove the sysadmin's action record.");
    }

    @Test
    void staffTarget_auditScopeIsOwnerId() {
        UserEntity staff = new UserEntity();
        staff.setId("staff-1");
        staff.setOwnerId("owner-boss");
        staff.setRole(Role.PROPERTY_ADMIN);

        assertEquals("owner-boss",
                SuperAdminUserController.computeAuditOwnerScope(staff));
    }

    @Test
    void tenantTarget_auditScopeIsOwnerId() {
        UserEntity tenant = new UserEntity();
        tenant.setId("tenant-1");
        tenant.setOwnerId("owner-boss");
        tenant.setRole(Role.TENANT);

        assertEquals("owner-boss",
                SuperAdminUserController.computeAuditOwnerScope(tenant));
    }

    @Test
    void agentTarget_withoutOwnerId_returnsNull() {
        UserEntity agent = new UserEntity();
        agent.setId("agent-1");
        agent.setRole(Role.REAL_ESTATE_AGENT);

        assertNull(SuperAdminUserController.computeAuditOwnerScope(agent));
    }

    @Test
    void nullTarget_returnsNull() {
        assertNull(SuperAdminUserController.computeAuditOwnerScope(null));
    }
}
