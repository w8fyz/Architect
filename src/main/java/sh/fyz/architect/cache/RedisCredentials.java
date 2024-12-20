package sh.fyz.architect.cache;

public class RedisCredentials {

    String host,password;
    int port, timeout, maxConnections;

    public RedisCredentials(String host, String password, int port, int timeout, int maxConnections) {
        this.host = host;
        this.password = password;
        this.port = port;
        this.timeout = timeout;
        this.maxConnections = maxConnections;
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
}
