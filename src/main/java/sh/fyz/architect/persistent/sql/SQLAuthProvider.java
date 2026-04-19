package sh.fyz.architect.persistent.sql;

import java.util.regex.Pattern;

public abstract class SQLAuthProvider {

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z0-9._\\-]+");

    public abstract String getDialect();

    public abstract String getDriver();

    public abstract String getUrl();

    protected static void validateHost(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("Invalid hostname: must not be null or blank");
        }
        if (!SAFE_IDENT.matcher(hostname).matches()) {
            throw new IllegalArgumentException(
                "Invalid hostname: only [A-Za-z0-9._-] characters are allowed (got: '" + hostname + "')"
            );
        }
    }

    protected static void validateDatabase(String database) {
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("Invalid database name: must not be null or blank");
        }
        if (!SAFE_IDENT.matcher(database).matches()) {
            throw new IllegalArgumentException(
                "Invalid database name: only [A-Za-z0-9._-] characters are allowed (got: '" + database + "')"
            );
        }
    }

    protected static void validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port number: " + port);
        }
    }
}
