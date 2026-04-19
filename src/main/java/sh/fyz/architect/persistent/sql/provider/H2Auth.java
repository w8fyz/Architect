package sh.fyz.architect.persistent.sql.provider;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;
import sh.fyz.architect.persistent.sql.TlsMode;

public class H2Auth extends SQLAuthProvider {

    private final String hostname;
    private final String database;
    private final int port;
    private TlsMode tlsMode = TlsMode.DISABLE;

    public H2Auth(String hostname, int port, String database) {
        validateHost(hostname);
        validatePort(port);
        validateDatabase(database);
        this.hostname = hostname;
        this.port = port;
        this.database = database;
    }

    public H2Auth withTls(TlsMode mode) {
        if (mode == null) throw new IllegalArgumentException("TlsMode must not be null");
        this.tlsMode = mode;
        return this;
    }

    @Override
    public String getDialect() {
        return "org.hibernate.dialect.H2Dialect";
    }

    @Override
    public String getDriver() {
        return "org.h2.Driver";
    }

    @Override
    public String getUrl() {
        String scheme = switch (tlsMode) {
            case DISABLE -> "tcp";
            case PREFER, REQUIRE, VERIFY_CA, VERIFY_FULL -> "ssl";
        };
        return "jdbc:h2:" + scheme + "://" + hostname + ":" + port + "/" + database;
    }
}
