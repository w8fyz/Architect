package sh.fyz.architect.persistent.sql;

import java.util.regex.Pattern;

public abstract class SQLAuthProvider {

    public abstract String getDialect();

    public abstract String getDriver();

    public abstract String getUrl();

    protected static void validateHost(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("Invalid hostname: must not be null or blank");
        }
    }

    protected static void validateDatabase(String database) {
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("Invalid database name: must not be null or blank");
        }
    }

    protected static void validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port number: " + port);
        }
    }
}
