package sh.fyz.architect.persistent;

import sh.fyz.architect.persistent.sql.SQLAuthProvider;

import java.util.Set;

public class DatabaseCredentials {

    private static final Set<String> ALLOWED_HBM2DDL = Set.of("none", "validate", "update", "create", "create-drop", "create-only");

    private final String user;
    private final String password;
    private final int poolSize;
    private final int threadPoolSize;
    private final SQLAuthProvider sqlAuthProvider;
    private final String hbm2ddlAuto;

    public DatabaseCredentials(SQLAuthProvider sqlAuthProvider, String user, String password, int poolSize, int threadPoolSize) {
        this(sqlAuthProvider, user, password, poolSize, threadPoolSize, "update");
    }

    public DatabaseCredentials(SQLAuthProvider sqlAuthProvider, String user, String password, int poolSize) {
        this(sqlAuthProvider, user, password, poolSize, 10, "update");
    }

    public DatabaseCredentials(SQLAuthProvider sqlAuthProvider, String user, String password, int poolSize, int threadPoolSize, String hbm2ddlAuto) {
        if (hbm2ddlAuto != null && !ALLOWED_HBM2DDL.contains(hbm2ddlAuto)) {
            throw new IllegalArgumentException(
                "Invalid hbm2ddlAuto value: '" + hbm2ddlAuto + "'. Allowed values: " + ALLOWED_HBM2DDL
            );
        }
        this.user = user;
        this.password = password;
        this.poolSize = poolSize;
        this.threadPoolSize = threadPoolSize;
        this.sqlAuthProvider = sqlAuthProvider;
        this.hbm2ddlAuto = hbm2ddlAuto;
    }

    public SQLAuthProvider getSQLAuthProvider() {
        return sqlAuthProvider;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public String getHbm2ddlAuto() {
        return hbm2ddlAuto;
    }

    @Override
    public String toString() {
        return "DatabaseCredentials{user='" + user + "', poolSize=" + poolSize +
               ", threadPoolSize=" + threadPoolSize + ", hbm2ddlAuto='" + hbm2ddlAuto + "'}";
    }
}
