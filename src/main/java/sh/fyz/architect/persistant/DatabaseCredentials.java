package sh.fyz.architect.persistant;

public class DatabaseCredentials {

    String hostname, database, user, password;
    int port, poolSize;

    public DatabaseCredentials(String hostname, int port, String database, String user, String password, int poolSize) {
        this.hostname = hostname;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
        this.poolSize = poolSize;
    }

    public String getHostname() {
        return hostname;
    }

    public String getDatabase() {
        return database;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }


    public int getPort() {
        return port;
    }

    public int getPoolSize() {
        return poolSize;
    }
}
