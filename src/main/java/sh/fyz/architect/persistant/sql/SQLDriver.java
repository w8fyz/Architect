package sh.fyz.architect.persistant.sql;

public enum SQLDriver {

    POSTGRESQL(
            "org.postgresql.Driver",
            "org.hibernate.dialect.PostgreSQLDialect",
            "jdbc:postgresql://%hostname%:%port%/%database%"
    ),
    MYSQL(
            "com.mysql.cj.jdbc.Driver",
            "org.hibernate.dialect.MySQLDialect",
            "jdbc:mysql://%hostname%:%port%/%database%"
    ),
    MARIADB(
            "org.mariadb.jdbc.Driver",
            "org.hibernate.dialect.MariaDBDialect",
            "jdbc:mariadb://%hostname%:%port%/%database%"
    ),
    SQLITE(
            "org.sqlite.JDBC",
            "org.hibernate.dialect.SQLiteDialect",
            "jdbc:sqlite:%database%"
    ),
    H2(
            "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect",
            "jdbc:h2:tcp://%hostname%:%port%/%database%"
    );

    private final String driver;
    private final String dialect;
    private final String url;

    SQLDriver(String driver, String dialect, String url) {
        this.driver = driver;
        this.dialect = dialect;
        this.url = url;
    }

    public String getDriver() {
        return driver;
    }

    public String getDialect() {
        return dialect;
    }

    /**
     * Constructs the JDBC URL with the given parameters by replacing placeholders
     * (%hostname%, %port%, %database%) only if they exist in the URL template.
     *
     * @param hostname the hostname of the database server
     * @param port     the port of the database server
     * @param database the name or path of the database
     * @return the constructed JDBC URL
     */
    public String getUrl(String hostname, int port, String database) {
        String constructedUrl = url;
        if (constructedUrl.contains("%hostname%")) {
            constructedUrl = constructedUrl.replace("%hostname%", hostname);
        }
        if (constructedUrl.contains("%port%")) {
            constructedUrl = constructedUrl.replace("%port%", String.valueOf(port));
        }
        if (constructedUrl.contains("%database%")) {
            constructedUrl = constructedUrl.replace("%database%", database);
        }
        return constructedUrl;
    }
}

