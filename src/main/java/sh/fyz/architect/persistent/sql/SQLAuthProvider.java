package sh.fyz.architect.persistent.sql;

public abstract class SQLAuthProvider {

    public abstract String getDialect();

    public abstract String getDriver();

    public abstract String getUrl();

    protected static void validateHost(String hostname) {
        if (hostname == null) {
            throw new IllegalArgumentException("Invalid hostname: hostname cannot be null");
        }
    }

    protected static void validateDatabase(String database) {
        if (database == null) {
            throw new IllegalArgumentException("Invalid database name: database name cannot be null");
        }
    }

    protected static void validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port number: " + port);
        }
    }
}
