package sh.fyz.architect.test;

import org.junit.jupiter.api.*;
import sh.fyz.architect.Architect;
import sh.fyz.architect.migration.MigrationCli;
import sh.fyz.architect.migration.MigrationRunner;
import sh.fyz.architect.migration.MigrationVersion;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Versioned migration layer. Needs a reachable PostgreSQL — same env knobs as
 * {@link MigrationTest}.
 */
@DisplayName("Migration Runner")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MigrationRunnerTest {

    private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final int DB_PORT = Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5440"));
    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "architect_test");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "architect");
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "architect");

    private Architect architect;
    private MigrationRunner runner;
    private Path migrationDir;

    @BeforeAll
    void setUp() throws IOException {
        migrationDir = Files.createTempDirectory("architect-runner-test-");

        architect = new Architect()
                .setReceiver(true)
                .setDatabaseCredentials(new DatabaseCredentials(
                        new PostgreSQLAuth(DB_HOST, DB_PORT, DB_NAME),
                        DB_USER, DB_PASS, 2, "update"
                ));
        architect.addEntityClass(Product.class);
        architect.start();

        runner = new MigrationRunner(architect, migrationDir);
        // Start from a known state: these tables survive between runs otherwise.
        runner.manager().executeSql("DROP TABLE IF EXISTS " + MigrationRunner.HISTORY_TABLE);
        runner.manager().executeSql("DROP TABLE IF EXISTS runner_widget");
    }

    @AfterAll
    void tearDown() throws IOException {
        if (runner != null) {
            try {
                runner.manager().executeSql("DROP TABLE IF EXISTS " + MigrationRunner.HISTORY_TABLE);
            } catch (Exception ignored) {
                // Best effort — a failed test may have left no table at all.
            }
        }
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

    // ── Version parsing ──────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("parse() accepte V<version>__<description>.sql")
    void testParse() {
        MigrationVersion v = MigrationVersion.parse("V1__initial_schema.sql");
        assertNotNull(v);
        assertEquals("1", v.raw());
        assertEquals("initial_schema", v.description());
        assertEquals("V1__initial_schema.sql", v.filename());
    }

    @Test
    @Order(2)
    @DisplayName("parse() rejette ce qui ne suit pas la convention")
    void testParseRejects() {
        assertNull(MigrationVersion.parse("initial.sql"));
        assertNull(MigrationVersion.parse("V1_missing_double_underscore.sql"));
        assertNull(MigrationVersion.parse("Vx__not_numeric.sql"));
        assertNull(MigrationVersion.parse(null));
    }

    @Test
    @Order(3)
    @DisplayName("l'ordre est numerique, pas lexicographique")
    void testOrderingIsNumeric() {
        MigrationVersion v2 = MigrationVersion.parse("V2__b.sql");
        MigrationVersion v10 = MigrationVersion.parse("V10__a.sql");
        assertNotNull(v2);
        assertNotNull(v10);
        // The bug a plain string sort would introduce: "V10" < "V2".
        assertTrue(v2.compareTo(v10) < 0, "V2 must sort before V10");
        assertTrue("V10__a.sql".compareTo("V2__b.sql") < 0, "string sort disagrees, as expected");
    }

    @Test
    @Order(4)
    @DisplayName("un prefixe commun classe le plus court en premier")
    void testOrderingPrefix() {
        MigrationVersion v1 = MigrationVersion.parse("V1__a.sql");
        MigrationVersion v1_1 = MigrationVersion.parse("V1.1__b.sql");
        assertNotNull(v1);
        assertNotNull(v1_1);
        assertTrue(v1.compareTo(v1_1) < 0);
    }

    // ── Discovery and history ────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("une base neuve n'a rien d'applique")
    void testEmptyHistory() {
        assertTrue(runner.applied().isEmpty());
        assertTrue(runner.pending().isEmpty());
        assertTrue(runner.verify().isEmpty());
    }

    @Test
    @Order(11)
    @DisplayName("les fichiers hors convention sont ignores, pas fatals")
    void testIgnoredFiles() throws IOException {
        Files.writeString(migrationDir.resolve("notes.sql"), "-- scratch\n");
        assertTrue(runner.ignored().contains("notes.sql"));
        assertTrue(runner.available().isEmpty(), "a non-conforming file is not a migration");
        Files.delete(migrationDir.resolve("notes.sql"));
    }

    @Test
    @Order(12)
    @DisplayName("un fichier ajoute devient pending")
    void testPendingDetected() throws IOException {
        Files.writeString(migrationDir.resolve("V1__create_widget.sql"),
                "CREATE TABLE runner_widget (id INTEGER PRIMARY KEY, label VARCHAR(64));");
        List<MigrationRunner.Available> pending = runner.pending();
        assertEquals(1, pending.size());
        assertEquals("V1__create_widget.sql", pending.get(0).filename());
    }

    // ── Applying ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("--dry-run ne touche a rien")
    void testDryRun() {
        List<MigrationRunner.Available> would = runner.apply(true);
        assertEquals(1, would.size());
        assertTrue(runner.applied().isEmpty(), "dry run must not record anything");
    }

    @Test
    @Order(21)
    @DisplayName("apply() execute et enregistre")
    void testApply() {
        List<MigrationRunner.Available> ran = runner.apply(false);
        assertEquals(1, ran.size());

        List<MigrationRunner.Applied> applied = runner.applied();
        assertEquals(1, applied.size());
        assertEquals("1", applied.get(0).version());
        assertEquals("create_widget", applied.get(0).description());
        assertTrue(runner.pending().isEmpty());

        // The table really exists now.
        assertTrue(runner.manager().listTables().stream()
                .anyMatch(t -> t.name().equalsIgnoreCase("runner_widget")));
    }

    @Test
    @Order(22)
    @DisplayName("apply() est idempotent — rien a refaire")
    void testApplyTwice() {
        assertTrue(runner.apply(false).isEmpty(), "nothing should be pending on a second run");
    }

    @Test
    @Order(23)
    @DisplayName("l'ordre d'execution suit la version, pas le nom de fichier")
    void testAppliesInVersionOrder() throws IOException {
        Files.writeString(migrationDir.resolve("V10__add_late.sql"),
                "ALTER TABLE runner_widget ADD COLUMN late_col INTEGER;");
        Files.writeString(migrationDir.resolve("V2__add_early.sql"),
                "ALTER TABLE runner_widget ADD COLUMN early_col INTEGER;");

        List<MigrationRunner.Available> ran = runner.apply(false);
        assertEquals(2, ran.size());
        assertEquals("V2__add_early.sql", ran.get(0).filename(), "V2 must run before V10");
        assertEquals("V10__add_late.sql", ran.get(1).filename());
    }

    @Test
    @Order(24)
    @DisplayName("un echec annule la migration entiere")
    void testFailureRollsBack() throws IOException {
        // Second statement is invalid, so the first must not survive either.
        Files.writeString(migrationDir.resolve("V11__broken.sql"),
                "ALTER TABLE runner_widget ADD COLUMN good_col INTEGER;\n"
                        + "THIS IS NOT SQL;");

        assertThrows(Exception.class, () -> runner.apply(false));

        assertTrue(runner.applied().stream().noneMatch(a -> a.version().equals("11")),
                "a failed migration must not be recorded");
        assertFalse(runner.manager().getTableSchema("runner_widget").columns().stream()
                        .anyMatch(c -> c.name().equalsIgnoreCase("good_col")),
                "the successful statement must have rolled back with the failing one");

        Files.delete(migrationDir.resolve("V11__broken.sql"));
    }

    // ── Verification ─────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("modifier un fichier deja applique est signale")
    void testChecksumDrift() throws IOException {
        Path applied = migrationDir.resolve("V1__create_widget.sql");
        String original = Files.readString(applied);
        Files.writeString(applied, original + "\n-- edited after the fact\n");

        List<MigrationRunner.Problem> problems = runner.verify();
        assertTrue(problems.stream().anyMatch(p -> p.version().equals("1")
                        && p.detail().contains("checksum")),
                "an edited applied migration must be reported");

        Files.writeString(applied, original);
        assertTrue(runner.verify().isEmpty(), "restoring the file clears the problem");
    }

    @Test
    @Order(31)
    @DisplayName("supprimer un fichier deja applique est signale")
    void testMissingFile() throws IOException {
        Path applied = migrationDir.resolve("V2__add_early.sql");
        String original = Files.readString(applied);
        Files.delete(applied);

        assertTrue(runner.verify().stream().anyMatch(p -> p.detail().contains("missing")),
                "an applied migration whose file vanished must be reported");

        Files.writeString(applied, original);
        assertTrue(runner.verify().isEmpty());
    }

    @Test
    @Order(32)
    @DisplayName("une migration inseree avant la derniere appliquee est signalee")
    void testOutOfOrder() throws IOException {
        Files.writeString(migrationDir.resolve("V3__sneaked_in.sql"),
                "ALTER TABLE runner_widget ADD COLUMN sneaky INTEGER;");

        assertTrue(runner.verify().stream().anyMatch(p -> p.detail().contains("out of order")),
                "V3 arriving after V10 was applied must be reported");

        Files.delete(migrationDir.resolve("V3__sneaked_in.sql"));
    }

    // ── CLI ──────────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("apply refuse de tourner quand verify signale un probleme")
    void testCliApplyRefusesOnProblems() throws IOException {
        Path applied = migrationDir.resolve("V1__create_widget.sql");
        String original = Files.readString(applied);
        Files.writeString(applied, original + "\n-- tampered\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = MigrationCli.run(architect, migrationDir, new String[]{"apply"},
                new PrintStream(out), new PrintStream(err));

        assertEquals(MigrationCli.EXIT_PROBLEMS, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Refusing to apply"));

        Files.writeString(applied, original);
    }

    @Test
    @Order(41)
    @DisplayName("verify sort 0 quand tout concorde")
    void testCliVerifyClean() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = MigrationCli.run(architect, migrationDir, new String[]{"verify"},
                new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(MigrationCli.EXIT_OK, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("OK"));
    }

    @Test
    @Order(42)
    @DisplayName("status liste applique et pending")
    void testCliStatus() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = MigrationCli.run(architect, migrationDir, new String[]{"status"},
                new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(MigrationCli.EXIT_OK, code);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Applied"));
        assertTrue(text.contains("Pending"));
    }

    @Test
    @Order(44)
    @DisplayName("les contraintes enum sont generables pour une migration")
    void testEnumConstraintStatements() {
        // Product has no enum column, so this is about the call being wired and
        // dialect-safe rather than about the statement count.
        assertNotNull(runner.enumConstraintStatements());
    }

    @Test
    @Order(45)
    @DisplayName("un flag sans valeur sort en erreur propre, pas en exception")
    void testCliFlagMissingValue() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = MigrationCli.run(architect, migrationDir,
                new String[]{"diff", "add_col", "--shadow-url"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));

        assertEquals(MigrationCli.EXIT_ERROR, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--shadow-url"));
    }

    @Test
    @Order(46)
    @DisplayName("une option inconnue est refusee au lieu de finir dans la description")
    void testCliUnknownOption() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = MigrationCli.run(architect, migrationDir,
                new String[]{"diff", "add_col", "--shadow_url", "jdbc:postgresql://x/y"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));

        assertEquals(MigrationCli.EXIT_ERROR, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unknown option: --shadow_url"));
    }

    @Test
    @Order(47)
    @DisplayName("diff bout en bout : shadow reconstruit, migration suivante ecrite")
    void testCliDiffEndToEnd() throws Exception {
        // The shadow must be a separate database; create it through a plain connection
        // because CREATE DATABASE cannot run inside a transaction.
        String shadowDb = DB_NAME + "_shadow";
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME, DB_USER, DB_PASS);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + shadowDb);
            statement.execute("CREATE DATABASE " + shadowDb);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = MigrationCli.run(architect, migrationDir,
                new String[]{"diff", "sync_model", "--shadow-url",
                        "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + shadowDb},
                new PrintStream(out), new PrintStream(err));

        assertEquals(MigrationCli.EXIT_OK, code, err.toString(StandardCharsets.UTF_8));
        // Highest on disk is V10, so the generated file is V11.
        Path file = migrationDir.resolve("V11__sync_model.sql");
        assertTrue(Files.exists(file), "expected V11__sync_model.sql; output: "
                + out.toString(StandardCharsets.UTF_8));
        String sql = Files.readString(file);
        assertTrue(sql.toLowerCase().contains("test_products"),
                "the registered entity's table must be in the diff: " + sql);
    }

    @Test
    @Order(50)
    @DisplayName("un flux avec sa propre table d'historique est independant du flux par defaut")
    void testCustomHistoryTableIsAnIndependentStream() throws IOException {
        String streamTable = MigrationRunner.HISTORY_TABLE + "_stream_a";
        runner.manager().executeSql("DROP TABLE IF EXISTS " + streamTable);
        runner.manager().executeSql("DROP TABLE IF EXISTS stream_a_widget");
        Path streamDir = Files.createTempDirectory("architect-runner-stream-a-");
        Files.writeString(streamDir.resolve("V1__stream_a.sql"),
                "CREATE TABLE stream_a_widget (id INT PRIMARY KEY);");

        MigrationRunner stream = new MigrationRunner(architect, streamDir, streamTable);
        assertEquals(1, stream.apply(false).size());
        assertEquals(1, stream.applied().size());
        assertTrue(stream.pending().isEmpty());

        // The default-history runner never sees the other stream's rows.
        for (MigrationRunner.Applied a : runner.applied()) {
            assertNotEquals("V1__stream_a.sql", a.filename(),
                    "stream history leaked into the default history table");
        }

        runner.manager().executeSql("DROP TABLE IF EXISTS stream_a_widget");
        runner.manager().executeSql("DROP TABLE IF EXISTS " + streamTable);
    }

    @Test
    @Order(52)
    @DisplayName("deux fichiers de meme version sont signales par verify")
    void testDuplicateVersionsAreReported() throws IOException {
        Path dir = Files.createTempDirectory("architect-runner-dup-");
        MigrationRunner dup = new MigrationRunner(architect, dir, MigrationRunner.HISTORY_TABLE + "_dup");
        try {
            Files.writeString(dir.resolve("V1__first.sql"), "SELECT 1;");
            Files.writeString(dir.resolve("V01__second.sql"), "SELECT 2;");
            List<MigrationRunner.Problem> problems = dup.verify();
            assertTrue(problems.stream().anyMatch(p -> p.detail().contains("duplicate version")),
                    "V1 and V01 share a version: " + problems);
        } finally {
            runner.manager().executeSql("DROP TABLE IF EXISTS " + dup.historyTable());
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @Order(54)
    @DisplayName("meme version brute : le doublon reste signale une fois l'un des fichiers applique")
    void testSameRawVersionStaysReportedOnceApplied() throws IOException {
        Path dir = Files.createTempDirectory("architect-runner-dup-raw-");
        MigrationRunner dup = new MigrationRunner(architect, dir, MigrationRunner.HISTORY_TABLE + "_dup_raw");
        try {
            runner.manager().executeSql("DROP TABLE IF EXISTS " + dup.historyTable());
            Files.writeString(dir.resolve("V7__a.sql"), "SELECT 1;");
            assertEquals(1, dup.apply(false).size());
            Files.writeString(dir.resolve("V7__b.sql"), "SELECT 2;");

            List<MigrationRunner.Problem> problems = dup.verify();
            assertTrue(problems.stream().anyMatch(p -> p.detail().contains("duplicate version")),
                    "V7__b can never be recorded next to V7__a: " + problems);
            assertTrue(problems.stream().noneMatch(p -> p.detail().contains("checksum mismatch")),
                    "V7__a did not change: " + problems);
        } finally {
            runner.manager().executeSql("DROP TABLE IF EXISTS " + dup.historyTable());
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @Order(55)
    @DisplayName("versions equivalentes deja appliquees toutes les deux : pas de doublon signale")
    void testEquivalentVersionsBothAppliedAreNotReported() throws IOException {
        Path dir = Files.createTempDirectory("architect-runner-dup-applied-");
        MigrationRunner dup = new MigrationRunner(architect, dir, MigrationRunner.HISTORY_TABLE + "_dup_applied");
        try {
            runner.manager().executeSql("DROP TABLE IF EXISTS " + dup.historyTable());
            Files.writeString(dir.resolve("V1__first.sql"), "SELECT 1;");
            Files.writeString(dir.resolve("V01__second.sql"), "SELECT 2;");
            // apply() itself does not check for duplicates (the CLI runs verify first), as on
            // a database migrated before that check existed.
            assertEquals(2, dup.apply(false).size());

            List<MigrationRunner.Problem> problems = dup.verify();
            assertTrue(problems.stream().noneMatch(p -> p.detail().contains("duplicate version")),
                    "both files are recorded: " + problems);
        } finally {
            runner.manager().executeSql("DROP TABLE IF EXISTS " + dup.historyTable());
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @Order(56)
    @DisplayName("fichier applique renomme depuis, dans un groupe V1 / V01 : pas de doublon signale")
    void testRenamedAppliedFileIsNotADuplicate() throws IOException {
        Path dir = Files.createTempDirectory("architect-runner-dup-renamed-");
        MigrationRunner dup = new MigrationRunner(architect, dir, MigrationRunner.HISTORY_TABLE + "_dup_renamed");
        try {
            runner.manager().executeSql("DROP TABLE IF EXISTS " + dup.historyTable());
            Files.writeString(dir.resolve("V1__first.sql"), "SELECT 1;");
            Files.writeString(dir.resolve("V01__second.sql"), "SELECT 2;");
            assertEquals(2, dup.apply(false).size());
            Files.move(dir.resolve("V01__second.sql"), dir.resolve("V01__renamed.sql"));

            List<MigrationRunner.Problem> problems = dup.verify();
            assertTrue(problems.stream().noneMatch(p -> p.detail().contains("duplicate version")),
                    "both versions are recorded: " + problems);
        } finally {
            runner.manager().executeSql("DROP TABLE IF EXISTS " + dup.historyTable());
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @Order(53)
    @DisplayName("decoupage SQL : commentaire bloc entre deux mots, BOM en tete")
    void testSqlSplitterEdgeCases() {
        assertDoesNotThrow(() -> runner.manager().executeSql(
                "\uFEFFCREATE TABLE/*c*/runner_split (id INT); DROP TABLE runner_split;"));
    }

    @Test
    @Order(51)
    @DisplayName("le nom de table d'historique est contraint au prefixe de l'outil")
    void testHistoryTableNameIsValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> new MigrationRunner(architect, migrationDir, "my_history"));
        assertThrows(IllegalArgumentException.class,
                () -> new MigrationRunner(architect, migrationDir,
                        MigrationRunner.HISTORY_TABLE + "_x; DROP TABLE"));
    }

    @Test
    @Order(90)
    @DisplayName("baseline : ne reecrit jamais un fichier existant, refuse une version deja prise")
    void testBaselineNeverRewritesAFile() throws Exception {
        Path dir = Files.createTempDirectory("architect-baseline-test-");
        String history = MigrationRunner.HISTORY_TABLE + "_baseline_test";
        MigrationRunner r = new MigrationRunner(architect, dir, history);
        try {
            r.manager().executeSql("DROP TABLE IF EXISTS " + history);
            MigrationRunner.Available first = r.baseline("1");
            Path file = dir.resolve(first.filename());
            // Committed, then edited by hand: no longer what a fresh snapshot would produce.
            Files.writeString(file, Files.readString(file) + "-- reviewed\n");
            String committed = Files.readString(file);

            // A second database adopted at the same point.
            r.manager().executeSql("DROP TABLE " + history);
            r.baseline("1");
            assertEquals(committed, Files.readString(file), "the committed file must not be rewritten");
            assertTrue(r.verify().isEmpty(), "the recorded checksum must be the file's: " + r.verify());

            // A dotted version is written V1_5: the same version, found again.
            r.manager().executeSql("DROP TABLE " + history);
            Files.delete(file);
            MigrationRunner.Available dotted = r.baseline("1.5");
            r.manager().executeSql("DROP TABLE " + history);
            assertEquals(dotted.filename(), r.baseline("1.5").filename());

            Files.writeString(dir.resolve("V2__other.sql"), "SELECT 1;");
            r.manager().executeSql("DROP TABLE " + history);
            assertThrows(IllegalStateException.class, () -> r.baseline("2"));
            assertFalse(Files.exists(dir.resolve("V2__baseline.sql")));
        } finally {
            r.manager().executeSql("DROP TABLE IF EXISTS " + history);
            try (var files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }
}
