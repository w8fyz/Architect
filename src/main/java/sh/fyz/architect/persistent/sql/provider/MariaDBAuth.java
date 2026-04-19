package sh.fyz.architect.persistent.sql.provider;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;
import sh.fyz.architect.persistent.sql.TlsMode;

public class MariaDBAuth extends SQLAuthProvider {

    private final String hostname;
    private final String database;
    private final int port;
    private TlsMode tlsMode = TlsMode.DISABLE;

    public MariaDBAuth(String hostname, int port, String database) {
        validateHost(hostname);
        validatePort(port);
        validateDatabase(database);
        this.hostname = hostname;
        this.port = port;
        this.database = database;
    }

    public MariaDBAuth withTls(TlsMode mode) {
        if (mode == null) throw new IllegalArgumentException("TlsMode must not be null");
        this.tlsMode = mode;
        return this;
    }

    @Override
    public String getDialect() {
        return "org.hibernate.dialect.MariaDBDialect";
    }

    @Override
    public String getDriver() {
        return "org.mariadb.jdbc.Driver";
    }

    @Override
    public String getUrl() {
        String base = "jdbc:mariadb://" + hostname + ":" + port + "/" + database;
        return base + tlsParams();
    }

    private String tlsParams() {
        return switch (tlsMode) {
            case DISABLE -> "";
            case PREFER -> "?sslMode=trust";
            case REQUIRE -> "?sslMode=trust&useSsl=true";
            case VERIFY_CA -> "?sslMode=verify-ca&useSsl=true";
            case VERIFY_FULL -> "?sslMode=verify-full&useSsl=true";
        };
    }
}
