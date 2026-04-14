package sh.fyz.architect.persistant.sql;

import java.util.regex.Pattern;

public abstract class SQLAuthProvider {

    private static final Pattern SAFE_HOST_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern SAFE_DB_PATTERN = Pattern.compile("^[a-zA-Z0-9._/-]+$");

    public abstract String getDialect();

    public abstract String getDriver();

    public abstract String getUrl();

    protected static void validateHost(String hostname) {
        if (hostname == null || !SAFE_HOST_PATTERN.matcher(hostname).matches()) {
            throw new IllegalArgumentException("Invalid hostname: must contain only alphanumeric characters, dots, hyphens, and underscores");
        }
    }

    protected static void validateDatabase(String database) {
        if (database == null || !SAFE_DB_PATTERN.matcher(database).matches()) {
            throw new IllegalArgumentException("Invalid database name: must contain only alphanumeric characters, dots, slashes, hyphens, and underscores");
        }
        if (database.contains("?") || database.contains("&") || database.contains("=") || database.contains(";")) {
            throw new IllegalArgumentException("Database name must not contain query parameters");
        }
    }

    protected static void validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port number: " + port);
        }
    }
}
