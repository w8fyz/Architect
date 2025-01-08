package sh.fyz.architect.persistant.sql;

public abstract class SQLAuthProvider {

    public abstract String getDialect();

    public abstract String getDriver();

    public abstract String getUrl();
}
