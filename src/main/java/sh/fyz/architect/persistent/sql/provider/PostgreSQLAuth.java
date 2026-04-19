package sh.fyz.architect.persistent.sql.provider;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;
import sh.fyz.architect.persistent.sql.TlsMode;

public class PostgreSQLAuth extends SQLAuthProvider {

    private final String hostname;
    private final String database;
    private final int port;
    private TlsMode tlsMode = TlsMode.DISABLE;

    public PostgreSQLAuth(String hostname, int port, String database) {
        validateHost(hostname);
        validatePort(port);
        validateDatabase(database);
        this.database = database;
        this.hostname = hostname;
        this.port = port;
    }

    public PostgreSQLAuth withTls(TlsMode mode) {
        if (mode == null) throw new IllegalArgumentException("TlsMode must not be null");
        this.tlsMode = mode;
        return this;
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
        String base = "jdbc:postgresql://" + hostname + ":" + port + "/" + database;
        return base + tlsParams();
    }

    private String tlsParams() {
        return switch (tlsMode) {
            case DISABLE -> "";
            case PREFER -> "?sslmode=prefer";
            case REQUIRE -> "?sslmode=require";
            case VERIFY_CA -> "?sslmode=verify-ca";
            case VERIFY_FULL -> "?sslmode=verify-full";
        };
    }
}
