package sh.fyz.architect.test;

import org.junit.jupiter.api.*;
import sh.fyz.architect.Architect;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;
import sh.fyz.architect.repositories.GenericRepository;
import sh.fyz.architect.repositories.QueryBuilder;
import sh.fyz.architect.repositories.QueryBuilder.Operator;
import sh.fyz.architect.repositories.QueryBuilder.SortOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GenericRepository - Tests complets")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GenericRepositoryTest {

    private Architect architect;
    private GenericRepository<Product> repository;

    @BeforeAll
    void setup() {
        String host = System.getenv().getOrDefault("DB_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5440"));
        String db = System.getenv().getOrDefault("DB_NAME", "freshapi");
        String user = System.getenv().getOrDefault("DB_USER", "freshapi");
        String pass = System.getenv().getOrDefault("DB_PASS", "freshapi");

        architect = new Architect()
            .setReceiver(true)
            .setDatabaseCredentials(new DatabaseCredentials(
                new PostgreSQLAuth(host, port, db),
                user, pass, 4, 4, "create-drop"
            ));
        architect.addEntityClass(Product.class);
        architect.start();
        repository = new GenericRepository<>(Product.class);
    }

    @AfterAll
    void teardown() {
        if (architect != null) {
            architect.stop();
        }
    }

    @BeforeEach
    void cleanTable() {
        List<Product> all = repository.all();
        for (Product p : all) {
            repository.delete(p);
        }
    }

    @Test
    @Order(1)
    @DisplayName("save() - Creer une nouvelle entite")
    void testSaveNew() {
        Product p = new Product("Clavier", "Peripheriques", 49.99, 100, true);
        Product saved = repository.save(p);

        assertNotNull(saved);
        assertNotNull(saved.getId());
        assertEquals("Clavier", saved.getName());
        assertEquals(49.99, saved.getPrice(), 0.01);
    }

    @Test
    @Order(2)
    @DisplayName("save() - Mettre a jour une entite existante")
    void testSaveUpdate() {
        Product p = repository.save(new Product("Souris", "Peripheriques", 29.99, 50, true));
        Long id = p.getId();

        p.setPrice(24.99);
        p.setStock(45);
        Product updated = repository.save(p);

        assertEquals(id, updated.getId());
        assertEquals(24.99, updated.getPrice(), 0.01);
        assertEquals(45, updated.getStock());
    }

    @Test
    @Order(3)
    @DisplayName("findById() - Trouver par ID existant")
    void testFindByIdExists() {
        Product saved = repository.save(new Product("Ecran", "Moniteurs", 299.99, 20, true));
        Product found = repository.findById(saved.getId());

        assertNotNull(found);
        assertEquals(saved.getId(), found.getId());
        assertEquals("Ecran", found.getName());
    }

    @Test
    @Order(4)
    @DisplayName("findById() - Retourner null pour ID inexistant")
    void testFindByIdNotExists() {
        Product found = repository.findById(999999L);
        assertNull(found);
    }

    @Test
    @Order(5)
    @DisplayName("all() - Recuperer toutes les entites")
    void testAll() {
        repository.save(new Product("A", "Cat1", 10.0, 1, true));
        repository.save(new Product("B", "Cat2", 20.0, 2, true));
        repository.save(new Product("C", "Cat3", 30.0, 3, false));

        List<Product> all = repository.all();
        assertEquals(3, all.size());
    }

    @Test
    @Order(6)
    @DisplayName("all() - Liste vide si aucune entite")
    void testAllEmpty() {
        List<Product> all = repository.all();
        assertTrue(all.isEmpty());
    }

    @Test
    @Order(7)
    @DisplayName("delete() - Supprimer une entite")
    void testDelete() {
        Product saved = repository.save(new Product("Temp", "Temp", 1.0, 1, true));
        Long id = saved.getId();
        assertNotNull(repository.findById(id));

        repository.delete(saved);
        assertNull(repository.findById(id));
    }

    @Test
    @Order(10)
    @DisplayName("query().where(EQ) - findFirst()")
    void testQueryWhereEqFindFirst() {
        repository.save(new Product("Alpha", "Cat1", 10.0, 5, true));
        repository.save(new Product("Beta", "Cat2", 20.0, 10, true));

        Product result = repository.query()
            .where("name", "Alpha")
            .findFirst();

        assertNotNull(result);
        assertEquals("Alpha", result.getName());
    }

    @Test
    @Order(11)
    @DisplayName("query().where(EQ) - findAll()")
    void testQueryWhereEqFindAll() {
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
    @Order(12)
    @DisplayName("query().where(EQ) - findFirst() retourne null si pas de resultat")
    void testQueryWhereEqNoResult() {
        repository.save(new Product("Test", "Cat", 10.0, 5, true));

        Product result = repository.query()
            .where("name", "Inexistant")
            .findFirst();

        assertNull(result);
    }

    @Test
    @Order(20)
    @DisplayName("query().where(GT) - Superieur strict")
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
    @Order(21)
    @DisplayName("query().where(GTE) - Superieur ou egal")
    void testQueryWhereGte() {
        repository.save(new Product("A", "Cat", 10.0, 1, true));
        repository.save(new Product("B", "Cat", 50.0, 1, true));
        repository.save(new Product("C", "Cat", 5.0, 1, true));

        List<Product> result = repository.query()
            .where("price", Operator.GTE, 10.0)
            .findAll();

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(p -> p.getPrice() >= 10.0));
    }

    @Test
    @Order(22)
    @DisplayName("query().where(LT) - Inferieur strict")
    void testQueryWhereLt() {
        repository.save(new Product("A", "Cat", 5.0, 1, true));
        repository.save(new Product("B", "Cat", 50.0, 1, true));
        repository.save(new Product("C", "Cat", 100.0, 1, true));

        List<Product> result = repository.query()
            .where("price", Operator.LT, 50.0)
            .findAll();

        assertEquals(1, result.size());
        assertEquals("A", result.get(0).getName());
    }

    @Test
    @Order(23)
    @DisplayName("query().where(LTE) - Inferieur ou egal")
    void testQueryWhereLte() {
        repository.save(new Product("A", "Cat", 5.0, 1, true));
        repository.save(new Product("B", "Cat", 50.0, 1, true));
        repository.save(new Product("C", "Cat", 100.0, 1, true));

        List<Product> result = repository.query()
            .where("price", Operator.LTE, 50.0)
            .findAll();

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(p -> p.getPrice() <= 50.0));
    }

    @Test
    @Order(24)
    @DisplayName("query().where(NEQ) - Different de")
    void testQueryWhereNeq() {
        repository.save(new Product("A", "Cat1", 10.0, 1, true));
        repository.save(new Product("B", "Cat2", 20.0, 1, true));
        repository.save(new Product("C", "Cat1", 30.0, 1, true));

        List<Product> result = repository.query()
            .where("category", Operator.NEQ, "Cat1")
            .findAll();

        assertEquals(1, result.size());
        assertEquals("B", result.get(0).getName());
    }

    @Test
    @Order(30)
    @DisplayName("query().whereLike() - Pattern avec %")
    void testQueryWhereLike() {
        repository.save(new Product("Clavier mecanique", "Peripheriques", 80.0, 10, true));
        repository.save(new Product("Clavier membrane", "Peripheriques", 20.0, 50, true));
        repository.save(new Product("Souris gaming", "Peripheriques", 40.0, 30, true));

        List<Product> result = repository.query()
            .whereLike("name", "Clavier%")
            .findAll();

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(p -> p.getName().startsWith("Clavier")));
    }

    @Test
    @Order(31)
    @DisplayName("query().whereLike() - Pattern au milieu")
    void testQueryWhereLikeMiddle() {
        repository.save(new Product("Clavier mecanique RGB", "Cat", 80.0, 10, true));
        repository.save(new Product("Souris mecanique", "Cat", 40.0, 30, true));
        repository.save(new Product("Ecran LED", "Cat", 200.0, 5, true));

        List<Product> result = repository.query()
            .whereLike("name", "%mecanique%")
            .findAll();

        assertEquals(2, result.size());
    }

    @Test
    @Order(40)
    @DisplayName("query().whereIn() - Valeurs dans une liste")
    void testQueryWhereIn() {
        repository.save(new Product("A", "Electronics", 10.0, 1, true));
        repository.save(new Product("B", "Books", 20.0, 1, true));
        repository.save(new Product("C", "Games", 30.0, 1, true));
        repository.save(new Product("D", "Food", 5.0, 1, true));

        List<Product> result = repository.query()
            .whereIn("category", List.of("Electronics", "Games"))
            .findAll();

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(p ->
            "Electronics".equals(p.getCategory()) || "Games".equals(p.getCategory())
        ));
    }

    @Test
    @Order(41)
    @DisplayName("query().whereNotIn() - Valeurs pas dans une liste")
    void testQueryWhereNotIn() {
        repository.save(new Product("A", "Electronics", 10.0, 1, true));
        repository.save(new Product("B", "Books", 20.0, 1, true));
        repository.save(new Product("C", "Games", 30.0, 1, true));

        List<Product> result = repository.query()
            .whereNotIn("category", List.of("Electronics"))
            .findAll();

        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(p -> "Electronics".equals(p.getCategory())));
    }

    @Test
    @Order(50)
    @DisplayName("query().whereNull() - Champ null")
    void testQueryWhereNull() {
        Product p1 = new Product("WithDesc", "Cat", 10.0, 1, true);
        p1.setDescription("A description");
        repository.save(p1);

        Product p2 = new Product("NoDesc", "Cat", 20.0, 1, true);
        repository.save(p2);

        List<Product> result = repository.query()
            .whereNull("description")
            .findAll();

        assertEquals(1, result.size());
        assertEquals("NoDesc", result.get(0).getName());
    }

    @Test
    @Order(51)
    @DisplayName("query().whereNotNull() - Champ non null")
    void testQueryWhereNotNull() {
        Product p1 = new Product("WithDesc", "Cat", 10.0, 1, true);
        p1.setDescription("A description");
        repository.save(p1);

        Product p2 = new Product("NoDesc", "Cat", 20.0, 1, true);
        repository.save(p2);

        List<Product> result = repository.query()
            .whereNotNull("description")
            .findAll();

        assertEquals(1, result.size());
        assertEquals("WithDesc", result.get(0).getName());
    }

    @Test
    @Order(60)
    @DisplayName("query().orderBy() - Tri ascendant (defaut)")
    void testQueryOrderByAsc() {
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
    @Order(61)
    @DisplayName("query().orderBy(DESC) - Tri descendant")
    void testQueryOrderByDesc() {
        repository.save(new Product("A", "Cat", 10.0, 1, true));
        repository.save(new Product("B", "Cat", 50.0, 1, true));
        repository.save(new Product("C", "Cat", 30.0, 1, true));

        List<Product> result = repository.query()
            .orderBy("price", SortOrder.DESC)
            .findAll();

        assertEquals(3, result.size());
        assertEquals(50.0, result.get(0).getPrice(), 0.01);
        assertEquals(30.0, result.get(1).getPrice(), 0.01);
        assertEquals(10.0, result.get(2).getPrice(), 0.01);
    }

    @Test
    @Order(62)
    @DisplayName("query().orderBy() - Tri multi-critere")
    void testQueryOrderByMultiple() {
        repository.save(new Product("B", "Cat1", 20.0, 1, true));
        repository.save(new Product("A", "Cat2", 10.0, 1, true));
        repository.save(new Product("C", "Cat1", 30.0, 1, true));
        repository.save(new Product("A", "Cat1", 5.0, 1, true));

        List<Product> result = repository.query()
            .orderBy("category")
            .orderBy("name")
            .findAll();

        assertEquals(4, result.size());
        assertEquals("Cat1", result.get(0).getCategory());
        assertEquals("A", result.get(0).getName());
        assertEquals("Cat1", result.get(1).getCategory());
        assertEquals("B", result.get(1).getName());
    }

    @Test
    @Order(70)
    @DisplayName("query().limit() - Limiter les resultats")
    void testQueryLimit() {
        for (int i = 0; i < 10; i++) {
            repository.save(new Product("Product" + i, "Cat", i * 10.0, i, true));
        }

        List<Product> result = repository.query()
            .limit(3)
            .findAll();

        assertEquals(3, result.size());
    }

    @Test
    @Order(71)
    @DisplayName("query().offset().limit() - Pagination")
    void testQueryPagination() {
        for (int i = 0; i < 10; i++) {
            repository.save(new Product("P" + String.format("%02d", i), "Cat", i * 10.0, i, true));
        }

        List<Product> page1 = repository.query()
            .orderBy("name")
            .limit(3)
            .offset(0)
            .findAll();

        List<Product> page2 = repository.query()
            .orderBy("name")
            .limit(3)
            .offset(3)
            .findAll();

        assertEquals(3, page1.size());
        assertEquals(3, page2.size());
        assertNotEquals(page1.get(0).getName(), page2.get(0).getName());
        assertEquals("P00", page1.get(0).getName());
        assertEquals("P03", page2.get(0).getName());
    }

    @Test
    @Order(72)
    @DisplayName("query().offset() - Offset depasse le nombre total")
    void testQueryOffsetBeyondTotal() {
        repository.save(new Product("Only", "Cat", 10.0, 1, true));

        List<Product> result = repository.query()
            .offset(100)
            .findAll();

        assertTrue(result.isEmpty());
    }

    @Test
    @Order(80)
    @DisplayName("query().count() - Compter toutes les entites")
    void testQueryCountAll() {
        repository.save(new Product("A", "Cat1", 10.0, 1, true));
        repository.save(new Product("B", "Cat2", 20.0, 1, true));
        repository.save(new Product("C", "Cat1", 30.0, 1, true));

        long count = repository.query().count();
        assertEquals(3, count);
    }

    @Test
    @Order(81)
    @DisplayName("query().count() - Compter avec condition")
    void testQueryCountWithCondition() {
        repository.save(new Product("A", "Cat1", 10.0, 1, true));
        repository.save(new Product("B", "Cat2", 20.0, 1, true));
        repository.save(new Product("C", "Cat1", 30.0, 1, true));

        long count = repository.query()
            .where("category", "Cat1")
            .count();

        assertEquals(2, count);
    }

    @Test
    @Order(82)
    @DisplayName("query().count() - Compter zero resultats")
    void testQueryCountZero() {
        long count = repository.query()
            .where("category", "NonExistent")
            .count();

        assertEquals(0, count);
    }

    @Test
    @Order(90)
    @DisplayName("query().delete() - Supprimer avec condition")
    void testQueryDelete() {
        repository.save(new Product("Keep1", "Good", 10.0, 1, true));
        repository.save(new Product("Keep2", "Good", 20.0, 1, true));
        repository.save(new Product("Remove1", "Bad", 5.0, 1, false));
        repository.save(new Product("Remove2", "Bad", 3.0, 1, false));

        int deleted = repository.query()
            .where("category", "Bad")
            .delete();

        assertEquals(2, deleted);
        assertEquals(2, repository.all().size());
        assertTrue(repository.all().stream().allMatch(p -> "Good".equals(p.getCategory())));
    }

    @Test
    @Order(91)
    @DisplayName("query().delete() - Erreur si pas de condition")
    void testQueryDeleteWithoutCondition() {
        repository.save(new Product("A", "Cat", 10.0, 1, true));

        assertThrows(IllegalStateException.class, () -> {
            repository.query().delete();
        });
    }

    @Test
    @Order(100)
    @DisplayName("query() - Conditions multiples AND")
    void testQueryMultipleConditions() {
        repository.save(new Product("A", "Electronics", 500.0, 10, true));
        repository.save(new Product("B", "Electronics", 50.0, 100, true));
        repository.save(new Product("C", "Books", 500.0, 5, true));
        repository.save(new Product("D", "Electronics", 300.0, 0, false));

        List<Product> result = repository.query()
            .where("category", "Electronics")
            .where("price", Operator.GTE, 100.0)
            .where("active", true)
            .findAll();

        assertEquals(1, result.size());
        assertEquals("A", result.get(0).getName());
    }

    @Test
    @Order(101)
    @DisplayName("query() - Combinaison where + orderBy + limit")
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
    @Order(110)
    @DisplayName("query().whereRaw() - HQL brut avec parametres")
    void testQueryWhereRaw() {
        repository.save(new Product("Clavier", "Cat", 50.0, 1, true));
        repository.save(new Product("CLAVIER", "Cat", 30.0, 1, true));
        repository.save(new Product("Souris", "Cat", 20.0, 1, true));

        List<Product> result = repository.query()
            .whereRaw("LOWER(name) = :val", Map.of("val", "clavier"))
            .findAll();

        assertEquals(2, result.size());
    }

    @Test
    @Order(111)
    @DisplayName("query().whereRaw() - BETWEEN avec parametres")
    void testQueryWhereRawBetween() {
        repository.save(new Product("Cheap", "Cat", 5.0, 1, true));
        repository.save(new Product("Medium", "Cat", 50.0, 1, true));
        repository.save(new Product("Expensive", "Cat", 500.0, 1, true));

        List<Product> result = repository.query()
            .whereRaw("price BETWEEN :min AND :max", Map.of("min", 10.0, "max", 100.0))
            .findAll();

        assertEquals(1, result.size());
        assertEquals("Medium", result.get(0).getName());
    }

    @Test
    @Order(112)
    @DisplayName("query().whereRaw() - Combinaison raw + builder")
    void testQueryWhereRawCombined() {
        repository.save(new Product("Active Cheap", "Electronics", 5.0, 10, true));
        repository.save(new Product("Active Expensive", "Electronics", 500.0, 10, true));
        repository.save(new Product("Inactive", "Electronics", 50.0, 10, false));
        repository.save(new Product("Other", "Books", 50.0, 10, true));

        List<Product> result = repository.query()
            .where("category", "Electronics")
            .where("active", true)
            .whereRaw("price > :minPrice", Map.of("minPrice", 10.0))
            .findAll();

        assertEquals(1, result.size());
        assertEquals("Active Expensive", result.get(0).getName());
    }

    @Test
    @Order(113)
    @DisplayName("query().whereRaw() - Sans parametres")
    @SuppressWarnings("deprecation")
    void testQueryWhereRawNoParams() {
        repository.save(new Product("Active", "Cat", 10.0, 1, true));
        repository.save(new Product("Inactive", "Cat", 20.0, 1, false));

        List<Product> result = repository.query()
            .whereRaw("active = true")
            .findAll();

        assertEquals(1, result.size());
        assertEquals("Active", result.get(0).getName());
    }

    @Test
    @Order(120)
    @DisplayName("Validation - Nom de champ invalide dans where()")
    void testValidationInvalidFieldWhere() {
        assertThrows(IllegalArgumentException.class, () -> {
            repository.query()
                .where("nonExistentField", "value")
                .findAll();
        });
    }

    @Test
    @Order(121)
    @DisplayName("Validation - Nom de champ invalide dans orderBy()")
    void testValidationInvalidFieldOrderBy() {
        assertThrows(IllegalArgumentException.class, () -> {
            repository.query()
                .orderBy("nonExistentField")
                .findAll();
        });
    }

    @Test
    @Order(122)
    @DisplayName("Validation - Limit negatif")
    void testValidationNegativeLimit() {
        assertThrows(IllegalArgumentException.class, () -> {
            repository.query().limit(-1);
        });
    }

    @Test
    @Order(123)
    @DisplayName("Validation - Offset negatif")
    void testValidationNegativeOffset() {
        assertThrows(IllegalArgumentException.class, () -> {
            repository.query().offset(-1);
        });
    }

    @Test
    @Order(130)
    @DisplayName("saveAsync() - Sauvegarde asynchrone")
    void testSaveAsync() throws Exception {
        CompletableFuture<Product> future = new CompletableFuture<>();
        Product p = new Product("Async", "Cat", 10.0, 1, true);

        repository.saveAsync(p,
            future::complete,
            future::completeExceptionally
        );

        Product saved = future.get(5, TimeUnit.SECONDS);
        assertNotNull(saved);
        assertNotNull(saved.getId());
        assertEquals("Async", saved.getName());
    }

    @Test
    @Order(131)
    @DisplayName("query().findAllAsync() - Requete asynchrone")
    void testQueryFindAllAsync() throws Exception {
        repository.save(new Product("A", "Cat", 10.0, 1, true));
        repository.save(new Product("B", "Cat", 20.0, 1, true));

        List<Product> result = repository.query()
            .where("category", "Cat")
            .findAllAsync()
            .get(5, TimeUnit.SECONDS);

        assertEquals(2, result.size());
    }

    @Test
    @Order(132)
    @DisplayName("query().findFirstAsync() - Recherche async du premier")
    void testQueryFindFirstAsync() throws Exception {
        repository.save(new Product("Target", "Special", 99.0, 1, true));
        repository.save(new Product("Other", "Normal", 10.0, 1, true));

        Product result = repository.query()
            .where("category", "Special")
            .findFirstAsync()
            .get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals("Target", result.getName());
    }

    @Test
    @Order(133)
    @DisplayName("query().countAsync() - Comptage asynchrone")
    void testQueryCountAsync() throws Exception {
        repository.save(new Product("A", "Cat", 10.0, 1, true));
        repository.save(new Product("B", "Cat", 20.0, 1, true));
        repository.save(new Product("C", "Other", 30.0, 1, true));

        long count = repository.query()
            .where("category", "Cat")
            .countAsync()
            .get(5, TimeUnit.SECONDS);

        assertEquals(2, count);
    }

    @Test
    @Order(134)
    @DisplayName("query().deleteAsync() - Suppression asynchrone")
    void testQueryDeleteAsync() throws Exception {
        repository.save(new Product("Keep", "Good", 10.0, 1, true));
        repository.save(new Product("Remove", "Bad", 5.0, 1, false));

        int deleted = repository.query()
            .where("category", "Bad")
            .deleteAsync()
            .get(5, TimeUnit.SECONDS);

        assertEquals(1, deleted);
        assertEquals(1, repository.all().size());
    }

    @Test
    @Order(140)
    @DisplayName("query() sans condition - Equivalent de all()")
    void testQueryNoCondition() {
        repository.save(new Product("A", "Cat", 10.0, 1, true));
        repository.save(new Product("B", "Cat", 20.0, 1, true));

        List<Product> result = repository.query().findAll();
        assertEquals(2, result.size());
    }

    @Test
    @Order(141)
    @DisplayName("query().findFirst() sur liste vide")
    void testQueryFindFirstEmpty() {
        Product result = repository.query()
            .where("name", "Ghost")
            .findFirst();

        assertNull(result);
    }

    @Test
    @Order(142)
    @DisplayName("query() - Recherche booleenne")
    void testQueryBooleanField() {
        repository.save(new Product("Active1", "Cat", 10.0, 1, true));
        repository.save(new Product("Active2", "Cat", 20.0, 1, true));
        repository.save(new Product("Inactive", "Cat", 5.0, 1, false));

        List<Product> active = repository.query()
            .where("active", true)
            .findAll();

        List<Product> inactive = repository.query()
            .where("active", false)
            .findAll();

        assertEquals(2, active.size());
        assertEquals(1, inactive.size());
    }

    @Test
    @Order(143)
    @DisplayName("query() - Pagination complete (toutes les pages)")
    void testQueryFullPagination() {
        for (int i = 0; i < 7; i++) {
            repository.save(new Product("Item" + i, "Cat", i * 10.0, i, true));
        }

        int pageSize = 3;
        List<Product> page1 = repository.query().orderBy("name").limit(pageSize).offset(0).findAll();
        List<Product> page2 = repository.query().orderBy("name").limit(pageSize).offset(3).findAll();
        List<Product> page3 = repository.query().orderBy("name").limit(pageSize).offset(6).findAll();

        assertEquals(3, page1.size());
        assertEquals(3, page2.size());
        assertEquals(1, page3.size());
    }

    @Test
    @Order(144)
    @DisplayName("query().whereRaw() - Multiple raw conditions")
    void testQueryMultipleRawConditions() {
        repository.save(new Product("Match", "Electronics", 50.0, 10, true));
        repository.save(new Product("TooExpensive", "Electronics", 500.0, 10, true));
        repository.save(new Product("LowStock", "Electronics", 50.0, 0, true));

        List<Product> result = repository.query()
            .whereRaw("price BETWEEN :min AND :max", Map.of("min", 10.0, "max", 100.0))
            .whereRaw("stock > :minStock", Map.of("minStock", 5))
            .findAll();

        assertEquals(1, result.size());
        assertEquals("Match", result.get(0).getName());
    }

    private record BenchResult(String name, int iterations, long totalMs, double avgMs) {
        @Override
        public String toString() {
            return String.format("  %-45s | %6d ops | %7d ms total | %8.3f ms/op",
                name, iterations, totalMs, avgMs);
        }
    }

    private BenchResult bench(String name, int iterations, Runnable task) {
        task.run();

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            task.run();
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        double avg = (double) elapsed / iterations;
        return new BenchResult(name, iterations, elapsed, avg);
    }

    @Test
    @Order(200)
    @DisplayName("BENCHMARK - Performance des operations")
    void benchmarkAll() {
        List<BenchResult> results = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            repository.save(new Product(
                "BenchProduct" + i,
                i % 2 == 0 ? "CatA" : "CatB",
                10.0 + i,
                i,
                i % 3 != 0
            ));
        }
        List<Product> allProducts = repository.all();
        Long sampleId = allProducts.get(0).getId();

        System.out.println();
        System.out.println("=".repeat(90));
        System.out.println("  BENCHMARK - GenericRepository (100 entites en base)");
        System.out.println("=".repeat(90));

        results.add(bench("save() - nouvelle entite", 100, () -> {
            repository.save(new Product("Tmp", "Bench", 1.0, 1, true));
        }));

        Product toUpdate = repository.findById(sampleId);
        results.add(bench("save() - update existant", 200, () -> {
            toUpdate.setStock(toUpdate.getStock() + 1);
            repository.save(toUpdate);
        }));

        results.add(bench("findById()", 500, () -> {
            repository.findById(sampleId);
        }));

        results.add(bench("all()", 100, () -> {
            repository.all();
        }));

        results.add(bench("query().where(EQ).findFirst()", 200, () -> {
            repository.query().where("name", "BenchProduct50").findFirst();
        }));

        results.add(bench("query().where(EQ).findAll()", 200, () -> {
            repository.query().where("category", "CatA").findAll();
        }));

        results.add(bench("query().where(GT).findAll()", 200, () -> {
            repository.query().where("price", Operator.GT, 50.0).findAll();
        }));

        results.add(bench("query().whereLike().findAll()", 200, () -> {
            repository.query().whereLike("name", "BenchProduct1%").findAll();
        }));

        results.add(bench("query().whereIn().findAll()", 200, () -> {
            repository.query().whereIn("category", List.of("CatA", "CatB")).findAll();
        }));

        results.add(bench("query().orderBy(price DESC).findAll()", 100, () -> {
            repository.query().orderBy("price", SortOrder.DESC).findAll();
        }));

        results.add(bench("query().orderBy().limit(10).findAll()", 200, () -> {
            repository.query().orderBy("price", SortOrder.DESC).limit(10).findAll();
        }));

        results.add(bench("query() combo (where+order+limit)", 200, () -> {
            repository.query()
                .where("category", "CatA")
                .where("active", true)
                .orderBy("price", SortOrder.DESC)
                .limit(5)
                .findAll();
        }));

        results.add(bench("query().count()", 200, () -> {
            repository.query().where("category", "CatA").count();
        }));

        results.add(bench("query().whereRaw(BETWEEN).findAll()", 200, () -> {
            repository.query()
                .whereRaw("price BETWEEN :min AND :max", Map.of("min", 20.0, "max", 80.0))
                .findAll();
        }));

        results.add(bench("query().limit(10).offset(50).findAll()", 200, () -> {
            repository.query().orderBy("id").limit(10).offset(50).findAll();
        }));

        results.add(bench("query().where().delete()", 50, () -> {
            repository.save(new Product("ToDelete", "Trash", 0.0, 0, false));
            repository.query().where("name", "ToDelete").delete();
        }));

        System.out.println("-".repeat(90));
        for (BenchResult r : results) {
            System.out.println(r);
        }
        System.out.println("-".repeat(90));

        double totalMs = results.stream().mapToLong(BenchResult::totalMs).sum();
        int totalOps = results.stream().mapToInt(BenchResult::iterations).sum();
        System.out.printf("  TOTAL : %d operations en %.0f ms (%.3f ms/op moyenne)%n",
            totalOps, totalMs, totalMs / totalOps);
        System.out.println("=".repeat(90));
        System.out.println();

        assertTrue(true);
    }
}
