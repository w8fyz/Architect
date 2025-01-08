package sh.fyz.architect.persistant.sql.provider;

import sh.fyz.architect.persistant.sql.SQLAuthProvider;

public class PostgreSQLAuth extends SQLAuthProvider {

    private String hostname,database;
    private int port;

    public PostgreSQLAuth(String hostname, int port, String database) {
        this.database = database;
        this.hostname = hostname;
        this.port = port;
    }

    @Override
    public String getDialect() {
        return "org.hibernate.dialect.PostgreSQLDialect";
    }

    @Override
    public String getDriver() {
        return "org.postgresql.Driver";
    }

    @Override
    public String getUrl() {
        return "jdbc:postgresql://"+hostname+":"+port+"/"+database;
    }
}
