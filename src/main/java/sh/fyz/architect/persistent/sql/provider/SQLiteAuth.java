package sh.fyz.architect.persistent.sql.provider;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;
import sh.fyz.architect.persistent.sql.TlsMode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

public class SQLiteAuth extends SQLAuthProvider {

    private static final Pattern URL_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*:");

    private final String databasePath;

    public SQLiteAuth(String databasePath) {
        this.databasePath = validateAndNormalizePath(databasePath);
    }

    public SQLiteAuth withTls(TlsMode mode) {
        throw new UnsupportedOperationException("SQLite is a local file database and does not support TLS");
    }

    private static String validateAndNormalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Invalid database path: must not be null or blank");
        }
        if (path.contains(";") || path.contains("\u0000")) {
            throw new IllegalArgumentException("Invalid database path: illegal character");
        }
        if (URL_SCHEME.matcher(path).find()) {
            throw new IllegalArgumentException("Invalid database path: URL schemes are not allowed");
        }
        if (":memory:".equals(path)) {
            return path;
        }
        try {
            Path normalized = Paths.get(path).normalize();
            return normalized.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid database path: " + e.getMessage());
        }
    }

    @Override
    public String getDialect() {
        return "org.hibernate.dialect.SQLiteDialect";
    }

    @Override
    public String getDriver() {
        return "org.sqlite.JDBC";
    }

    @Override
    public String getUrl() {
        return "jdbc:sqlite:" + databasePath;
    }
}
