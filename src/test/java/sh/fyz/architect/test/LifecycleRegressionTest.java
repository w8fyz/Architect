package sh.fyz.architect.test;

import com.zaxxer.hikari.HikariDataSource;
import org.awaitility.Awaitility;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.*;
import redis.clients.jedis.BuilderFactory;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.RedisClient;
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
    @DisplayName("Cache - stop() attend les appels async avant d'arreter Redis")
    void testStopWaitsForAsyncCalls() throws Exception {
        architect = start(true, 4);
        GenericCachedRepository<Product> repository = new GenericCachedRepository<>(Product.class);
        Product product = repository.save(new Product(MARKER, "Cat", 10.0, 1, true));

        // Writes only once stop() has begun (it clears isStarted() first): that write needs
        // Redis, then the final flush.
        Architect stopping = architect;
        java.util.concurrent.CompletableFuture<Void> call = java.util.concurrent.CompletableFuture.runAsync(() -> {
            Awaitility.await().atMost(Duration.ofSeconds(4)).until(() -> !stopping.isStarted());
            product.setStock(42);
            repository.save(product);
        }, SessionManager.get().getThreadPool());

        architect.stop();
        architect = null;

        assertDoesNotThrow(() -> call.get());
        assertEquals(42, readStock(product.getId()));
    }

    @Test
    @Order(4)
    @DisplayName("Relay sans base - stop() attend les appels async avant d'arreter Redis")
    void testStopWaitsForAsyncCallsWithoutDatabase() throws Exception {
        architect = new Architect()
            .setReceiver(false)
            .setRedisCredentials(new RedisCredentials(REDIS_HOST, REDIS_PASS, REDIS_PORT, 2000, 10));
        architect.start();
        var relay = new GenericRelayRepository<Product>(Product.class) {
            java.util.concurrent.ExecutorService asyncPool() {
                return threadPool();
            }
        };
        Product product = new Product(MARKER, "Cat", 10.0, 1, true);
        java.lang.reflect.Field id = Product.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(product, 987654321L); // a non-receiver only updates existing entities

        // On the pool the repository's async calls use, writing only once stop() has begun.
        Architect stopping = architect;
        java.util.concurrent.CompletableFuture<Void> call = java.util.concurrent.CompletableFuture.runAsync(() -> {
            Awaitility.await().atMost(Duration.ofSeconds(4)).until(() -> !stopping.isStarted());
            relay.save(product);
        }, relay.asyncPool());
        architect.stop();
        architect = null;

        assertDoesNotThrow(() -> call.get());
        try (RedisClient client = RedisClient.builder()
                .hostAndPort(REDIS_HOST, REDIS_PORT)
                .clientConfig(DefaultJedisClientConfig.builder().password(REDIS_PASS).build())
                .build()) {
            assertEquals(1, client.del("architect:Product:987654321"), "the cached copy must be written");
        }
    }

    @Test
    @Order(5)
    @DisplayName("Cache - un repository conserve apres stop()/start() ecrit toujours en base")
    void testCachedRepositorySurvivesRestart() {
        architect = start(true, 4);
        GenericCachedRepository<Product> repository = new GenericCachedRepository<>(Product.class);
        Product product = repository.save(new Product(MARKER, "Cat", 10.0, 1, true));
        architect.stop();

        architect = start(true, 4);
        product.setStock(77);
        repository.save(product); // queued: only a flush worker of the new manager applies it
        architect.stop();
        architect = null;

        assertEquals(77, readStock(product.getId()));
    }

    @Test
    @Order(6)
    @DisplayName("Cache - base en lecture seule : l'ecriture attend, puis est appliquee et servie apres restart")
    void testWriteLeftQueuedAtStopIsCachedAfterRestart() {
        architect = start(true, 4);
        GenericCachedRepository<Product> repository = new GenericCachedRepository<>(Product.class);
        Product product = repository.save(new Product(MARKER, "Cat", 10.0, 1, true));
        setReadOnly(true);
        try {
            product.setStock(55);
            repository.save(product);
            // Well past the ~5 s after which a write failing for another reason is dropped: a
            // read-only database (a failover) is unavailable, not a refusal of this write.
            Awaitility.await().pollDelay(Duration.ofSeconds(7)).atMost(Duration.ofSeconds(8)).until(() -> true);
            assertTrue(repository.pendingWriteCount() > 0, "the write must still wait for the database");
            architect.stop(); // the final flush is refused too: the write stays queued
            architect = null;
            assertTrue(repository.pendingWriteCount() > 0, "the database must have refused the write");
        } finally {
            setReadOnly(false);
        }

        architect = start(true, 4);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertEquals(55, readStock(product.getId())));
        assertEquals(55, repository.findById(product.getId()).getStock(),
            "the cache loaded at start must not serve the row's older state");
    }

    @Test
    @Order(7)
    @DisplayName("Cache - apres restart, le cache charge toute la table sans ecraser une ligne deja en cache")
    void testCacheLoadsWholeTableAfterRestart() {
        architect = start(true, 4);
        GenericRepository<Product> db = new GenericRepository<>(Product.class);
        Product first = db.save(new Product(MARKER, "Whole", 1.0, 1, true));
        db.save(new Product(MARKER, "Whole", 2.0, 2, true));
        db.save(new Product(MARKER, "Whole", 3.0, 3, true));
        architect.stop();

        architect = start(true, 4);
        // Another instance sharing Redis caches one row, newer than the database's (a relayed
        // save still on its way), before this instance reads the type.
        first.setStock(99);
        sh.fyz.architect.cache.RedisManager.get().save("Product:" + first.getId(), first);
        GenericCachedRepository<Product> repository = new GenericCachedRepository<>(Product.class);

        assertEquals(3, repository.query().where("category", "Whole").count(),
            "one cached row is not the whole table");
        assertEquals(99, repository.findById(first.getId()).getStock(),
            "loading the table must not overwrite a newer cached state");
    }

    @Test
    @Order(8)
    @DisplayName("Relay - stop() rapide et reabonnement apres restart")
    void testRelayResubscribesAfterRestart() {
        architect = start(true, 4);
        GenericRelayRepository<Product> relay = new GenericRelayRepository<>(Product.class);
        awaitSubscribers(1);

        long startedAt = System.nanoTime();
        architect.stop();
        architect = null;
        long stopMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        assertTrue(stopMillis < 4000, "stop() must not wait for a blocked subscriber, took " + stopMillis + " ms");
        awaitSubscribers(0);

        // The repository kept across the restart subscribes again on its own.
        architect = start(true, 4);
        awaitSubscribers(1);
        assertNotNull(relay);

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
                DB_USER, DB_PASS, poolSize, "update"
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
        // A client of its own: the count is also read after Architect has stopped.
        try (RedisClient client = RedisClient.builder()
                .hostAndPort(REDIS_HOST, REDIS_PORT)
                .clientConfig(DefaultJedisClientConfig.builder().password(REDIS_PASS).build())
                .build()) {
            return client.executeCommand(new CommandObject<>(
                    new CommandArguments(Protocol.Command.PUBSUB).add("NUMSUB").add(CHANNEL),
                    BuilderFactory.PUBSUB_NUMSUB_MAP))
                    .getOrDefault(CHANNEL, 0L);
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

    /** Makes PostgreSQL refuse writes, like a primary demoted during a failover. */
    private void setReadOnly(boolean readOnly) {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), DB_USER, DB_PASS);
             var stmt = c.createStatement()) {
            stmt.execute(readOnly
                ? "ALTER SYSTEM SET default_transaction_read_only = on"
                : "ALTER SYSTEM RESET default_transaction_read_only");
            stmt.execute("SELECT pg_reload_conf()");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        // The reload is signalled asynchronously: wait until new sessions see it.
        String expected = readOnly ? "on" : "off";
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
            try (Connection c = DriverManager.getConnection(jdbcUrl(), DB_USER, DB_PASS);
                 ResultSet rs = c.createStatement().executeQuery("SHOW default_transaction_read_only")) {
                return rs.next() && expected.equals(rs.getString(1));
            }
        });
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
