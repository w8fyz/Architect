package sh.fyz.architect.migration;

import org.hibernate.Session;
import sh.fyz.architect.Architect;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.SessionManager;
import sh.fyz.architect.persistent.sql.SQLAuthProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class MigrationManager {

    private static final Logger LOG = Logger.getLogger(MigrationManager.class.getName());
    public static final String CLEAR_CONFIRMATION = "CONFIRM_DROP_ALL";

    private final SchemaGenerator schemaGenerator;
    private final DatabaseInspector databaseInspector;
    private final Path migrationDirectory;
    private final String dialect;

    public MigrationManager(Architect architect, Path migrationDirectory) {
        DatabaseCredentials creds = architect.getDatabaseCredentials();
        if (creds == null) {
            throw new IllegalStateException("DatabaseCredentials must be set on Architect before using MigrationManager");
        }
        SQLAuthProvider authProvider = creds.getSQLAuthProvider();
        this.schemaGenerator = new SchemaGenerator(authProvider, creds.getUser(), creds.getPassword());
        this.dialect = authProvider.getDialect();
        this.databaseInspector = new DatabaseInspector(dialect);
        this.migrationDirectory = migrationDirectory;
    }

    public String generateSchema() {
        return schemaGenerator.generateDDL();
    }

    public Path createMigration(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Migration name cannot be null or blank");
        }
        String safeName = name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        String ddl = schemaGenerator.generateDDL();

        try {
            Files.createDirectories(migrationDirectory);
            Path file = resolveMigrationFile(safeName);
            Files.writeString(file, ddl);
            LOG.info("Migration created: " + file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create migration file", e);
        }
    }

    public void executeMigration(String name) {
        Path file = resolveMigrationFile(name);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Migration file not found: " + file.toAbsolutePath());
        }

        String sql;
        try {
            sql = Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read migration file: " + file, e);
        }

        executeSql(sql);
        LOG.info("Migration executed: " + file.getFileName());
    }

    public void executeSql(String sql) {
        List<String> statements = parseSqlStatements(sql);
        if (statements.isEmpty()) {
            LOG.warning("No SQL statements to execute");
            return;
        }

        try (Session session = SessionManager.get().getSession()) {
            session.doWork(connection -> {
                boolean wasAutoCommit = connection.getAutoCommit();
                try {
                    connection.setAutoCommit(false);
                    try (Statement stmt = connection.createStatement()) {
                        for (String s : statements) {
                            stmt.execute(s);
                        }
                    }
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(wasAutoCommit);
                }
            });
        }
    }

    /**
     * @deprecated Use {@link #clearDatabase(String)} with the confirmation token to avoid
     *             accidental destructive calls.
     */
    @Deprecated
    public void clearDatabase() {
        LOG.warning("clearDatabase() called without confirmation token. Use clearDatabase(\"" + CLEAR_CONFIRMATION + "\") instead.");
        doClearDatabase();
    }

    /**
     * Drops every table (or schema) in the database. Requires the exact confirmation
     * token {@code CONFIRM_DROP_ALL} to prevent accidental destructive calls.
     */
    public void clearDatabase(String confirmation) {
        if (!CLEAR_CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException(
                "clearDatabase requires confirmation token \"" + CLEAR_CONFIRMATION + "\""
            );
        }
        doClearDatabase();
    }

    private void doClearDatabase() {
        try (Session session = SessionManager.get().getSession()) {
            session.doWork(this::doClear);
        }
        LOG.info("Database cleared successfully");
    }

    private void doClear(Connection connection) throws SQLException {
        boolean wasAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            String dialectLower = dialect.toLowerCase();
            try (Statement stmt = connection.createStatement()) {
                if (dialectLower.contains("postgresql")) {
                    stmt.execute("DROP SCHEMA public CASCADE");
                    stmt.execute("CREATE SCHEMA public");
                } else if (dialectLower.contains("mysql") || dialectLower.contains("mariadb")) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
                    List<String> tables = getTableNamesViaJdbc(connection);
                    for (String table : tables) {
                        stmt.execute("DROP TABLE IF EXISTS `" + table + "`");
                    }
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
                } else if (dialectLower.contains("h2")) {
                    stmt.execute("DROP ALL OBJECTS");
                } else {
                    List<String> tables = getTableNamesViaJdbc(connection);
                    for (String table : tables) {
                        stmt.execute("DROP TABLE IF EXISTS \"" + table + "\" CASCADE");
                    }
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

    private List<String> getTableNamesViaJdbc(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        var meta = connection.getMetaData();
        String schema = dialect.toLowerCase().contains("postgresql") ? "public" : null;
        try (var rs = meta.getTables(null, schema, null, new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    public List<String> listMigrations() {
        if (!Files.isDirectory(migrationDirectory)) {
            return Collections.emptyList();
        }
        try (Stream<Path> files = Files.list(migrationDirectory)) {
            return files
                    .filter(p -> p.toString().endsWith(".sql"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list migrations", e);
        }
    }

    public String readMigrationContent(String name) {
        Path file = resolveMigrationFile(name);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Migration file not found: " + file.toAbsolutePath());
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read migration file", e);
        }
    }

    public List<DatabaseInspector.TableInfo> listTables() {
        return databaseInspector.listTables();
    }

    public DatabaseInspector.TableSchema getTableSchema(String tableName) {
        return databaseInspector.getTableSchema(tableName);
    }

    public DatabaseInspector.TableData getTableData(String tableName, int page, int pageSize) {
        return databaseInspector.getTableData(tableName, page, pageSize);
    }

    public Path getMigrationDirectory() {
        return migrationDirectory;
    }

    /**
     * Resolves {@code name} against the migration directory and ensures the resulting
     * path cannot escape it (protection against path-traversal like {@code ../../etc/passwd}).
     */
    private Path resolveMigrationFile(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Migration name must not be null or blank");
        }
        String safeName = name.endsWith(".sql") ? name : name + ".sql";
        Path baseAbs = migrationDirectory.toAbsolutePath().normalize();
        Path resolved = baseAbs.resolve(safeName).normalize();
        if (!resolved.startsWith(baseAbs)) {
            throw new IllegalArgumentException(
                "Migration path escapes migration directory: " + name
            );
        }
        return resolved;
    }

    /**
     * Splits a SQL script into individual statements at top-level semicolons.
     * Respects single-quoted strings, double-quoted identifiers, line ({@code --})
     * and block ({@code / * ... * /}) comments, and PostgreSQL dollar-quoted
     * string literals ({@code $$...$$} or {@code $tag$...$tag$}).
     */
    private List<String> parseSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        String dollarTag = null;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : 0;

            if (dollarTag != null) {
                current.append(c);
                if (c == '$' && sql.startsWith(dollarTag, i)) {
                    current.append(sql, i + 1, i + dollarTag.length());
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                }
                continue;
            }

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    current.append(c);
                }
                continue;
            }

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }

            if (c == '-' && next == '-' && !inSingleQuote && !inDoubleQuote) {
                inLineComment = true;
                i++;
                continue;
            }

            if (c == '/' && next == '*' && !inSingleQuote && !inDoubleQuote) {
                inBlockComment = true;
                i++;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '$' && !inSingleQuote && !inDoubleQuote) {
                String tag = tryReadDollarTag(sql, i);
                if (tag != null) {
                    current.append(tag);
                    i += tag.length() - 1;
                    dollarTag = tag;
                    continue;
                }
            }

            if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        String last = current.toString().trim();
        if (!last.isEmpty()) {
            statements.add(last);
        }

        return statements;
    }

    /**
     * If the character at {@code start} is {@code $} and starts a valid dollar-quote
     * opener ({@code $$} or {@code $identifier$}), returns the full tag including the
     * surrounding dollar signs. Returns {@code null} otherwise.
     */
    private static String tryReadDollarTag(String sql, int start) {
        if (sql.charAt(start) != '$') return null;
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '$') {
                return sql.substring(start, i + 1);
            }
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return null;
            }
            i++;
        }
        return null;
    }
}
