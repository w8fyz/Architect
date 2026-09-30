package sh.fyz.architect.migration;

import org.hibernate.Session;
import sh.fyz.architect.Architect;
import sh.fyz.architect.persistent.EnumCheckConstraintSynchronizer;
import sh.fyz.architect.persistent.SessionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Versioned migrations on top of {@link MigrationManager}.
 *
 * <p>{@code MigrationManager} generates and executes SQL, but it has no notion of
 * <em>which</em> scripts a given database has already seen: {@code listMigrations()}
 * lists files, nothing records what was applied. This class adds that missing half —
 * a history table, ordering, pending detection and checksum verification — so a
 * schema can be rolled forward deliberately instead of being mutated at boot by
 * {@code hbm2ddl=update}.</p>
 *
 * <h2>What this is not</h2>
 * <p>No diffing happens here. {@link MigrationManager#createMigration(String)} emits a
 * full {@code CREATE TABLE} snapshot of the current entity model, which is only
 * applicable to an empty database — useful as a baseline, not as an incremental
 * step. Incremental migrations are computed by {@link SchemaDiff} (the CLI's
 * {@code diff} command) or written by hand.</p>
 *
 * <h2>Atomicity</h2>
 * <p>Each migration's statements and its history row are written in one
 * transaction, so a failure cannot leave a script half-applied but recorded, nor
 * applied but unrecorded. That guarantee is only as strong as the database's
 * transactional DDL: it holds on PostgreSQL, and does not on MySQL, MariaDB or
 * H2, where DDL commits implicitly.</p>
 */
public class MigrationRunner {

    private static final Logger LOG = Logger.getLogger(MigrationRunner.class.getName());

    /**
     * Default bookkeeping table. Created on first use; never dropped by this class.
     *
     * <p>Also the shared naming prefix: several migration streams can coexist on one
     * database (one application owning some tables, another application owning others)
     * as long as each stream keeps its own history in a table named
     * {@code architect_schema_history_<stream>}. {@link SchemaDiff} treats every table
     * carrying this prefix as tool bookkeeping and keeps it out of any model
     * comparison. Custom history tables must therefore start with this prefix —
     * {@link #MigrationRunner(Architect, Path, String)} enforces it.</p>
     */
    public static final String HISTORY_TABLE = "architect_schema_history";

    private final Architect architect;
    private final MigrationManager manager;
    private final Path migrationDirectory;
    private final String dialect;
    private final String historyTable;

    public MigrationRunner(Architect architect, Path migrationDirectory) {
        this(architect, migrationDirectory, HISTORY_TABLE);
    }

    /**
     * A runner whose history lives in {@code historyTable} instead of the default —
     * for a database hosting several independent migration streams. The name must
     * start with {@link #HISTORY_TABLE} (see there) and stay a plain identifier;
     * it is concatenated into DDL/DML.
     */
    public MigrationRunner(Architect architect, Path migrationDirectory, String historyTable) {
        if (historyTable == null || !historyTable.startsWith(HISTORY_TABLE)
                || !historyTable.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("History table must match '" + HISTORY_TABLE
                    + "[_a-zA-Z0-9]*', got: " + historyTable);
        }
        this.architect = architect;
        this.manager = new MigrationManager(architect, migrationDirectory);
        this.migrationDirectory = migrationDirectory;
        this.dialect = architect.getDatabaseCredentials().getSQLAuthProvider().getDialect();
        this.historyTable = historyTable;
    }

    /** The history table this runner reads and writes. */
    public String historyTable() {
        return historyTable;
    }

    /** A migration file on disk that parses as a versioned migration. */
    public record Available(MigrationVersion version, String filename, String checksum) {}

    /** A row from the history table. */
    public record Applied(String version, String description, String filename,
                          String checksum, long appliedAt, long executionMs) {}

    /**
     * A discrepancy between disk and history. Reported rather than thrown so a
     * caller can show every problem at once instead of the first.
     */
    public record Problem(String version, String detail) {}

    // ── Discovery ────────────────────────────────────────────────────────────

    /**
     * Every parseable migration file, ordered by version. Files that do not match
     * {@code V<version>__<description>.sql} are skipped — see {@link #ignored()}.
     */
    public List<Available> available() {
        List<Available> out = new ArrayList<>();
        for (String filename : manager.listMigrations()) {
            MigrationVersion version = MigrationVersion.parse(filename);
            if (version == null) {
                continue;
            }
            out.add(new Available(version, filename, checksum(read(filename))));
        }
        out.sort(Comparator.comparing(Available::version));
        return out;
    }

    /** Files in the directory that do not follow the naming convention. */
    public List<String> ignored() {
        List<String> out = new ArrayList<>();
        for (String filename : manager.listMigrations()) {
            if (MigrationVersion.parse(filename) == null) {
                out.add(filename);
            }
        }
        return out;
    }

    /** History rows, oldest first. Creates the history table if it is absent. */
    public List<Applied> applied() {
        ensureHistoryTable();
        List<Applied> out = new ArrayList<>();
        withConnection(connection -> {
            String sql = "SELECT version, description, filename, checksum, applied_at, execution_ms"
                    + " FROM " + historyTable;
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Applied(
                            rs.getString("version"),
                            rs.getString("description"),
                            rs.getString("filename"),
                            rs.getString("checksum"),
                            rs.getLong("applied_at"),
                            rs.getLong("execution_ms")
                    ));
                }
            }
        });
        out.sort(Comparator.comparing(a -> MigrationVersion.parse(a.filename()) == null
                ? new MigrationVersion(a.version(), List.of(), "")
                : MigrationVersion.parse(a.filename())));
        return out;
    }

    /** Available migrations with no history row, in the order they would run. */
    public List<Available> pending() {
        Map<String, Applied> byVersion = appliedByVersion();
        List<Available> out = new ArrayList<>();
        for (Available a : available()) {
            if (!byVersion.containsKey(a.version().raw())) {
                out.add(a);
            }
        }
        return out;
    }

    // ── Verification ─────────────────────────────────────────────────────────

    /**
     * Checks history against disk. Reports, in this order:
     * <ul>
     *   <li>an applied migration whose file is gone — the schema's provenance is
     *       no longer reconstructable;</li>
     *   <li>an applied migration whose file changed since — it will not be re-run,
     *       so the database and the repository have silently diverged;</li>
     *   <li>a pending migration that sorts <em>before</em> something already
     *       applied — it would be skipped on a database that is ahead but applied
     *       on one that is behind, so two environments would end up different.</li>
     * </ul>
     *
     * @return an empty list when disk and history agree
     */
    public List<Problem> verify() {
        List<Problem> problems = new ArrayList<>();
        Map<String, Available> onDisk = new LinkedHashMap<>();
        // History is keyed by version: of two files sharing one (V7__a / V7__b, or V1 / V01),
        // whichever is applied first hides the other for good, or both run on a fresh database.
        // Groups already fully applied (both rows recorded) are history, not a hazard: renaming
        // either file now would only trade this report for a missing-file one.
        List<Applied> history = applied();
        Set<String> appliedVersions = new HashSet<>();
        for (Applied applied : history) {
            appliedVersions.add(applied.version());
        }
        Map<List<Long>, Available> byNumericVersion = new LinkedHashMap<>();
        for (Available a : available()) {
            onDisk.put(a.version().raw(), a);
            Available first = byNumericVersion.putIfAbsent(numericKey(a.version()), a);
            if (first != null && !(appliedVersions.contains(first.version().raw())
                    && appliedVersions.contains(a.version().raw()))) {
                problems.add(new Problem(a.version().raw(),
                        "duplicate version: " + first.filename() + " and " + a.filename()
                                + " have the same version; renumber one of them"));
            }
        }

        MigrationVersion highestApplied = null;
        for (Applied applied : history) {
            Available disk = onDisk.get(applied.version());
            if (disk == null) {
                problems.add(new Problem(applied.version(),
                        "applied on " + applied.filename() + " but that file is missing from "
                                + migrationDirectory));
            } else {
                if (!disk.checksum().equals(applied.checksum())) {
                    problems.add(new Problem(applied.version(),
                            "checksum mismatch: " + disk.filename()
                                    + " changed after it was applied and will not be re-run"));
                }
                MigrationVersion v = disk.version();
                if (highestApplied == null || v.compareTo(highestApplied) > 0) {
                    highestApplied = v;
                }
            }
        }

        if (highestApplied != null) {
            for (Available p : pending()) {
                if (p.version().compareTo(highestApplied) < 0) {
                    problems.add(new Problem(p.version().raw(),
                            "out of order: " + p.filename() + " sorts before the applied "
                                    + highestApplied + " and would be skipped on databases already ahead"));
                }
            }
        }
        return problems;
    }

    /**
     * Enum CHECK constraint statements for the current entity model.
     *
     * <p>Only meaningful on PostgreSQL, where an enum-mapped column carries a CHECK
     * constraint listing its permitted values. {@code EnumCheckConstraintSynchronizer}
     * repairs those automatically, but <em>only</em> when {@code hbm2ddl=update} —
     * so a deployment that has moved to {@code validate} loses that repair, and a
     * newly added Java enum constant makes every INSERT carrying it fail. Folding
     * this output into a migration is what replaces it.</p>
     *
     * @return an empty list on non-PostgreSQL dialects
     */
    public List<String> enumConstraintStatements() {
        return EnumCheckConstraintSynchronizer.generateEnumConstraintsDDL(
                SessionManager.get().getRegisteredEntityClasses(), dialect);
    }

    // ── Execution ────────────────────────────────────────────────────────────

    /**
     * Applies every pending migration in version order, recording each as it goes.
     * Stops at the first failure; migrations already applied in this run stay
     * applied, since each is its own transaction.
     *
     * @param dryRun when true, reports what would run without applying it (the history
     *               table is still created if absent)
     * @return the migrations applied (or that would be, when {@code dryRun})
     */
    public List<Available> apply(boolean dryRun) {
        ensureHistoryTable();
        List<Available> pending = pending();
        if (pending.isEmpty()) {
            return List.of();
        }
        if (dryRun) {
            return pending;
        }

        List<Available> done = new ArrayList<>();
        for (Available migration : pending) {
            long startedAt = System.currentTimeMillis();
            String sql = read(migration.filename());
            List<String> statements = MigrationManager.parseSqlStatements(sql);

            withTransaction(connection -> {
                try (Statement stmt = connection.createStatement()) {
                    for (String s : statements) {
                        stmt.execute(s);
                    }
                }
                record(connection, migration, startedAt);
            });

            LOG.info("Applied migration " + migration.filename()
                    + " in " + (System.currentTimeMillis() - startedAt) + "ms");
            done.add(migration);
        }
        return done;
    }

    /**
     * Writes a baseline snapshot of the current entity model and records it as
     * already applied, without executing it.
     *
     * <p>For adopting an existing database whose schema Hibernate already built:
     * the tables are there, so running the snapshot would fail on the first
     * {@code CREATE TABLE}. This marks that starting point so later migrations
     * have something to follow.</p>
     *
     * @param version version to file it under, e.g. {@code "1"}
     */
    public Available baseline(String version) {
        ensureHistoryTable();
        if (!applied().isEmpty()) {
            throw new IllegalStateException(
                    "Cannot baseline: " + historyTable + " already has rows. "
                            + "Baselining is only for a database that has never been migrated.");
        }
        String name = "V" + version + "__baseline";
        Path file = manager.createMigration(name);
        String filename = file.getFileName().toString();

        MigrationVersion parsed = MigrationVersion.parse(filename);
        if (parsed == null) {
            throw new IllegalStateException("Generated baseline filename is not parseable: " + filename);
        }
        Available baseline = new Available(parsed, filename, checksum(read(filename)));

        long now = System.currentTimeMillis();
        withTransaction(connection -> record(connection, baseline, now));
        LOG.info("Baseline recorded (not executed): " + filename);
        return baseline;
    }

    // ── History table ────────────────────────────────────────────────────────

    /**
     * Creates the history table when absent. {@code CREATE TABLE IF NOT EXISTS} is
     * understood by every dialect Architect supports (PostgreSQL, MySQL, MariaDB,
     * H2, SQLite), and the column types are deliberately the portable ones —
     * timings are epoch millis in a {@code BIGINT} rather than a timestamp, whose
     * spelling differs per dialect.
     */
    public void ensureHistoryTable() {
        withTransaction(connection -> {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS " + historyTable + " ("
                        + "version VARCHAR(128) NOT NULL, "
                        + "description VARCHAR(512), "
                        + "filename VARCHAR(512) NOT NULL, "
                        + "checksum VARCHAR(64) NOT NULL, "
                        + "applied_at BIGINT NOT NULL, "
                        + "execution_ms BIGINT NOT NULL, "
                        + "PRIMARY KEY (version))");
            }
        });
    }

    private void record(Connection connection, Available migration, long startedAt) throws SQLException {
        String sql = "INSERT INTO " + historyTable
                + " (version, description, filename, checksum, applied_at, execution_ms)"
                + " VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, migration.version().raw());
            ps.setString(2, migration.version().description());
            ps.setString(3, migration.filename());
            ps.setString(4, migration.checksum());
            ps.setLong(5, startedAt);
            ps.setLong(6, System.currentTimeMillis() - startedAt);
            ps.executeUpdate();
        }
    }

    private Map<String, Applied> appliedByVersion() {
        Map<String, Applied> out = new LinkedHashMap<>();
        for (Applied a : applied()) {
            out.put(a.version(), a);
        }
        return out;
    }

    /** Version segments without trailing zeros, so that V1, V01 and V1.0 compare equal. */
    private static List<Long> numericKey(MigrationVersion version) {
        List<Long> segments = new ArrayList<>(version.segments());
        while (segments.size() > 1 && segments.get(segments.size() - 1) == 0L) {
            segments.remove(segments.size() - 1);
        }
        return segments;
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private interface Work {
        void run(Connection connection) throws SQLException;
    }

    /** Read-only work on a borrowed connection, leaving commit handling alone. */
    private void withConnection(Work work) {
        try (Session session = SessionManager.get().getSession()) {
            session.doWork(work::run);
        }
    }

    /** Work committed as one unit, rolled back on any failure. */
    private void withTransaction(Work work) {
        try (Session session = SessionManager.get().getSession()) {
            session.doWork(connection -> {
                boolean wasAutoCommit = connection.getAutoCommit();
                try {
                    connection.setAutoCommit(false);
                    work.run(connection);
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(wasAutoCommit);
                }
            });
        }
    }

    private String read(String filename) {
        try {
            return Files.readString(migrationDirectory.resolve(filename));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read migration " + filename, e);
        }
    }

    /**
     * SHA-256 of the script, with CRLF normalised to LF first so a checkout on
     * Windows does not read as a modified migration everywhere else.
     */
    static String checksum(String content) {
        String normalised = content.replace("\r\n", "\n");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalised.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The underlying manager, for generation and inspection. */
    public MigrationManager manager() {
        return manager;
    }

    public Path migrationDirectory() {
        return migrationDirectory;
    }
}
