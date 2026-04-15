package sh.fyz.architect.persistent.sql.provider;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;

public class H2Auth extends SQLAuthProvider {

    private final String hostname;
    private final String database;
    private final int port;

    public H2Auth(String hostname, int port, String database) {
        validateHost(hostname);
        validatePort(port);
        validateDatabase(database);
        this.hostname = hostname;
        this.port = port;
        this.database = database;
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
        return "jdbc:h2:tcp://" + hostname + ":" + port + "/" + database;
    }
}
