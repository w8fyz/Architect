package sh.fyz.architect.cache;

public class RedisCredentials {

    private final String host;
    private final String password;
    private final int port;
    private final int timeout;
    private final int maxConnections;
    private final int defaultTtlSeconds;

    public RedisCredentials(String host, String password, int port, int timeout, int maxConnections) {
        this(host, password, port, timeout, maxConnections, 0);
    }

    public RedisCredentials(String host, String password, int port, int timeout, int maxConnections,
                             int defaultTtlSeconds) {
        if (defaultTtlSeconds < 0) {
            throw new IllegalArgumentException("defaultTtlSeconds must be >= 0 (0 = no expiry)");
        }
        this.host = host;
        this.password = password;
        this.port = port;
        this.timeout = timeout;
        this.maxConnections = maxConnections;
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public String getPassword() {
        return password;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getTimeout() {
        return timeout;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * Default TTL applied to every key stored through {@link RedisManager#save}. {@code 0}
     * (default) means no expiry and the cache grows until explicit eviction. Set a positive
     * value in production to cap Redis memory usage.
     */
    public int getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    @Override
    public String toString() {
        return "RedisCredentials{host='" + host + "', port=" + port +
               ", timeout=" + timeout + ", maxConnections=" + maxConnections +
               ", defaultTtlSeconds=" + defaultTtlSeconds + "}";
    }
}
