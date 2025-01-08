package sh.fyz.architect.persistant.sql.provider;

import sh.fyz.architect.persistant.sql.SQLAuthProvider;

public class H2Auth extends SQLAuthProvider {

    private String hostname, database;
    private int port;

    public H2Auth(String hostname, int port, String database) {
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
