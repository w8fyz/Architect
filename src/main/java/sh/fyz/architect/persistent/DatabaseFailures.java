package sh.fyz.architect.persistent;

import java.sql.SQLException;

/**
 * Classifies why the database failed a write, for the queues that apply writes later (cached
 * repositories' flushes, relayed actions) and must decide whether to wait, retry or drop it.
 */
public final class DatabaseFailures {

    private DatabaseFailures() {
    }

    /**
     * Whether the database could not process the write at all: connection lost or refused,
     * lock or statement timeout, deadlock or serialization failure, server overloaded, out of
     * disk, shutting down or read-only (a failover in progress), or missing privileges (a
     * configuration error, not the entity's). Such a write is fine and waits for the database.
     * Recognized by exception type and by standard SQLSTATE class (08, 25, 40, 53, 57, 58, HYT,
     * and 42501), for the drivers and dialects Hibernate does not map to a specific type; MySQL,
     * MariaDB and SQLite report some of these with vendor codes only.
     */
    public static boolean isUnavailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.JDBCConnectionException
                    || t instanceof org.hibernate.exception.LockAcquisitionException
                    || t instanceof org.hibernate.PessimisticLockException
                    || t instanceof org.hibernate.QueryTimeoutException
                    || t instanceof jakarta.persistence.PessimisticLockException
                    || t instanceof jakarta.persistence.LockTimeoutException
                    || t instanceof jakarta.persistence.QueryTimeoutException
                    || t instanceof java.sql.SQLTransientException
                    || t instanceof java.sql.SQLRecoverableException) {
                return true;
            }
            if (!(t instanceof SQLException sql)) {
                continue;
            }
            String state = sql.getSQLState();
            if (state != null && (state.startsWith("08") || state.startsWith("25") || state.startsWith("40")
                    || state.startsWith("53") || state.startsWith("57") || state.startsWith("58")
                    || state.startsWith("HYT") || state.equals("42501"))) {
                return true;
            }
            if (isMySqlUnavailable(sql) || isSqliteUnavailable(sql)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The part of {@link #isUnavailable} that concerns the whole database rather than one
     * statement: no connection (lost, refused, pool exhausted), server shutting down or
     * read-only. Unlike a lock timeout or a missing privilege, nothing about the write itself
     * can make it last.
     */
    public static boolean isConnectionLost(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.JDBCConnectionException
                    || t instanceof java.sql.SQLRecoverableException
                    || t instanceof java.sql.SQLNonTransientConnectionException
                    || t instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("57P01")
                        || state.equals("57P02") || state.equals("57P03") || state.equals("25006"))) {
                    return true;
                }
                if ("HY000".equals(state) && (sql.getErrorCode() == 1290 || sql.getErrorCode() == 1836)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the failure is an optimistic-lock one: the entity's row was deleted or changed meanwhile. */
    public static boolean isStaleRow(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof jakarta.persistence.OptimisticLockException
                    || t instanceof org.hibernate.StaleStateException) {
                return true;
            }
        }
        return false;
    }

    /** Whether a database statement failed, as opposed to a bug or a Redis error. */
    public static boolean isDatabaseError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.JDBCException || t instanceof SQLException
                    || t instanceof jakarta.persistence.PersistenceException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the database refused the write for the entity's own state (NOT NULL, CHECK, a
     * value too long or out of range): replaying it fails the same way.
     */
    public static boolean isPermanentRejection(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                switch (cve.getKind()) {
                    case NOT_NULL, CHECK -> {
                        return true;
                    }
                    // OTHER: SQLite's are only told apart by its own exception, further down.
                    default -> { }
                }
            }
            if (t instanceof org.hibernate.exception.DataException
                    || t instanceof org.hibernate.PropertyValueException) {
                return true;
            }
            if (t instanceof SQLException sql && isSqlite(sql) && (sql.getErrorCode() & 0xff) == 19) {
                String message = String.valueOf(sql.getMessage());
                if (message.contains("NOT NULL constraint failed") || message.contains("CHECK constraint failed")
                        || message.contains("SQLITE_CONSTRAINT_NOTNULL") || message.contains("SQLITE_CONSTRAINT_CHECK")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * ER_OPTION_PREVENTS_STATEMENT (--read-only, --super-read-only), ER_READ_ONLY_MODE,
     * ER_DISK_FULL and ER_RECORD_FILE_FULL, all with SQLSTATE HY000, and the table or column
     * access denied errors (42000).
     */
    private static boolean isMySqlUnavailable(SQLException sql) {
        String state = sql.getSQLState();
        int code = sql.getErrorCode();
        if ("HY000".equals(state)) {
            return code == 1290 || code == 1836 || code == 1021 || code == 1114;
        }
        return "42000".equals(state) && (code == 1142 || code == 1143);
    }

    /** SQLITE_BUSY, SQLITE_LOCKED and SQLITE_FULL, base or extended result codes. */
    private static boolean isSqliteUnavailable(SQLException sql) {
        if (!isSqlite(sql)) {
            return false;
        }
        int code = sql.getErrorCode() & 0xff;
        return code == 5 || code == 6 || code == 13;
    }

    /** By class name: the SQLite driver is optional, and its vendor codes overlap other drivers'. */
    private static boolean isSqlite(SQLException sql) {
        return sql.getClass().getName().startsWith("org.sqlite.");
    }
}
