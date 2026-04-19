package sh.fyz.architect.test;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import sh.fyz.architect.Architect;
import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;
import sh.fyz.architect.repositories.GenericCachedRepository;
import sh.fyz.architect.repositories.QueryBuilder.Operator;
import sh.fyz.architect.repositories.QueryBuilder.SortOrder;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GenericCachedRepository - Tests complets")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GenericCachedRepositoryTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    private Architect architect;
    private GenericCachedRepository<Product> repository;

    @BeforeAll
    void setup() {
        String dbHost = System.getenv().getOrDefault("DB_HOST", "localhost");
        int dbPort = Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5440"));
        String dbName = System.getenv().getOrDefault("DB_NAME", "architect_test");
        String dbUser = System.getenv().getOrDefault("DB_USER", "architect");
        String dbPass = System.getenv().getOrDefault("DB_PASS", "architect");

        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380"));
        String redisPass = System.getenv().getOrDefault("REDIS_PASS", "architect");

        architect = new Architect()
            .setReceiver(true)
            .setDatabaseCredentials(new DatabaseCredentials(
                new PostgreSQLAuth(dbHost, dbPort, dbName),
                dbUser, dbPass, 4, 4, "create-drop"
            ))
            .setRedisCredentials(new RedisCredentials(
                redisHost, redisPass, redisPort, 2000, 10
            ));
        architect.addEntityClass(Product.class);
        architect.start();

        repository = new GenericCachedRepository<>(Product.class);
    }

    @AfterAll
    void teardown() {
        if (architect != null) {
            architect.stop();
        }
    }

    @BeforeEach
    void cleanData() {
        repository.flushUpdates();

        try (var session = sh.fyz.architect.persistent.SessionManager.get().getSession()) {
            var tx = session.beginTransaction();
            session.createMutationQuery("DELETE FROM " + Product.class.getName()).executeUpdate();
            tx.commit();
        }

        try (var jedis = sh.fyz.architect.cache.RedisManager.get().getJedisPool().getResource()) {
            var keys = jedis.keys("architect:Product:*");
            if (!keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
        }
    }

    // --- SAVE ---

    @Test
    @Order(1)
    @DisplayName("save() - Nouvelle entite (receiver)")
    void testSaveNew() {
        Product p = new Product("Clavier", "Peripheriques", 49.99, 100, true);
        Product saved = repository.save(p);

        assertNotNull(saved);
        assertNotNull(saved.getId());
        assertEquals("Clavier", saved.getName());
    }

    @Test
    @Order(2)
    @DisplayName("save() - Update entite existante")
    void testSaveUpdate() {
        Product p = repository.save(new Product("Souris", "Peripheriques", 29.99, 50, true));
        Long id = p.getId();

        p.setPrice(19.99);
        Product updated = repository.save(p);

        assertEquals(id, updated.getId());
        assertEquals(19.99, updated.getPrice(), 0.01);
    }

    @Test
    @Order(3)
    @DisplayName("save() - Flush vers la base de donnees")
    void testSaveFlushToDb() {
        Product saved = repository.save(new Product("FlushTest", "Cat", 10.0, 1, true));
        assertNotNull(saved.getId());

        repository.flushUpdates();

        Awaitility.await().atMost(AWAIT).untilAsserted(() -> {
            Product fromCache = repository.findById(saved.getId());
            assertNotNull(fromCache);
            assertEquals("FlushTest", fromCache.getName());
        });
    }

    // --- FIND BY ID ---

    @Test
    @Order(10)
    @DisplayName("findById() - Hit cache Redis")
    void testFindByIdCacheHit() {
        Product saved = repository.save(new Product("CacheHit", "Cat", 10.0, 1, true));

        Product found = repository.findById(saved.getId());
        assertNotNull(found);
        assertEquals("CacheHit", found.getName());
    }

    @Test
    @Order(11)
    @DisplayName("findById() - ID inexistant retourne null")
    void testFindByIdNotFound() {
        Product found = repository.findById(999999L);
        assertNull(found);
    }

    // --- DELETE ---

    @Test
    @Order(20)
    @DisplayName("delete() - Supprime du cache et persiste (receiver)")
    void testDeleteReceiver() {
        Product saved = repository.save(new Product("ToDelete", "Cat", 5.0, 1, true));
        repository.flushUpdates();

        Awaitility.await().atMost(AWAIT).untilAsserted(() -> {
            assertNotNull(repository.findById(saved.getId()));
        });

        repository.delete(saved);
        repository.flushUpdates();

        Awaitility.await().atMost(AWAIT).untilAsserted(() -> {
            assertNull(repository.findById(saved.getId()));
        });
    }

    // --- ALL ---

    @Test
    @Order(30)
    @DisplayName("all() - Retourne toutes les entites du cache")
    void testAll() {
        repository.save(new Product("A", "Cat1", 10.0, 1, true));
        repository.save(new Product("B", "Cat2", 20.0, 2, true));
        repository.save(new Product("C", "Cat3", 30.0, 3, false));

        List<Product> all = repository.all();
        assertEquals(3, all.size());
    }

    @Test
    @Order(31)
    @DisplayName("all() - Liste vide quand aucune entite")
    void testAllEmpty() {
        List<Product> all = repository.all();
        assertTrue(all.isEmpty());
    }

    // --- QUERY BUILDER (cache-first) ---

    @Test
    @Order(40)
    @DisplayName("query().where(EQ) - Filtrage en memoire depuis le cache")
    void testQueryWhereEq() {
        repository.save(new Product("X1", "Electronics", 10.0, 5, true));
        repository.save(new Product("X2", "Electronics", 20.0, 10, true));
        repository.save(new Product("X3", "Books", 5.0, 100, true));

        List<Product> result = repository.query()
            .where("category", "Electronics")
            .findAll();

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(p -> "Electronics".equals(p.getCategory())));
    }

    @Test
    @Order(41)
    @DisplayName("query().where(GT) - Comparaison en memoire")
    void testQueryWhereGt() {
        repository.save(new Product("Cheap", "Cat", 5.0, 1, true));
        repository.save(new Product("Medium", "Cat", 50.0, 1, true));
        repository.save(new Product("Expensive", "Cat", 500.0, 1, true));

        List<Product> result = repository.query()
            .where("price", Operator.GT, 10.0)
            .findAll();

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(p -> p.getPrice() > 10.0));
    }

    @Test
    @Order(42)
    @DisplayName("query().whereLike() - Pattern matching en memoire")
    void testQueryWhereLike() {
        repository.save(new Product("Clavier mecanique", "Cat", 80.0, 10, true));
        repository.save(new Product("Clavier membrane", "Cat", 20.0, 50, true));
        repository.save(new Product("Souris gaming", "Cat", 40.0, 30, true));

        List<Product> result = repository.query()
            .whereLike("name", "Clavier%")
            .findAll();

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(p -> p.getName().startsWith("Clavier")));
    }

    @Test
    @Order(43)
    @DisplayName("query().whereIn() - Set membership en memoire")
    void testQueryWhereIn() {
        repository.save(new Product("A", "Electronics", 10.0, 1, true));
        repository.save(new Product("B", "Books", 20.0, 1, true));
        repository.save(new Product("C", "Games", 30.0, 1, true));

        List<Product> result = repository.query()
            .whereIn("category", List.of("Electronics", "Games"))
            .findAll();

        assertEquals(2, result.size());
    }

    @Test
    @Order(44)
    @DisplayName("query().whereNull() - Null check en memoire")
    void testQueryWhereNull() {
        Product p1 = new Product("WithDesc", "Cat", 10.0, 1, true);
        p1.setDescription("A description");
        repository.save(p1);
        repository.save(new Product("NoDesc", "Cat", 20.0, 1, true));

        List<Product> result = repository.query()
            .whereNull("description")
            .findAll();

        assertEquals(1, result.size());
        assertEquals("NoDesc", result.get(0).getName());
    }

    // --- ORDERING & PAGINATION (cache) ---

    @Test
    @Order(50)
    @DisplayName("query().orderBy() - Tri en memoire")
    void testQueryOrderBy() {
        repository.save(new Product("Charlie", "Cat", 30.0, 1, true));
        repository.save(new Product("Alpha", "Cat", 10.0, 1, true));
        repository.save(new Product("Bravo", "Cat", 20.0, 1, true));

        List<Product> result = repository.query()
            .orderBy("name")
            .findAll();

        assertEquals(3, result.size());
        assertEquals("Alpha", result.get(0).getName());
        assertEquals("Bravo", result.get(1).getName());
        assertEquals("Charlie", result.get(2).getName());
    }

    @Test
    @Order(51)
    @DisplayName("query().orderBy(DESC).limit() - Tri descendant + limit en memoire")
    void testQueryOrderByDescLimit() {
        repository.save(new Product("A", "Cat", 10.0, 1, true));
        repository.save(new Product("B", "Cat", 50.0, 1, true));
        repository.save(new Product("C", "Cat", 30.0, 1, true));

        List<Product> result = repository.query()
            .orderBy("price", SortOrder.DESC)
            .limit(2)
            .findAll();

        assertEquals(2, result.size());
        assertEquals(50.0, result.get(0).getPrice(), 0.01);
        assertEquals(30.0, result.get(1).getPrice(), 0.01);
    }

    @Test
    @Order(52)
    @DisplayName("query().limit().offset() - Pagination en memoire")
    void testQueryPagination() {
        for (int i = 0; i < 7; i++) {
            repository.save(new Product("Item" + i, "Cat", i * 10.0, i, true));
        }

        List<Product> page1 = repository.query().orderBy("name").limit(3).offset(0).findAll();
        List<Product> page2 = repository.query().orderBy("name").limit(3).offset(3).findAll();
        List<Product> page3 = repository.query().orderBy("name").limit(3).offset(6).findAll();

        assertEquals(3, page1.size());
        assertEquals(3, page2.size());
        assertEquals(1, page3.size());
    }

    // --- COUNT (cache) ---

    @Test
    @Order(60)
    @DisplayName("query().count() - Comptage depuis le cache")
    void testQueryCount() {
        repository.save(new Product("A", "Cat1", 10.0, 1, true));
        repository.save(new Product("B", "Cat2", 20.0, 1, true));
        repository.save(new Product("C", "Cat1", 30.0, 1, true));

        long count = repository.query()
            .where("category", "Cat1")
            .count();

        assertEquals(2, count);
    }

    @Test
    @Order(61)
    @DisplayName("query().count() - Zero resultats est un resultat valide (fix I4)")
    void testQueryCountZeroIsValid() {
        repository.save(new Product("A", "Cat1", 10.0, 1, true));

        long count = repository.query()
            .where("category", "NonExistent")
            .count();

        assertEquals(0, count);
    }

    // --- DELETE VIA QUERY ---

    @Test
    @Order(70)
    @DisplayName("query().delete() - Suppression avec condition")
    void testQueryDelete() {
        repository.save(new Product("Keep1", "Good", 10.0, 1, true));
        repository.save(new Product("Keep2", "Good", 20.0, 1, true));
        repository.save(new Product("Remove1", "Bad", 5.0, 1, false));
        repository.flushUpdates();

        Awaitility.await().atMost(AWAIT).untilAsserted(() -> {
            assertEquals(3, repository.all().size());
        });

        int deleted = repository.query()
            .where("category", "Bad")
            .delete();

        assertEquals(1, deleted);
    }

    // --- WHERERAW FALLBACK TO DB ---

    @Test
    @Order(80)
    @DisplayName("query().whereRaw() - Fallback vers la DB (pas d'eval en memoire)")
    void testQueryWhereRawFallsBackToDb() {
        repository.save(new Product("Clavier", "Cat", 50.0, 1, true));
        repository.save(new Product("CLAVIER", "Cat", 30.0, 1, true));
        repository.save(new Product("Souris", "Cat", 20.0, 1, true));
        repository.flushUpdates();

        Awaitility.await().atMost(AWAIT).untilAsserted(() -> {
            List<Product> result = repository.query()
                .whereRaw("LOWER(name) = :val", java.util.Map.of("val", "clavier"))
                .findAll();
            assertEquals(2, result.size());
        });
    }

    // --- COMBO ---

    @Test
    @Order(90)
    @DisplayName("query() - Conditions multiples + orderBy + limit")
    void testQueryFullCombo() {
        repository.save(new Product("A", "Tech", 100.0, 1, true));
        repository.save(new Product("B", "Tech", 200.0, 1, true));
        repository.save(new Product("C", "Tech", 300.0, 1, true));
        repository.save(new Product("D", "Tech", 400.0, 1, true));
        repository.save(new Product("E", "Other", 500.0, 1, true));

        List<Product> result = repository.query()
            .where("category", "Tech")
            .orderBy("price", SortOrder.DESC)
            .limit(2)
            .findAll();

        assertEquals(2, result.size());
        assertEquals("D", result.get(0).getName());
        assertEquals("C", result.get(1).getName());
    }

    @Test
    @Order(91)
    @DisplayName("findFirst() - Retourne le premier depuis le cache")
    void testFindFirst() {
        repository.save(new Product("Alpha", "Cat", 10.0, 1, true));
        repository.save(new Product("Beta", "Cat", 20.0, 1, true));

        Product result = repository.query()
            .where("name", "Alpha")
            .findFirst();

        assertNotNull(result);
        assertEquals("Alpha", result.getName());
    }

    @Test
    @Order(92)
    @DisplayName("findFirst() - Retourne null si pas de resultat")
    void testFindFirstNull() {
        repository.save(new Product("Test", "Cat", 10.0, 1, true));

        Product result = repository.query()
            .where("name", "Ghost")
            .findFirst();

        assertNull(result);
    }

    // --- FLUSH UPDATES RESILIENCE ---

    @Test
    @Order(100)
    @DisplayName("flushUpdates() - Batch vide ne provoque pas d'erreur")
    void testFlushUpdatesEmpty() {
        assertDoesNotThrow(() -> repository.flushUpdates());
    }

    @Test
    @Order(101)
    @DisplayName("flushUpdates() - Plusieurs save puis flush")
    void testFlushUpdatesMultipleSaves() {
        for (int i = 0; i < 25; i++) {
            repository.save(new Product("Batch" + i, "Cat", i * 5.0, i, true));
        }

        repository.flushUpdates();

        Awaitility.await().atMost(AWAIT).untilAsserted(() -> {
            List<Product> all = repository.all();
            assertEquals(25, all.size());
        });
    }
}
