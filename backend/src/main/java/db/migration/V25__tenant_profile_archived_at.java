package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Java migration for archived_at column.
 */
public class V25__tenant_profile_archived_at extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        boolean prevAutoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute(
                        "ALTER TABLE tenant_profiles ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP NULL");
            }
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }
}