package sh.fyz.architect.test;

import jakarta.persistence.OptimisticLockException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sh.fyz.architect.persistent.DatabaseFailures;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DatabaseFailures")
public class DatabaseFailuresTest {

    private static RuntimeException wrapped(SQLException sql) {
        return new RuntimeException("flush failed", sql);
    }

    @Test
    @DisplayName("connexion perdue : indisponible et connexion perdue")
    void testConnectionLost() {
        Throwable[] lost = {
                wrapped(new SQLException("refused", "08001")),
                wrapped(new SQLException("admin shutdown", "57P01")),
                wrapped(new SQLException("read only", "25006")),
                wrapped(new SQLException("--read-only", "HY000", 1290)),
                wrapped(new SQLTransientConnectionException("pool timeout")),
                new JDBCConnectionException("lost", new SQLException("lost", "08006")),
        };
        for (Throwable t : lost) {
            assertTrue(DatabaseFailures.isConnectionLost(t), t.getCause() + " is a lost connection");
            assertTrue(DatabaseFailures.isUnavailable(t), t.getCause() + " is unavailable");
        }
    }

    @Test
    @DisplayName("echec lie a l'instruction : indisponible, mais pas connexion perdue")
    void testStatementLevelUnavailability() {
        Throwable[] statementLevel = {
                wrapped(new SQLException("statement timeout", "57014")),
                wrapped(new SQLException("deadlock", "40P01")),
                wrapped(new SQLException("permission denied", "42501")),
                wrapped(new SQLException("table access denied", "42000", 1142)),
                wrapped(new SQLException("disk full", "HY000", 1021)),
                wrapped(new SQLException("table is full", "HY000", 1114)),
        };
        for (Throwable t : statementLevel) {
            assertTrue(DatabaseFailures.isUnavailable(t), t.getCause() + " is unavailable");
            assertFalse(DatabaseFailures.isConnectionLost(t), t.getCause() + " is not a lost connection");
        }
    }

    @Test
    @DisplayName("NOT NULL / CHECK : rejet definitif ; cle etrangere : non")
    void testPermanentRejections() {
        SQLException sql = new SQLException("violation", "23502");
        assertTrue(DatabaseFailures.isPermanentRejection(new ConstraintViolationException(
                "not null", sql, "insert", ConstraintViolationException.ConstraintKind.NOT_NULL, "c")));
        assertTrue(DatabaseFailures.isPermanentRejection(new ConstraintViolationException(
                "check", sql, "insert", ConstraintViolationException.ConstraintKind.CHECK, "c")));
        assertFalse(DatabaseFailures.isPermanentRejection(new ConstraintViolationException(
                "fk", new SQLException("fk", "23503"), "insert", ConstraintViolationException.ConstraintKind.FOREIGN_KEY, "c")));
        assertFalse(DatabaseFailures.isUnavailable(new ConstraintViolationException(
                "fk", new SQLException("fk", "23503"), "insert", ConstraintViolationException.ConstraintKind.FOREIGN_KEY, "c")));
    }

    @Test
    @DisplayName("ligne perimee et erreurs hors base")
    void testStaleRowAndNonDatabaseErrors() {
        assertTrue(DatabaseFailures.isStaleRow(new RuntimeException(new OptimisticLockException("stale"))));
        assertFalse(DatabaseFailures.isDatabaseError(new IllegalStateException("bug")));
        assertTrue(DatabaseFailures.isDatabaseError(wrapped(new SQLException("fk", "23503"))));
    }

    @Test
    @DisplayName("les codes vendeur MySQL ne sont pas pris pour des codes SQLite")
    void testVendorCodesAreNotMistakenForSqlite() {
        // 5 is SQLITE_BUSY, but only for the SQLite driver's own exception.
        assertFalse(DatabaseFailures.isUnavailable(wrapped(new SQLException("other driver", "HY000", 5))));
    }
}
