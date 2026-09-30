package sh.fyz.architect.migration;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * A throwaway database rebuilt from the migration files, used as the reference a diff is computed
 * against.
 *
 * <p>Diffing straight against production would work, but it makes the generated migration depend
 * on whatever production happens to look like today — including any drift. Replaying the
 * migrations into an empty database instead gives a reference that is a pure function of the
 * repository: the resulting diff is exactly "what the committed migrations produce" versus "what
 * the entity model now says", and two people on the same commit get the same file.</p>
 *
 * <h2>Safety</h2>
 * <p>This class <b>wipes every table</b> in the database it is pointed at. {@link #prepare} refuses
 * to run when the shadow URL matches the application's own, which is the mistake that would cost
 * a production database. That check is not optional and must not be relaxed into a warning.</p>
 */
public class ShadowDatabase {

    private static final Logger LOG = Logger.getLogger(ShadowDatabase.class.getName());

    private final SQLAuthProvider shadow;
    private final String user;
    private final String password;

    public ShadowDatabase(SQLAuthProvider shadow, String user, String password) {
        this.shadow = shadow;
        this.user = user;
        this.password = password;
    }

    public SQLAuthProvider provider() { return shadow; }
    public String user() { return user; }
    public String password() { return password; }

    /**
     * Empties the shadow database and replays every migration into it, in version order.
     *
     * @param mainUrl the application's own JDBC URL, refused as a shadow target
     * @param migrationDirectory where the {@code V<version>__<description>.sql} files live
     * @return the migrations replayed, in the order they were applied
     * @throws IllegalArgumentException if the shadow target is the application's own database
     */
    public List<String> prepare(String mainUrl, Path migrationDirectory) {
        assertNotMainDatabase(mainUrl);

        clear();

        List<String> replayed = new ArrayList<>();
        for (String filename : orderedMigrations(migrationDirectory)) {
            String sql;
            try {
                sql = Files.readString(migrationDirectory.resolve(filename));
            } catch (Exception e) {
                throw new RuntimeException("Failed to read migration " + filename, e);
            }
            execute(MigrationManager.parseSqlStatements(sql), filename);
            replayed.add(filename);
        }
        LOG.info("Shadow database rebuilt from " + replayed.size() + " migration(s)");
        return replayed;
    }

    /**
     * The guard. Compares normalised JDBC URLs — query parameters and H2 {@code ;} settings
     * stripped, case folded, default ports made explicit — so that a TLS flag, a trailing
     * parameter or an omitted {@code :5432} cannot disguise the production database as a shadow.
     */
    public void assertNotMainDatabase(String mainUrl) {
        if (mainUrl == null) {
            return;
        }
        String a = normalise(mainUrl);
        String b = normalise(shadow.getUrl());
        if (a.equals(b)) {
            throw new IllegalArgumentException(
                    "Refusing to use the application's own database as a shadow: " + b
                            + ". The shadow is wiped before use — point it at a scratch database.");
        }
    }

    private static String normalise(String jdbcUrl) {
        String url = jdbcUrl.trim().toLowerCase(Locale.ROOT);
        // Parameters start at '?' (PostgreSQL, MySQL, MariaDB) or at ';' (H2).
        int query = url.indexOf('?');
        int settings = url.indexOf(';');
        if (settings >= 0 && (query < 0 || settings < query)) {
            query = settings;
        }
        if (query >= 0) {
            url = url.substring(0, query);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return withExplicitDefaultPort(url);
    }

    /**
     * {@code jdbc:postgresql://host/db} and {@code jdbc:postgresql://host:5432/db} are the same
     * database; the guard must not be fooled by the spelling difference.
     */
    private static String withExplicitDefaultPort(String url) {
        int defaultPort;
        if (url.startsWith("jdbc:postgresql:")) {
            defaultPort = 5432;
        } else if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) {
            defaultPort = 3306;
        } else if (url.startsWith("jdbc:h2:tcp:") || url.startsWith("jdbc:h2:ssl:")) {
            // H2Auth always spells the port out, a hand-written shadow URL may not.
            defaultPort = 9092;
        } else {
            return url;
        }
        int authorityStart = url.indexOf("//");
        if (authorityStart < 0) {
            return url;
        }
        int hostStart = authorityStart + 2;
        int hostEnd = url.indexOf('/', hostStart);
        if (hostEnd < 0) {
            hostEnd = url.length();
        }
        String authority = url.substring(hostStart, hostEnd);
        // A colon after the last ']' (IPv6) or anywhere in a plain host means the port is present.
        int portColon = authority.lastIndexOf(':');
        boolean hasPort = portColon > authority.lastIndexOf(']');
        if (hasPort) {
            return url;
        }
        return url.substring(0, hostEnd) + ":" + defaultPort + url.substring(hostEnd);
    }

    /** Migration files in version order, ignoring anything not following the convention. */
    private List<String> orderedMigrations(Path migrationDirectory) {
        List<MigrationVersion> versions = new ArrayList<>();
        if (!Files.isDirectory(migrationDirectory)) {
            return List.of();
        }
        try (var files = Files.list(migrationDirectory)) {
            files.filter(p -> p.toString().endsWith(".sql"))
                    .map(p -> MigrationVersion.parse(p.getFileName().toString()))
                    .filter(java.util.Objects::nonNull)
                    .forEach(versions::add);
        } catch (Exception e) {
            throw new RuntimeException("Failed to list migrations in " + migrationDirectory, e);
        }
        versions.sort(MigrationVersion::compareTo);
        return versions.stream().map(MigrationVersion::filename).toList();
    }

    /** Drops everything. PostgreSQL gets the schema recreated; other dialects are dropped table by table. */
    public void clear() {
        withConnection(connection -> {
            boolean wasAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                String dialect = shadow.getDialect().toLowerCase(Locale.ROOT);
                try (Statement stmt = connection.createStatement()) {
                    if (dialect.contains("postgresql")) {
                        stmt.execute("DROP SCHEMA public CASCADE");
                        stmt.execute("CREATE SCHEMA public");
                    } else if (dialect.contains("mysql") || dialect.contains("mariadb")) {
                        stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
                        for (String table : tableNames(connection)) {
                            stmt.execute("DROP TABLE IF EXISTS `" + table + "`");
                        }
                        stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
                    } else if (dialect.contains("h2")) {
                        stmt.execute("DROP ALL OBJECTS");
                    } else if (dialect.contains("sqlite")) {
                        // SQLite's DROP TABLE has no CASCADE.
                        for (String table : tableNames(connection)) {
                            stmt.execute("DROP TABLE IF EXISTS \"" + table + "\"");
                        }
                    } else {
                        for (String table : tableNames(connection)) {
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
        });
    }

    private void execute(List<String> statements, String label) {
        if (statements.isEmpty()) {
            return;
        }
        withConnection(connection -> {
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
                throw new SQLException("Replaying " + label + " into the shadow database failed. "
                        + "That migration is not valid against the schema its predecessors build: "
                        + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(wasAutoCommit);
            }
        });
    }

    private List<String> tableNames(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getTables(null, null, null, new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    private interface Work {
        void run(Connection connection) throws SQLException;
    }

    /**
     * Plain JDBC on purpose: {@code SessionManager} is a singleton bound to the application's own
     * database, so the shadow cannot borrow a connection from it.
     */
    private void withConnection(Work work) {
        try (Connection connection = DriverManager.getConnection(shadow.getUrl(), user, password)) {
            work.run(connection);
        } catch (SQLException e) {
            throw new RuntimeException("Shadow database operation failed: " + e.getMessage(), e);
        }
    }
}
