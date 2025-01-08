package sh.fyz.architect.persistant.sql.provider;

import sh.fyz.architect.persistant.sql.SQLAuthProvider;

public class SQLiteAuth extends SQLAuthProvider {

    private String databasePath;

    public SQLiteAuth(String databasePath) {
        this.databasePath = databasePath;
    }

    @Override
    public String getDialect() {
        return "org.hibernate.dialect.SQLiteDialect";
    }

    @Override
    public String getDriver() {
        return "org.sqlite.JDBC";
    }

    @Override
    public String getUrl() {
        return "jdbc:sqlite:" + databasePath;
    }
}
