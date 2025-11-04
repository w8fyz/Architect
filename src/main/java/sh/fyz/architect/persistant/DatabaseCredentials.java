package sh.fyz.architect.persistant;

import sh.fyz.architect.persistant.sql.SQLAuthProvider;

public class DatabaseCredentials {

    String user, password;
    int poolSize;
    int threadPoolSize;
    SQLAuthProvider sqlAuthProvider;

    public DatabaseCredentials(SQLAuthProvider sqlAuthProvider, String user, String password, int poolSize, int threadPoolSize) {
        this.user = user;
        this.password = password;
        this.poolSize = poolSize;
        this.threadPoolSize = threadPoolSize;
        this.sqlAuthProvider = sqlAuthProvider;
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
}
