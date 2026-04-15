package sh.fyz.architect.persistent.sql.provider;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;

public class MySQLAuth extends SQLAuthProvider {

    private final String hostname;
    private final String database;
    private final int port;

    public MySQLAuth(String hostname, int port, String database) {
        validateHost(hostname);
        validatePort(port);
        validateDatabase(database);
        this.hostname = hostname;
        this.port = port;
        this.database = database;
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
        return "jdbc:mysql://" + hostname + ":" + port + "/" + database;
    }
}
