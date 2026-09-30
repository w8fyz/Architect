package sh.fyz.architect.test;

import com.zaxxer.hikari.HikariDataSource;
import org.awaitility.Awaitility;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.*;
import sh.fyz.architect.Architect;
import sh.fyz.architect.cache.EntityChannelPubSub;
import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.SessionManager;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;
import sh.fyz.architect.repositories.GenericCachedRepository;
import sh.fyz.architect.repositories.GenericRelayRepository;
import sh.fyz.architect.repositories.GenericRepository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Lifecycle - Pool, arret et pub/sub")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LifecycleRegressionTest {

    private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final int DB_PORT = Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5440"));
    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "architect_test");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "architect");
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "architect");

    private static final String REDIS_HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380"));
    private static final String REDIS_PASS = System.getenv().getOrDefault("REDIS_PASS", "architect");

    private static final String CHANNEL = "database-action:Product";
    private static final String MARKER = "lifecycle-regression";

    private Architect architect;

    @AfterEach
    void teardown() {
        if (architect != null) {
            architect.stop();
            architect = null;
        }
        deleteMarkedRows();
    }

    @Test
    @Order(1)
    @DisplayName("Pool - HikariCP est reellement utilise avec la taille configuree")
    void testHikariPoolIsUsed() {
        architect = start(false, 3);

        try (var session = SessionManager.get().getSession()) {
            ConnectionProvider provider = session.getSessionFactory()
                .unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry()
                .getService(ConnectionProvider.class);

            assertTrue(provider.isUnwrappableAs(HikariDataSource.class),
                "Hibernate should use HikariCP, not its built-in pool; got " + provider.getClass().getName());
            HikariDataSource dataSource = provider.unwrap(HikariDataSource.class);
            assertEquals(3, dataSource.getMaximumPoolSize());
            assertEquals("architect", dataSource.getPoolName());
        }
    }

    @Test
    @Order(2)
    @DisplayName("Cache - stop() persiste les ecritures encore en file")
    void testStopFlushesPendingWrites() {
        architect = start(true, 4);
        GenericCachedRepository<Product> repository = new GenericCachedRepository<>(Product.class);

        Product product = repository.save(new Product(MARKER, "Cat", 10.0, 1, true));
        assertNotNull(product.getId());

        // An update of an existing entity is only queued; the worker flushes it every 200 ms.
        product.setStock(42);
        repository.save(product);

        architect.stop();
        architect = null;

        assertEquals(42, readStock(product.getId()),
            "The queued update must reach the database before stop() returns");
    }

    @Test
    @Order(3)
    @DisplayName("Relay - stop() rapide et reabonnement apres restart")
    void testRelayResubscribesAfterRestart() {
        architect = start(true, 4);
        new GenericRelayRepository<>(Product.class);
        awaitSubscribers(1);

        long startedAt = System.nanoTime();
        architect.stop();
        architect = null;
        long stopMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        assertTrue(stopMillis < 4000, "stop() must not wait for a blocked subscriber, took " + stopMillis + " ms");

        architect = start(true, 4);
        awaitSubscribers(0);
        new GenericRelayRepository<>(Product.class);
        awaitSubscribers(1);

        Product relayed = new Product(MARKER, "Relayed", 5.0, 7, true);
        new EntityChannelPubSub<>(Product.class).publish(new DatabaseAction<>(relayed, DatabaseAction.Type.SAVE));

        GenericRepository<Product> db = new GenericRepository<>(Product.class);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertEquals(1, db.query().where("name", MARKER).where("category", "Relayed").count())
        );
    }

    // ========================
    // Helpers
    // ========================

    private Architect start(boolean withRedis, int poolSize) {
        Architect arch = new Architect()
            .setReceiver(true)
            .setDatabaseCredentials(new DatabaseCredentials(
                new PostgreSQLAuth(DB_HOST, DB_PORT, DB_NAME),
                DB_USER, DB_PASS, poolSize, 2, "update"
            ));
        if (withRedis) {
            arch.setRedisCredentials(new RedisCredentials(REDIS_HOST, REDIS_PASS, REDIS_PORT, 2000, 10));
        }
        arch.addEntityClass(Product.class);
        arch.start();
        return arch;
    }

    private void awaitSubscribers(long expected) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertEquals(expected, subscriberCount())
        );
    }

    private long subscriberCount() {
        try (var jedis = new redis.clients.jedis.Jedis(REDIS_HOST, REDIS_PORT)) {
            jedis.auth(REDIS_PASS);
            return jedis.pubsubNumSub(CHANNEL).getOrDefault(CHANNEL, 0L);
        }
    }

    private int readStock(Long id) {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), DB_USER, DB_PASS);
             PreparedStatement ps = c.prepareStatement("SELECT stock FROM test_products WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "row " + id + " should exist");
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void deleteMarkedRows() {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), DB_USER, DB_PASS);
             PreparedStatement ps = c.prepareStatement("DELETE FROM test_products WHERE name = ?")) {
            ps.setString(1, MARKER);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // table absent: nothing to clean
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME;
    }
}
