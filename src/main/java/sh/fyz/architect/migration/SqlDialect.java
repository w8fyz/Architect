package sh.fyz.architect.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dialect-dependent SQL shared by the migration tools, which run plain JDBC outside Hibernate. */
final class SqlDialect {

    private SqlDialect() {}

    private static boolean isMySqlFamily(String dialect) {
        String lower = dialect.toLowerCase(Locale.ROOT);
        return lower.contains("mysql") || lower.contains("mariadb");
    }

    /** Quoted in the dialect's syntax: without ANSI_QUOTES, MySQL reads "x" as a string. */
    static String quote(String dialect, String identifier) {
        if (isMySqlFamily(dialect)) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Drops every table, view and sequence of the application's schema or database in one
     * transaction, leaving the others alone (also MySQL / MariaDB's stored routines, and H2's
     * domains, aliases, constants and synonyms). PostgreSQL gets the {@code public} schema
     * recreated.
     *
     * @param otherSchemas also drop H2's other user schemas (not {@code PUBLIC}): a shadow
     *                     database whose migrations run {@code CREATE SCHEMA} must be empty again
     *                     for the next replay. Never for the application's database.
     */
    static void dropAll(Connection connection, String dialect, boolean otherSchemas) throws SQLException {
        String lower = dialect.toLowerCase(Locale.ROOT);
        boolean wasAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                if (lower.contains("postgresql")) {
                    stmt.execute("DROP SCHEMA public CASCADE");
                    stmt.execute("CREATE SCHEMA public");
                } else if (isMySqlFamily(dialect)) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
                    try {
                        dropEach(stmt, "DROP VIEW IF EXISTS ", objectNames(connection, dialect, "VIEW"), dialect, "", "");
                        dropEach(stmt, "DROP TABLE IF EXISTS ", tableNames(connection, dialect), dialect, "", "");
                        // MariaDB's sequences.
                        dropEach(stmt, "DROP SEQUENCE IF EXISTS ", objectNames(connection, dialect, "SEQUENCE"), dialect, "", "");
                        dropEach(stmt, "DROP FUNCTION IF EXISTS ", mySqlRoutines(connection, "FUNCTION"), dialect, "", "");
                        dropEach(stmt, "DROP PROCEDURE IF EXISTS ", mySqlRoutines(connection, "PROCEDURE"), dialect, "", "");
                    } finally {
                        // A session variable: a pool does not reset it, and the next borrower
                        // of this connection would run without foreign-key enforcement.
                        stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
                    }
                } else if (lower.contains("h2")) {
                    // Not DROP ALL OBJECTS: it drops every schema, the current one included, and
                    // a URL with ;SCHEMA=APP could not connect any more.
                    String current = connection.getSchema();
                    if (otherSchemas) {
                        dropEach(stmt, "DROP SCHEMA IF EXISTS ", h2Objects(connection,
                                "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME <> ?"
                                        + " AND SCHEMA_NAME NOT IN ('INFORMATION_SCHEMA', 'PUBLIC')"), dialect, "", " CASCADE");
                        // PUBLIC cannot be dropped: emptied instead, when it is not the current schema.
                        if (!"PUBLIC".equals(current)) {
                            connection.setSchema("PUBLIC");
                            try {
                                dropH2Schema(connection, stmt, dialect);
                            } finally {
                                connection.setSchema(current);
                            }
                        }
                    }
                    dropH2Schema(connection, stmt, dialect);
                } else {
                    // SQLite's DROP TABLE has no CASCADE.
                    String cascade = lower.contains("sqlite") ? "" : " CASCADE";
                    dropEach(stmt, "DROP VIEW IF EXISTS ", objectNames(connection, dialect, "VIEW"), dialect, "", cascade);
                    dropEach(stmt, "DROP TABLE IF EXISTS ", tableNames(connection, dialect), dialect, "", cascade);
                }
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(wasAutoCommit);
        }
    }

    /** Drops the objects of the connection's current H2 schema. */
    private static void dropH2Schema(Connection connection, Statement stmt, String dialect) throws SQLException {
        String schema = quote(dialect, connection.getSchema()) + ".";
        dropEach(stmt, "DROP VIEW IF EXISTS ", objectNames(connection, dialect, "VIEW"), dialect, schema, " CASCADE");
        dropEach(stmt, "DROP SYNONYM IF EXISTS ", objectNames(connection, dialect, "SYNONYM"), dialect, schema, "");
        dropEach(stmt, "DROP TABLE IF EXISTS ", tableNames(connection, dialect), dialect, schema, " CASCADE");
        dropEach(stmt, "DROP TABLE IF EXISTS ", objectNames(connection, dialect, "GLOBAL TEMPORARY"),
                dialect, schema, " CASCADE");
        dropEach(stmt, "DROP SEQUENCE IF EXISTS ", h2Objects(connection,
                "SELECT SEQUENCE_NAME FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ?"), dialect, schema, "");
        dropEach(stmt, "DROP DOMAIN IF EXISTS ", h2Objects(connection,
                "SELECT DOMAIN_NAME FROM INFORMATION_SCHEMA.DOMAINS WHERE DOMAIN_SCHEMA = ?"), dialect, schema, " CASCADE");
        dropEach(stmt, "DROP AGGREGATE IF EXISTS ", h2Objects(connection,
                "SELECT DISTINCT ROUTINE_NAME FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA = ?"
                        + " AND ROUTINE_TYPE = 'AGGREGATE'"), dialect, schema, "");
        dropEach(stmt, "DROP ALIAS IF EXISTS ", h2Objects(connection,
                "SELECT DISTINCT ROUTINE_NAME FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA = ?"
                        + " AND ROUTINE_TYPE <> 'AGGREGATE'"), dialect, schema, "");
        dropEach(stmt, "DROP CONSTANT IF EXISTS ", h2Objects(connection,
                "SELECT CONSTANT_NAME FROM INFORMATION_SCHEMA.CONSTANTS WHERE CONSTANT_SCHEMA = ?"), dialect, schema, "");
    }

    private static void dropEach(Statement stmt, String drop, List<String> names, String dialect,
                                 String qualifier, String suffix) throws SQLException {
        for (String name : names) {
            stmt.execute(drop + qualifier + quote(dialect, name) + suffix);
        }
    }

    /** The stored functions or procedures of the connection's MySQL / MariaDB database. */
    private static List<String> mySqlRoutines(Connection connection, String type) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT ROUTINE_NAME FROM information_schema.ROUTINES"
                + " WHERE ROUTINE_SCHEMA = DATABASE() AND ROUTINE_TYPE = ?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    /** Objects of H2's current schema listed by {@code sql}, whose one parameter is the schema. */
    private static List<String> h2Objects(Connection connection, String sql) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, connection.getSchema());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    /**
     * The schema holding the application's tables, as stored in the database, for
     * {@code DatabaseMetaData} methods taking a schema name (the pattern-taking ones go through
     * {@link #forEachColumn} and {@link #tableNames}). They are also given
     * {@code connection.getCatalog()}: MySQL Connector/J reads a null catalog as every database
     * the user can see. On H2 a null schema would also list its {@code INFORMATION_SCHEMA}
     * tables; MySQL, MariaDB and SQLite have no schema level.
     */
    static String schema(Connection connection, String dialect) throws SQLException {
        String lower = dialect.toLowerCase(Locale.ROOT);
        if (lower.contains("postgresql")) {
            return "public";
        }
        return lower.contains("h2") ? connection.getSchema() : null;
    }

    /** A consumer of result set rows. */
    interface RowConsumer {
        void accept(ResultSet row) throws SQLException;
    }

    /** Runs {@code consumer} on each column row ({@code DatabaseMetaData.getColumns}) of {@code table}. */
    static void forEachColumn(Connection connection, String dialect, String table, RowConsumer consumer)
            throws SQLException {
        String schema = schema(connection, dialect);
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(),
                pattern(connection, dialect, schema), pattern(connection, dialect, table), null)) {
            while (rs.next()) {
                if (table.equals(rs.getString("TABLE_NAME")) && inSchema(rs, schema)) {
                    consumer.accept(rs);
                }
            }
        }
    }

    private static boolean inSchema(ResultSet rs, String schema) throws SQLException {
        return schema == null || schema.equals(rs.getString("TABLE_SCHEM"));
    }

    /**
     * A name as a {@code DatabaseMetaData} search pattern, where {@code _} and {@code %} are
     * wildcards: {@code rel_pets} also matches a table {@code relxpets}, so the rows returned are
     * still filtered by exact name. The escape is left out on MySQL and MariaDB, whose drivers
     * report {@code \} but do not honour it for a backslash, nor at all (Connector/J) under
     * {@code NO_BACKSLASH_ESCAPES}: an escaped name would then match nothing. Null stays null.
     */
    private static String pattern(Connection connection, String dialect, String name) throws SQLException {
        String escape = connection.getMetaData().getSearchStringEscape();
        if (name == null || isMySqlFamily(dialect) || escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    /** The tables of the connection's own database. */
    static List<String> tableNames(Connection connection, String dialect) throws SQLException {
        return objectNames(connection, dialect, "TABLE");
    }

    /** The objects of the given {@code DatabaseMetaData} table type in the connection's own database. */
    private static List<String> objectNames(Connection connection, String dialect, String type) throws SQLException {
        String schema = schema(connection, dialect);
        List<String> names = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getTables(connection.getCatalog(),
                pattern(connection, dialect, schema), null, new String[]{type})) {
            while (rs.next()) {
                if (inSchema(rs, schema)) {
                    names.add(rs.getString("TABLE_NAME"));
                }
            }
        }
        return names;
    }
}
