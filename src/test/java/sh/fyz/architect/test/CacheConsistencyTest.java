package sh.fyz.architect.test;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import redis.clients.jedis.Jedis;
import sh.fyz.architect.Architect;
import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.cachemodel.Gadget;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;
import sh.fyz.architect.repositories.GenericCachedRepository;
import sh.fyz.architect.repositories.GenericRepository;
import sh.fyz.architect.repositories.QueryBuilder;
import sh.fyz.architect.repositories.QueryBuilder.Operator;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A warm cache must answer like the database: same rows for the same query, and no write lost
 * on its way to the database.
 */
@DisplayName("GenericCachedRepository - coherence cache / base")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CacheConsistencyTest {

    private Architect architect;
    private GenericCachedRepository<Gadget> cached;
    private GenericRepository<Gadget> db;

    @BeforeAll
    void setup() {
        architect = new Architect()
            .setReceiver(true)
            .setDatabaseCredentials(new DatabaseCredentials(
                new PostgreSQLAuth(
                    System.getenv().getOrDefault("DB_HOST", "localhost"),
                    Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5440")),
                    System.getenv().getOrDefault("DB_NAME", "architect_test")),
                System.getenv().getOrDefault("DB_USER", "architect"),
                System.getenv().getOrDefault("DB_PASS", "architect"),
                4, "create-drop"
            ))
            .setRedisCredentials(new RedisCredentials(
                System.getenv().getOrDefault("REDIS_HOST", "localhost"),
                System.getenv().getOrDefault("REDIS_PASS", "architect"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380")),
                2000, 10
            ));
        architect.addEntityClass(Gadget.class);
        architect.start();

        cached = new GenericCachedRepository<>(Gadget.class);
        db = new GenericRepository<>(Gadget.class);
    }

    @AfterAll
    void teardown() {
        if (architect != null) {
            architect.stop();
        }
    }

    @BeforeEach
    void seed() {
        for (Gadget g : db.all()) {
            db.delete(g);
        }
        // Rows deleted behind the cache's back: drop whatever the previous test left in Redis.
        try (Jedis jedis = RedisManager.get().getJedisPool().getResource()) {
            for (String key : jedis.keys("architect:Gadget:*")) {
                jedis.del(key);
            }
        }
        db.save(new Gadget("a", "books", 1, Gadget.Kind.SMALL));
        db.save(new Gadget("b", null, null, Gadget.Kind.LARGE));
        db.save(new Gadget("c", "toys", 200, Gadget.Kind.SMALL));
        db.save(new Gadget("multi\nline", "toys", 5, null));
        // Read once through the cached repository, so that every row is in Redis.
        for (Gadget g : db.all()) {
            cached.findById(g.getId());
        }
    }

    private static Set<String> names(List<Gadget> gadgets) {
        return gadgets.stream().map(Gadget::getName).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("enum, LocalDateTime, BigDecimal et List<Double> survivent au passage par Redis")
    void testFieldTypesRoundTrip() {
        Gadget a = db.query().where("name", "a").findFirst();
        Gadget fromCache = cached.findById(a.getId());
        assertNotNull(fromCache);
        assertEquals(Gadget.Kind.SMALL, fromCache.getKind());
        assertEquals(a.getCreatedAt(), fromCache.getCreatedAt());
        assertEquals(0, a.getAmount().compareTo(fromCache.getAmount()),
                "a BigDecimal must not be rounded through double: " + fromCache.getAmount());
        assertEquals(List.of(1.5, 2.25), fromCache.getScores(),
                "collection elements must keep their declared type");
        assertEquals(4, cached.all().size());
    }

    @Test
    @DisplayName("filtrage en memoire : memes lignes que la base (NULL, nombres, LIKE, NOT IN)")
    void testInMemoryFilteringMatchesDatabase() {
        long intId = db.query().where("name", "a").findFirst().getId();
        List<UnaryOperator<QueryBuilder<Gadget>>> queries = List.of(
            q -> q.where("category", Operator.NEQ, "books"),
            q -> q.where("qty", Operator.GTE, 2),
            q -> q.where("category", null),
            q -> q.whereNotIn("category", List.of("books")),
            // An immutable list straight into where(): contains(null) would throw on it.
            q -> q.where("category", Operator.NOT_IN, List.of("books")),
            q -> q.where("id", (int) intId),
            q -> q.whereIn("id", List.of((int) intId)),
            q -> q.whereLike("name", "multi%")
        );
        for (UnaryOperator<QueryBuilder<Gadget>> query : queries) {
            Set<String> expected = names(query.apply(db.query()).findAll());
            assertEquals(expected, names(query.apply(cached.query()).findAll()));
            assertEquals(expected.size(), query.apply(cached.query()).count());
        }
    }

    @Test
    @DisplayName("delete avec whereRaw : seules les lignes supprimees quittent le cache")
    void testCachedDeleteWithRawConditions() {
        int deleted = cached.query()
                .where("category", "toys")
                .whereRaw("qty > :m", Map.of("m", 1000))
                .delete();
        assertEquals(0, deleted);
        assertEquals(4, cached.all().size(), "nothing was deleted, nothing may be evicted");

        deleted = cached.query().whereRaw("qty > :m", Map.of("m", 100)).delete();
        assertEquals(1, deleted);
        assertEquals(Set.of("a", "b", "multi\nline"), names(cached.all()));
    }

    @Test
    @DisplayName("@Version : les mises a jour successives atteignent la base")
    void testVersionedUpdatesAreNotDropped() {
        long id = db.query().where("name", "a").findFirst().getId();
        for (int qty = 10; qty <= 12; qty++) {
            Gadget current = cached.findById(id);
            current.setQty(qty);
            cached.save(current);
            int expected = qty;
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(expected, db.findById(id).getQty()));
        }
    }
}
