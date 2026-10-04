package sh.fyz.architect.persistent.sql.provider;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;
import sh.fyz.architect.persistent.sql.TlsMode;

public class MySQLAuth extends SQLAuthProvider {

    private final String hostname;
    private final String database;
    private final int port;
    private TlsMode tlsMode = TlsMode.DRIVER_DEFAULT;

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

    /**
     * The legacy options, which Connector/J 8.0+ maps to {@code sslMode} (DISABLED, PREFERRED,
     * REQUIRED, VERIFY_CA; VERIFY_FULL sets {@code sslMode=VERIFY_IDENTITY} itself). Connector/J
     * 5.1 reads them differently: it verifies the certificate whenever {@code useSSL=true}, and
     * ignores {@code sslMode}.
     *
     * <p>DISABLE forces plaintext. With MySQL 8's default {@code caching_sha2_password}, such a
     * login fails with "Public Key Retrieval is not allowed" whenever the server has not cached
     * the account's password yet (after a restart, typically), unless {@code serverRSAPublicKeyFile}
     * or {@code allowPublicKeyRetrieval} is set. Architect sets neither: with the latter, a man in
     * the middle could supply the key.</p>
     */
    private String tlsParams() {
        return switch (tlsMode) {
            case DRIVER_DEFAULT -> "";
            case DISABLE -> "?useSSL=false";
            case PREFER -> "?useSSL=true";
            case REQUIRE -> "?useSSL=true&requireSSL=true";
            case VERIFY_CA -> "?useSSL=true&requireSSL=true&verifyServerCertificate=true";
            case VERIFY_FULL -> "?useSSL=true&requireSSL=true&verifyServerCertificate=true&sslMode=VERIFY_IDENTITY";
        };
    }
}
