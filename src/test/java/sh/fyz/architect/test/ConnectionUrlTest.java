package sh.fyz.architect.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sh.fyz.architect.migration.ShadowDatabase;
import sh.fyz.architect.persistent.sql.SQLAuthProvider;
import sh.fyz.architect.persistent.sql.TlsMode;
import sh.fyz.architect.persistent.sql.provider.H2Auth;
import sh.fyz.architect.persistent.sql.provider.MariaDBAuth;
import sh.fyz.architect.persistent.sql.provider.MySQLAuth;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;
import sh.fyz.architect.persistent.sql.provider.SQLiteAuth;

import static org.junit.jupiter.api.Assertions.*;

/** JDBC URLs built by the providers, and the shadow guard comparing them. No database needed. */
@DisplayName("URLs JDBC et garde-fou shadow")
public class ConnectionUrlTest {

    private static SQLAuthProvider url(String url) {
        return new SQLAuthProvider() {
            public String getDialect() { return "org.hibernate.dialect.H2Dialect"; }
            public String getDriver() { return "org.h2.Driver"; }
            public String getUrl() { return url; }
        };
    }

    @Test
    @DisplayName("shadow H2 sans port explicite : reconnu comme la base de l'application")
    void testShadowGuardH2DefaultPort() {
        String mainUrl = new H2Auth("db.local", 9092, "app").getUrl();
        ShadowDatabase shadow = new ShadowDatabase(url("jdbc:h2:tcp://db.local/app"), "u", "p");
        assertThrows(IllegalArgumentException.class, () -> shadow.assertNotMainDatabase(mainUrl));

        ShadowDatabase other = new ShadowDatabase(url("jdbc:h2:tcp://db.local/app_shadow"), "u", "p");
        assertDoesNotThrow(() -> other.assertNotMainDatabase(mainUrl));
    }

    @Test
    @DisplayName("MySQL : DRIVER_DEFAULT laisse le defaut du driver, DISABLE force le texte clair")
    void testMySqlTls() {
        assertEquals("jdbc:mysql://h:3306/app", new MySQLAuth("h", 3306, "app").getUrl());
        assertEquals("jdbc:mysql://h:3306/app?useSSL=false",
                new MySQLAuth("h", 3306, "app").withTls(TlsMode.DISABLE).getUrl());
        assertTrue(new MySQLAuth("h", 3306, "app").withTls(TlsMode.REQUIRE).getUrl().contains("requireSSL=true"));
    }

    @Test
    @DisplayName("PostgreSQL et MariaDB : DRIVER_DEFAULT sans parametre, DISABLE force le texte clair")
    void testPostgresAndMariaDbTls() {
        assertEquals("jdbc:postgresql://h:5432/app", new PostgreSQLAuth("h", 5432, "app").getUrl());
        assertEquals("jdbc:postgresql://h:5432/app?sslmode=disable",
                new PostgreSQLAuth("h", 5432, "app").withTls(TlsMode.DISABLE).getUrl());
        assertEquals("jdbc:mariadb://h:3306/app", new MariaDBAuth("h", 3306, "app").getUrl());
        assertEquals("jdbc:mariadb://h:3306/app?useSsl=false",
                new MariaDBAuth("h", 3306, "app").withTls(TlsMode.DISABLE).getUrl());
    }

    @Test
    @DisplayName("SQLite : chemins Windows acceptes, schemas d'URL et parametres refuses")
    void testSqlitePaths() {
        assertDoesNotThrow(() -> new SQLiteAuth("C:\\data\\app.db"));
        assertDoesNotThrow(() -> new SQLiteAuth("data/app.db"));
        assertDoesNotThrow(() -> new SQLiteAuth(":memory:"));
        assertThrows(IllegalArgumentException.class, () -> new SQLiteAuth("file:app.db"));
        assertThrows(IllegalArgumentException.class, () -> new SQLiteAuth("app.db?open_mode=1"));
        assertThrows(IllegalArgumentException.class, () -> new SQLiteAuth("app.db;x"));
    }
}
