package sh.fyz.architect.persistant.sql.provider;

import sh.fyz.architect.persistant.sql.SQLAuthProvider;

public class MariaDBAuth extends SQLAuthProvider {

    private String hostname, database;
    private int port;

    public MariaDBAuth(String hostname, int port, String database) {
        this.hostname = hostname;
        this.port = port;
        this.database = database;
    }

    @Override
    public String getDialect() {
        return "org.hibernate.dialect.MariaDBDialect";
    }

    @Override
    public String getDriver() {
        return "org.mariadb.jdbc.Driver";
    }

    @Override
    public String getUrl() {
        return "jdbc:mariadb://" + hostname + ":" + port + "/" + database;
    }
}
