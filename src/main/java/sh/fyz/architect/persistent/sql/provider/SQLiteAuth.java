package sh.fyz.architect.persistent.sql.provider;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;

public class SQLiteAuth extends SQLAuthProvider {

    private final String databasePath;

    public SQLiteAuth(String databasePath) {
        validateDatabase(databasePath);
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
