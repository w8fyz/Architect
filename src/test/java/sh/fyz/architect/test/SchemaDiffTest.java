package sh.fyz.architect.test;

import org.junit.jupiter.api.*;
import sh.fyz.architect.diffmodel.DiffFixtures.*;
import sh.fyz.architect.migration.SchemaDiff;
import sh.fyz.architect.migration.ShadowDatabase;
import sh.fyz.architect.persistent.sql.SQLAuthProvider;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The diff engine. Needs a reachable PostgreSQL — same env knobs as {@link MigrationTest}.
 *
 * <p>Every case drives {@link SchemaDiff} directly against a scratch database, which is possible
 * because the engine takes its entity classes as a parameter and never touches the
 * {@code SessionManager} singleton.</p>
 */
@DisplayName("Schema Diff")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SchemaDiffTest {

    private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final int DB_PORT = Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5440"));
    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "architect_test");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "architect");
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "architect");

    private SQLAuthProvider provider;

    @BeforeAll
    void setUp() {
        provider = new PostgreSQLAuth(DB_HOST, DB_PORT, DB_NAME);
    }

    @BeforeEach
    void wipe() throws Exception {
        exec("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void exec(String... sqls) throws Exception {
        try (Connection c = DriverManager.getConnection(provider.getUrl(), DB_USER, DB_PASS);
             Statement st = c.createStatement()) {
            for (String s : sqls) st.execute(s);
        }
    }

    private SchemaDiff.Result diff(Class<?>... entities) {
        return new SchemaDiff(provider, DB_USER, DB_PASS, List.of(entities)).compute();
    }

    private void applyAdditive(SchemaDiff.Result result) throws Exception {
        if (!result.additive().isEmpty()) {
            exec(result.additive().toArray(new String[0]));
        }
        if (!result.enumConstraints().isEmpty()) {
            exec(result.enumConstraints().toArray(new String[0]));
        }
    }

    // ── Generation ───────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("base vide : la table est cree")
    void testCreatesTable() {
        SchemaDiff.Result result = diff(ItemV1.class);
        assertFalse(result.isEmpty());
        assertTrue(result.additive().stream().anyMatch(s -> s.toLowerCase().contains("create table")
                        && s.toLowerCase().contains("diff_item")),
                "expected a CREATE TABLE, got: " + result.additive());
    }

    @Test
    @Order(2)
    @DisplayName("convergence : re-diff apres application sort vide")
    void testConverges() throws Exception {
        applyAdditive(diff(ItemV1.class));
        SchemaDiff.Result second = diff(ItemV1.class);
        assertTrue(second.isEmpty(),
                "a diff engine that keeps emitting on an unchanged model is unusable; got: "
                        + second.additive() + " / " + second.removals());
    }

    @Test
    @Order(3)
    @DisplayName("colonnes ajoutees : ALTER TABLE ADD COLUMN")
    void testAddsColumns() throws Exception {
        applyAdditive(diff(ItemV1.class));

        SchemaDiff.Result result = diff(ItemV2.class);
        assertTrue(result.additive().stream().anyMatch(s -> s.toLowerCase().contains("price_cents")));
        assertTrue(result.additive().stream().anyMatch(s -> s.toLowerCase().contains("note")));
        assertTrue(result.removals().isEmpty(), "nothing was removed from the model");

        applyAdditive(result);
        assertTrue(diff(ItemV2.class).isEmpty(), "must converge after applying");
    }

    @Test
    @Order(4)
    @DisplayName("table ajoutee : CREATE TABLE, et convergence apres")
    void testAddsTable() throws Exception {
        applyAdditive(diff(ItemV1.class));

        SchemaDiff.Result result = diff(ItemV1.class, OtherTable.class);
        assertTrue(result.additive().stream().anyMatch(s -> s.toLowerCase().contains("diff_other")));

        applyAdditive(result);
        assertTrue(diff(ItemV1.class, OtherTable.class).isEmpty());
    }

    // ── Destructive ──────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("colonne retiree : signalee en removal, jamais en additif")
    void testDetectsRemovedColumn() throws Exception {
        applyAdditive(diff(ItemV2.class));

        SchemaDiff.Result result = diff(ItemV1.class);
        assertTrue(result.additive().isEmpty(), "Hibernate emits no drops — additive must stay empty");
        assertEquals(2, result.removals().size(), "price_cents and note both went: " + result.removals());
        assertTrue(result.removals().stream().allMatch(r -> r.sql().toLowerCase().contains("drop column")));
        assertFalse(result.isEmpty());
    }

    @Test
    @Order(11)
    @DisplayName("table retiree : DROP TABLE signale")
    void testDetectsRemovedTable() throws Exception {
        applyAdditive(diff(ItemV1.class, OtherTable.class));

        SchemaDiff.Result result = diff(ItemV1.class);
        assertTrue(result.removals().stream().anyMatch(r -> r.isTable()
                        && r.table().equalsIgnoreCase("diff_other")),
                "expected a table removal, got: " + result.removals());
    }

    @Test
    @Order(12)
    @DisplayName("le SQL rendu commente la section destructive")
    void testDestructiveIsCommented() throws Exception {
        applyAdditive(diff(ItemV2.class));

        SchemaDiff.Result result = diff(ItemV1.class);
        String sql = SchemaDiff.toSql(result, "V9__test.sql", "now");

        assertTrue(sql.contains("DESTRUCTIVE"));
        for (String line : sql.split("\n")) {
            if (line.toLowerCase().contains("drop column")) {
                assertTrue(line.trim().startsWith("--"),
                        "every drop must be commented out, found bare: " + line);
            }
        }
    }

    @Test
    @Order(13)
    @DisplayName("renommage : la paire suspecte est annotee")
    void testRenameHint() throws Exception {
        applyAdditive(diff(ItemV1.class));

        SchemaDiff.Result result = diff(ItemRenamed.class);
        assertTrue(result.renameHints().stream().anyMatch(h ->
                        h.removed().equalsIgnoreCase("label") && h.added().equalsIgnoreCase("title")),
                "expected label -> title flagged, got: " + result.renameHints());

        String sql = SchemaDiff.toSql(result, "V9__rename.sql", "now");
        assertTrue(sql.contains("Possible renames"));
    }

    @Test
    @Order(14)
    @DisplayName("columnDefinition et double ne produisent pas de faux type change")
    void testNoFalseTypeChanges() throws Exception {
        applyAdditive(diff(QuirkTypes.class));

        SchemaDiff.Result second = diff(QuirkTypes.class);
        assertTrue(second.typeChanges().isEmpty(),
                "a quoted columnDefinition type (\"text\") or a float(53) double must not "
                        + "be reported against the live text/float8: " + second.typeChanges());
        assertTrue(second.isEmpty(), "must converge: " + second.additive() + second.removals());
    }

    // ── Enums ────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("valeur d'enum ajoutee : la contrainte CHECK est regeneree")
    void testEnumValueAdded() throws Exception {
        applyAdditive(diff(EnumHolderV1.class));
        assertTrue(diff(EnumHolderV1.class).isEmpty(), "baseline must converge first");

        SchemaDiff.Result result = diff(EnumHolderV2.class);
        assertFalse(result.enumConstraints().isEmpty(),
                "adding ARCHIVED must regenerate the CHECK constraint");
        assertTrue(result.enumConstraints().stream().anyMatch(s -> s.contains("ARCHIVED")));
        assertTrue(result.enumConstraints().stream().anyMatch(s -> s.toLowerCase().contains("drop constraint")),
                "the ADD must be preceded by a DROP so re-applying is safe");

        applyAdditive(result);
        assertTrue(diff(EnumHolderV2.class).isEmpty(), "must converge after applying");
    }

    @Test
    @Order(21)
    @DisplayName("enum inchange : aucune contrainte emise")
    void testEnumUnchangedIsQuiet() throws Exception {
        applyAdditive(diff(EnumHolderV1.class));
        assertTrue(diff(EnumHolderV1.class).enumConstraints().isEmpty(),
                "an unchanged enum must not drag its constraint into every migration");
    }

    // ── Shadow guard ─────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("le shadow refuse de viser la base principale")
    void testShadowRefusesMainDatabase() {
        ShadowDatabase shadow = new ShadowDatabase(provider, DB_USER, DB_PASS);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> shadow.assertNotMainDatabase(provider.getUrl()));
        assertTrue(e.getMessage().contains("Refusing"));
    }

    @Test
    @Order(31)
    @DisplayName("le garde-fou ignore les parametres de connexion")
    void testShadowGuardNormalisesUrl() {
        ShadowDatabase shadow = new ShadowDatabase(provider, DB_USER, DB_PASS);
        // Same database, dressed up with a query string — must still be refused.
        assertThrows(IllegalArgumentException.class,
                () -> shadow.assertNotMainDatabase(provider.getUrl() + "?sslmode=require"));
    }

    @Test
    @Order(32)
    @DisplayName("un shadow distinct est accepte")
    void testShadowAcceptsDifferentDatabase() {
        SQLAuthProvider other = new PostgreSQLAuth(DB_HOST, DB_PORT, DB_NAME + "_shadow");
        ShadowDatabase shadow = new ShadowDatabase(other, DB_USER, DB_PASS);
        assertDoesNotThrow(() -> shadow.assertNotMainDatabase(provider.getUrl()));
    }

    @Test
    @Order(33)
    @DisplayName("le garde-fou voit a travers un port par defaut omis")
    void testShadowGuardDefaultPort() {
        // jdbc:postgresql://host/db and jdbc:postgresql://host:5432/db are the same database.
        SQLAuthProvider portless = new SQLAuthProvider() {
            public String getDialect() { return "org.hibernate.dialect.PostgreSQLDialect"; }
            public String getDriver() { return "org.postgresql.Driver"; }
            public String getUrl() { return "jdbc:postgresql://prod-host/app"; }
        };
        ShadowDatabase shadow = new ShadowDatabase(portless, DB_USER, DB_PASS);
        assertThrows(IllegalArgumentException.class,
                () -> shadow.assertNotMainDatabase("jdbc:postgresql://prod-host:5432/app"));
        assertDoesNotThrow(
                () -> shadow.assertNotMainDatabase("jdbc:postgresql://prod-host:5433/app"));
    }
}
