package sh.fyz.architect.persistent.sql.provider;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;

public class PostgreSQLAuth extends SQLAuthProvider {

    private final String hostname;
    private final String database;
    private final int port;

    public PostgreSQLAuth(String hostname, int port, String database) {
        validateHost(hostname);
        validatePort(port);
        validateDatabase(database);
        this.database = database;
        this.hostname = hostname;
        this.port = port;
    }

    @Override
    public String getDialect() {
        return "org.hibernate.dialect.PostgreSQLDialect";
    }

    @Override
    public String getDriver() {
        return "org.postgresql.Driver";
    }

    @Override
    public String getUrl() {
        return "jdbc:postgresql://" + hostname + ":" + port + "/" + database;
    }
}
