package sh.fyz.architect.persistant.sql.provider;

import sh.fyz.architect.persistant.sql.SQLAuthProvider;

public class MySQLAuth extends SQLAuthProvider {

    private String hostname, database;
    private int port;

    public MySQLAuth(String hostname, int port, String database) {
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
