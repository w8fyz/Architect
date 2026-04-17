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
            Path file = migrationDirectory.resolve(safeName + ".sql");
            Files.writeString(file, ddl);
            LOG.info("Migration created: " + file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create migration file", e);
        }
    }

    public void executeMigration(String name) {
        String safeName = name.endsWith(".sql") ? name : name + ".sql";
        Path file = migrationDirectory.resolve(safeName);
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

    public void clearDatabase() {
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
        String safeName = name.endsWith(".sql") ? name : name + ".sql";
        Path file = migrationDirectory.resolve(safeName);
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

    private List<String> parseSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : 0;

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
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
}
