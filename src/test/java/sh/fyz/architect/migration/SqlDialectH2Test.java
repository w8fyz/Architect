package sh.fyz.architect.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2-specific clearing, on an in-memory database. In this package: {@link SqlDialect} is
 * package-private.
 */
@DisplayName("SqlDialect (H2)")
public class SqlDialectH2Test {

    private static final String DIALECT = "org.hibernate.dialect.H2Dialect";

    @Test
    @DisplayName("une shadow H2 est vidée de ses autres schémas, la base applicative non")
    void testOtherSchemasAreOnlyDroppedForShadows() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:dialect_" + System.nanoTime())) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE app_table (id INT PRIMARY KEY)");
                stmt.execute("CREATE SCHEMA reporting");
                stmt.execute("CREATE TABLE reporting.report (id INT PRIMARY KEY)");
            }

            SqlDialect.dropAll(connection, DIALECT, false);
            assertFalse(tableExists(connection, "PUBLIC", "APP_TABLE"));
            assertTrue(schemaExists(connection, "REPORTING"), "the application's other schemas are left alone");

            SqlDialect.dropAll(connection, DIALECT, true);
            assertFalse(schemaExists(connection, "REPORTING"));
            // Replaying the same migrations works again.
            try (Statement stmt = connection.createStatement()) {
                assertDoesNotThrow(() -> stmt.execute("CREATE SCHEMA reporting"));
            }
            assertEquals("PUBLIC", connection.getSchema());
        }
    }

    @Test
    @DisplayName("shadow H2 sur un autre schéma que PUBLIC : PUBLIC est vidé, le schéma courant reste")
    void testPublicIsEmptiedWhenNotTheCurrentSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:dialect_" + System.nanoTime())) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE public.legacy (id INT PRIMARY KEY)");
                stmt.execute("CREATE SCHEMA app");
            }
            connection.setSchema("APP");
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE app_table (id INT PRIMARY KEY)");
            }

            SqlDialect.dropAll(connection, DIALECT, true);
            assertFalse(tableExists(connection, "PUBLIC", "LEGACY"));
            assertFalse(tableExists(connection, "APP", "APP_TABLE"));
            assertTrue(schemaExists(connection, "APP"), "the current schema is emptied, not dropped");
            assertEquals("APP", connection.getSchema());
        }
    }

    private static boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(null, schema, table, null)) {
            return rs.next();
        }
    }

    private static boolean schemaExists(Connection connection, String schema) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getSchemas(null, schema)) {
            return rs.next();
        }
    }
}
