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

    /**
     * The legacy options, which Connector/J 2.x and 3.x read the same way (3.x maps them to
     * {@code sslMode} trust / verify-ca / verify-full, logging a deprecation notice). Mixing
     * them with {@code sslMode} does not work: 3.x lets {@code useSsl} override an explicit
     * {@code sslMode} with verify-full, and 2.x ignores {@code sslMode} altogether.
     * MariaDB has no opportunistic mode, so PREFER requires TLS like REQUIRE.
     */
    private String tlsParams() {
        return switch (tlsMode) {
            case DISABLE -> "";
            case PREFER, REQUIRE -> "?useSsl=true&trustServerCertificate=true";
            case VERIFY_CA -> "?useSsl=true&disableSslHostnameVerification=true";
            case VERIFY_FULL -> "?useSsl=true";
        };
    }
}
