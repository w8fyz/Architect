package sh.fyz.architect.persistent.sql;

/**
 * Connection-level TLS/SSL enforcement level for SQL providers.
 *
 * <p>Each provider translates the mode to its dialect-specific JDBC URL parameters.
 * Not every mode is supported by every dialect — unsupported modes fall back to
 * the closest equivalent.</p>
 */
public enum TlsMode {
    /**
     * The default. No TLS parameter is added, so the driver's own default applies: opportunistic
     * TLS for PostgreSQL ({@code prefer}) and MySQL ({@code PREFERRED}), plaintext for MariaDB
     * and H2.
     */
    DRIVER_DEFAULT,
    /** No TLS: plaintext is forced, even where the driver would try TLS by default. */
    DISABLE,
    /** Try TLS, fall back to plain text. */
    PREFER,
    /** Require TLS but do not verify the server certificate. */
    REQUIRE,
    /** Require TLS and verify that the server certificate is signed by a trusted CA. */
    VERIFY_CA,
    /** Require TLS, verify the CA, and verify the hostname. */
    VERIFY_FULL
}
