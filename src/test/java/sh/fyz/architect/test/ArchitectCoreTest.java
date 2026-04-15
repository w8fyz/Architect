package sh.fyz.architect.test;

import org.junit.jupiter.api.*;
import sh.fyz.architect.Architect;
import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.SessionManager;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;
import sh.fyz.architect.repositories.GenericRepository;
import sh.fyz.architect.repositories.RepositoryRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Architect Core - Lifecycle, Credentials, Registry")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ArchitectCoreTest {

    private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final int DB_PORT = Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5440"));
    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "architect_test");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "architect");
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "architect");

    // ========================
    // DatabaseCredentials
    // ========================

    @Test
    @Order(1)
    @DisplayName("DatabaseCredentials - Valeurs par defaut")
    void testCredentialsDefaults() {
        DatabaseCredentials creds = new DatabaseCredentials(
            new PostgreSQLAuth("localhost", 5432, "db"),
            "user", "pass", 5
        );
        assertEquals(10, creds.getThreadPoolSize());
        assertEquals("update", creds.getHbm2ddlAuto());
    }

    @Test
    @Order(2)
    @DisplayName("DatabaseCredentials - hbm2ddlAuto valide accepte")
    void testCredentialsValidHbm2ddl() {
        for (String val : List.of("none", "validate", "update", "create", "create-drop", "create-only")) {
            assertDoesNotThrow(() -> new DatabaseCredentials(
                new PostgreSQLAuth("localhost", 5432, "db"),
                "user", "pass", 5, 10, val
            ));
        }
    }

    @Test
    @Order(3)
    @DisplayName("DatabaseCredentials - hbm2ddlAuto invalide rejete (fix F7)")
    void testCredentialsInvalidHbm2ddl() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseCredentials(
            new PostgreSQLAuth("localhost", 5432, "db"),
            "user", "pass", 5, 10, "drop-all"
        ));

        assertThrows(IllegalArgumentException.class, () -> new DatabaseCredentials(
            new PostgreSQLAuth("localhost", 5432, "db"),
            "user", "pass", 5, 10, "DELETE FROM users"
        ));
    }

    @Test
    @Order(4)
    @DisplayName("DatabaseCredentials - toString() ne contient pas le mot de passe (fix F5)")
    void testCredentialsToStringNoPassword() {
        DatabaseCredentials creds = new DatabaseCredentials(
            new PostgreSQLAuth("localhost", 5432, "db"),
            "admin", "s3cr3t_p@ss!", 5
        );
        String str = creds.toString();
        assertFalse(str.contains("s3cr3t_p@ss!"), "Le mot de passe ne doit pas apparaitre dans toString()");
        assertTrue(str.contains("admin"), "Le user doit etre present dans toString()");
    }

    @Test
    @Order(5)
    @DisplayName("RedisCredentials - toString() ne contient pas le mot de passe (fix F5)")
    void testRedisCredentialsToStringNoPassword() {
        RedisCredentials creds = new RedisCredentials("redis.host", "my_redis_secret", 6379, 2000, 10);
        String str = creds.toString();
        assertFalse(str.contains("my_redis_secret"), "Le mot de passe Redis ne doit pas apparaitre dans toString()");
        assertTrue(str.contains("redis.host"), "Le host doit etre present dans toString()");
    }

    // ========================
    // SQL Auth Providers
    // ========================

    @Test
    @Order(10)
    @DisplayName("SQLAuthProvider - Hostname invalide rejete")
    void testInvalidHostname() {
        assertThrows(IllegalArgumentException.class, () ->
            new PostgreSQLAuth("host; DROP TABLE--", 5432, "db")
        );
    }

    @Test
    @Order(11)
    @DisplayName("SQLAuthProvider - Port invalide rejete")
    void testInvalidPort() {
        assertThrows(IllegalArgumentException.class, () ->
            new PostgreSQLAuth("localhost", 0, "db")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new PostgreSQLAuth("localhost", 70000, "db")
        );
    }

    @Test
    @Order(12)
    @DisplayName("SQLAuthProvider - Database name invalide rejete")
    void testInvalidDatabase() {
        assertThrows(IllegalArgumentException.class, () ->
            new PostgreSQLAuth("localhost", 5432, "db?user=admin&password=secret")
        );
    }

    // ========================
    // RepositoryRegistry
    // ========================

    @Test
    @Order(20)
    @DisplayName("RepositoryRegistry - register et getRepository")
    void testRegistryRegisterAndGet() {
        RepositoryRegistry registry = RepositoryRegistry.get();
        Architect arch = startArchitect();
        try {
            GenericRepository<Product> repo = new GenericRepository<>(Product.class);
            registry.register("products", repo);

            assertNotNull(registry.getRepository("products"));
            assertSame(repo, registry.getRepository("products"));
        } finally {
            arch.stop();
        }
    }

    @Test
    @Order(21)
    @DisplayName("RepositoryRegistry - Case insensitive")
    void testRegistryCaseInsensitive() {
        RepositoryRegistry registry = RepositoryRegistry.get();
        Architect arch = startArchitect();
        try {
            GenericRepository<Product> repo = new GenericRepository<>(Product.class);
            registry.register("Products", repo);

            assertNotNull(registry.getRepository("products"));
            assertNotNull(registry.getRepository("PRODUCTS"));
        } finally {
            arch.stop();
        }
    }

    @Test
    @Order(22)
    @DisplayName("RepositoryRegistry - getRepository retourne null si inconnu")
    void testRegistryUnknown() {
        assertNull(RepositoryRegistry.get().getRepository("nonexistent"));
    }

    @Test
    @Order(23)
    @DisplayName("RepositoryRegistry - clear() vide le registre")
    void testRegistryClear() {
        RepositoryRegistry registry = RepositoryRegistry.get();
        Architect arch = startArchitect();
        try {
            GenericRepository<Product> repo = new GenericRepository<>(Product.class);
            registry.register("products", repo);
            assertNotNull(registry.getRepository("products"));

            registry.clear();
            assertNull(registry.getRepository("products"));
        } finally {
            arch.stop();
        }
    }

    @Test
    @Order(24)
    @DisplayName("RepositoryRegistry - getRegisteredNames()")
    void testRegistryNames() {
        RepositoryRegistry registry = RepositoryRegistry.get();
        Architect arch = startArchitect();
        try {
            GenericRepository<Product> repo = new GenericRepository<>(Product.class);
            registry.register("products", repo);
            registry.register("users", repo);

            var names = registry.getRegisteredNames();
            assertTrue(names.contains("products"));
            assertTrue(names.contains("users"));
        } finally {
            arch.stop();
        }
    }

    // ========================
    // Architect Lifecycle
    // ========================

    @Test
    @Order(30)
    @DisplayName("Architect - start() et stop() sans erreur")
    void testStartStop() {
        Architect arch = new Architect()
            .setReceiver(true)
            .setDatabaseCredentials(new DatabaseCredentials(
                new PostgreSQLAuth(DB_HOST, DB_PORT, DB_NAME),
                DB_USER, DB_PASS, 2, 2, "update"
            ));
        arch.addEntityClass(Product.class);

        assertDoesNotThrow(arch::start);
        assertDoesNotThrow(arch::stop);
    }

    @Test
    @Order(31)
    @DisplayName("Architect - Singletons reinitialisables apres stop() (fix C1)")
    void testSingletonsResetAfterStop() {
        Architect arch1 = new Architect()
            .setReceiver(true)
            .setDatabaseCredentials(new DatabaseCredentials(
                new PostgreSQLAuth(DB_HOST, DB_PORT, DB_NAME),
                DB_USER, DB_PASS, 2, 2, "update"
            ));
        arch1.addEntityClass(Product.class);
        arch1.start();
        arch1.stop();

        Architect arch2 = new Architect()
            .setReceiver(true)
            .setDatabaseCredentials(new DatabaseCredentials(
                new PostgreSQLAuth(DB_HOST, DB_PORT, DB_NAME),
                DB_USER, DB_PASS, 2, 2, "update"
            ));
        arch2.addEntityClass(Product.class);
        assertDoesNotThrow(arch2::start);
        arch2.stop();
    }

    @Test
    @Order(32)
    @DisplayName("Architect - start() sans DB credentials n'init pas SessionManager (fix I3)")
    void testStartWithoutDbCredentials() {
        Architect arch = new Architect().setReceiver(true);
        arch.start();
        assertFalse(SessionManager.isInitialized());
        arch.stop();
    }

    @Test
    @Order(33)
    @DisplayName("Architect - addRepositories() enregistre dans le Registry")
    void testAddRepositories() {
        Architect arch = startArchitect();
        try {
            GenericRepository<Product> repo = new GenericRepository<>(Product.class);
            arch.addRepositories(repo);

            assertNotNull(RepositoryRegistry.get().getRepository("products"));
        } finally {
            arch.stop();
        }
    }

    @Test
    @Order(34)
    @DisplayName("Architect - stop() nettoie le Registry")
    void testStopClearsRegistry() {
        Architect arch = startArchitect();
        GenericRepository<Product> repo = new GenericRepository<>(Product.class);
        arch.addRepositories(repo);
        assertNotNull(RepositoryRegistry.get().getRepository("products"));

        arch.stop();
        assertNull(RepositoryRegistry.get().getRepository("products"));
    }

    @Test
    @Order(35)
    @DisplayName("SessionManager.get() avant init leve une exception")
    void testSessionManagerGetBeforeInit() {
        assertFalse(SessionManager.isInitialized());
        assertThrows(IllegalStateException.class, SessionManager::get);
    }

    // ========================
    // Helpers
    // ========================

    private Architect startArchitect() {
        Architect arch = new Architect()
            .setReceiver(true)
            .setDatabaseCredentials(new DatabaseCredentials(
                new PostgreSQLAuth(DB_HOST, DB_PORT, DB_NAME),
                DB_USER, DB_PASS, 2, 2, "update"
            ));
        arch.addEntityClass(Product.class);
        arch.start();
        return arch;
    }
}
