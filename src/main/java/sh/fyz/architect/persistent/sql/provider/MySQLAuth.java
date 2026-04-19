package sh.fyz.architect.persistent.sql.provider;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;
import sh.fyz.architect.persistent.sql.TlsMode;

public class MySQLAuth extends SQLAuthProvider {

    private final String hostname;
    private final String database;
    private final int port;
    private TlsMode tlsMode = TlsMode.DISABLE;

    public MySQLAuth(String hostname, int port, String database) {
        validateHost(hostname);
        validatePort(port);
        validateDatabase(database);
        this.hostname = hostname;
        this.port = port;
        this.database = database;
    }

    public MySQLAuth withTls(TlsMode mode) {
        if (mode == null) throw new IllegalArgumentException("TlsMode must not be null");
        this.tlsMode = mode;
        return this;
    }

    @Override
    public String getDialect() {
        return "org.hibernate.dialect.MySQLDialect";
    }

    @Override
    public String getDriver() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public String getUrl() {
        String base = "jdbc:mysql://" + hostname + ":" + port + "/" + database;
        return base + tlsParams();
    }

    private String tlsParams() {
        return switch (tlsMode) {
            case DISABLE -> "?useSSL=false";
            case PREFER -> "?useSSL=true";
            case REQUIRE -> "?useSSL=true&requireSSL=true";
            case VERIFY_CA -> "?useSSL=true&requireSSL=true&verifyServerCertificate=true";
            case VERIFY_FULL -> "?useSSL=true&requireSSL=true&verifyServerCertificate=true&sslMode=VERIFY_IDENTITY";
        };
    }
}
