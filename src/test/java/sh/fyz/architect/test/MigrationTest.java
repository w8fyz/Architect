package sh.fyz.architect.test;

import org.junit.jupiter.api.*;
import sh.fyz.architect.Architect;
import sh.fyz.architect.migration.DatabaseInspector;
import sh.fyz.architect.migration.MigrationManager;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Migration System")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MigrationTest {

    private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final int DB_PORT = Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5440"));
    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "architect_test");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "architect");
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "architect");

    private Architect architect;
    private MigrationManager manager;
    private Path migrationDir;

    @BeforeAll
    void setUp() throws IOException {
        migrationDir = Files.createTempDirectory("architect-migration-test-");

        architect = new Architect()
                .setReceiver(true)
                .setDatabaseCredentials(new DatabaseCredentials(
                        new PostgreSQLAuth(DB_HOST, DB_PORT, DB_NAME),
                        DB_USER, DB_PASS, 2, 2, "update"
                ));
        architect.addEntityClass(Product.class);
        architect.start();

        manager = new MigrationManager(architect, migrationDir);
    }

    @AfterAll
    void tearDown() throws IOException {
        if (architect != null) {
            architect.stop();
        }
        if (migrationDir != null && Files.exists(migrationDir)) {
            Files.walk(migrationDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }

    // ========================
    // Schema Generation
    // ========================

    @Test
    @Order(1)
    @DisplayName("generateSchema() retourne du DDL non vide")
    void testGenerateSchema() {
        String ddl = manager.generateSchema();
        assertNotNull(ddl);
        assertFalse(ddl.isBlank());
        assertTrue(ddl.contains("create table") || ddl.contains("CREATE TABLE") || ddl.contains("create  table"),
                "DDL should contain CREATE TABLE statements");
    }

    @Test
    @Order(2)
    @DisplayName("generateSchema() contient le nom de la table d'entite")
    void testGenerateSchemaContainsEntity() {
        String ddl = manager.generateSchema().toLowerCase();
        assertTrue(ddl.contains("test_products"), "DDL should reference the test_products table");
    }

    // ========================
    // Create Migration
    // ========================

    @Test
    @Order(10)
    @DisplayName("createMigration() cree un fichier SQL")
    void testCreateMigration() {
        Path file = manager.createMigration("v1_initial");
        assertTrue(Files.exists(file));
        assertEquals("v1_initial.sql", file.getFileName().toString());
    }

    @Test
    @Order(11)
    @DisplayName("createMigration() - le fichier contient du DDL")
    void testCreateMigrationContent() throws IOException {
        Path file = manager.createMigration("v2_check_content");
        String content = Files.readString(file);
        assertFalse(content.isBlank());
        assertTrue(content.contains("Architect Migration Schema"));
    }

    @Test
    @Order(12)
    @DisplayName("createMigration() - nom null rejete")
    void testCreateMigrationNullName() {
        assertThrows(IllegalArgumentException.class, () -> manager.createMigration(null));
    }

    @Test
    @Order(13)
    @DisplayName("createMigration() - nom vide rejete")
    void testCreateMigrationBlankName() {
        assertThrows(IllegalArgumentException.class, () -> manager.createMigration("   "));
    }

    // ========================
    // List Migrations
    // ========================

    @Test
    @Order(20)
    @DisplayName("listMigrations() retourne les fichiers crees")
    void testListMigrations() {
        List<String> migrations = manager.listMigrations();
        assertFalse(migrations.isEmpty());
        assertTrue(migrations.contains("v1_initial.sql"));
    }

    // ========================
    // Read Migration Content
    // ========================

    @Test
    @Order(25)
    @DisplayName("readMigrationContent() retourne le contenu")
    void testReadMigrationContent() {
        String content = manager.readMigrationContent("v1_initial");
        assertNotNull(content);
        assertFalse(content.isBlank());
    }

    @Test
    @Order(26)
    @DisplayName("readMigrationContent() - fichier inexistant rejete")
    void testReadMigrationContentNotFound() {
        assertThrows(IllegalArgumentException.class, () -> manager.readMigrationContent("nonexistent"));
    }

    // ========================
    // Database Inspection
    // ========================

    @Test
    @Order(30)
    @DisplayName("listTables() retourne les tables existantes")
    void testListTables() {
        List<DatabaseInspector.TableInfo> tables = manager.listTables();
        assertNotNull(tables);
        assertFalse(tables.isEmpty(), "Should have at least the test_products table");
        assertTrue(tables.stream().anyMatch(t -> t.name().equals("test_products")),
                "Should find test_products table");
    }

    @Test
    @Order(31)
    @DisplayName("getTableSchema() retourne le schema correct")
    void testGetTableSchema() {
        DatabaseInspector.TableSchema schema = manager.getTableSchema("test_products");
        assertNotNull(schema);
        assertEquals("test_products", schema.tableName());
        assertFalse(schema.columns().isEmpty());
        assertTrue(schema.columns().stream().anyMatch(c -> c.name().equals("name")));
        assertTrue(schema.columns().stream().anyMatch(c -> c.name().equals("price")));
    }

    @Test
    @Order(32)
    @DisplayName("getTableSchema() - table inexistante leve une exception")
    void testGetTableSchemaInvalid() {
        assertThrows(Exception.class, () -> manager.getTableSchema("nonexistent_table_xyz"));
    }

    @Test
    @Order(33)
    @DisplayName("getTableData() retourne des donnees paginee")
    void testGetTableData() {
        DatabaseInspector.TableData data = manager.getTableData("test_products", 0, 10);
        assertNotNull(data);
        assertEquals("test_products", data.tableName());
        assertEquals(0, data.page());
        assertEquals(10, data.pageSize());
        assertNotNull(data.columnNames());
        assertFalse(data.columnNames().isEmpty());
    }

    // ========================
    // Execute Migration
    // ========================

    @Test
    @Order(40)
    @DisplayName("executeSql() execute du SQL valide")
    void testExecuteSql() {
        assertDoesNotThrow(() ->
                manager.executeSql("CREATE TABLE IF NOT EXISTS \"migration_exec_test\" (\"id\" SERIAL PRIMARY KEY, \"val\" VARCHAR(50))")
        );
        assertDoesNotThrow(() ->
                manager.executeSql("DROP TABLE IF EXISTS \"migration_exec_test\"")
        );
    }

    @Test
    @Order(41)
    @DisplayName("executeMigration() - fichier inexistant rejete")
    void testExecuteMigrationNotFound() {
        assertThrows(IllegalArgumentException.class, () -> manager.executeMigration("ghost_migration"));
    }

    // ========================
    // Clear Database
    // ========================

    @Test
    @Order(50)
    @DisplayName("clearDatabase() vide toutes les tables")
    void testClearDatabase() {
        assertDoesNotThrow(() -> manager.clearDatabase());
        List<DatabaseInspector.TableInfo> tables = manager.listTables();
        assertTrue(tables.isEmpty(), "All tables should be dropped after clearDatabase()");
    }

    // ========================
    // Execute after Clear
    // ========================

    @Test
    @Order(60)
    @DisplayName("executeMigration() reapplique le schema apres clear")
    void testExecuteAfterClear() {
        assertDoesNotThrow(() -> manager.executeMigration("v1_initial"));
        List<DatabaseInspector.TableInfo> tables = manager.listTables();
        assertFalse(tables.isEmpty(), "Tables should exist after executing migration");
        assertTrue(tables.stream().anyMatch(t -> t.name().equals("test_products")),
                "test_products table should be recreated");
    }
}
