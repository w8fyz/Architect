package sh.fyz.architect.migration;

import sh.fyz.architect.Architect;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Headless entrypoint for {@link MigrationRunner}, the counterpart to
 * {@link MigrationToolGUI} for machines without a display.
 *
 * <p>Architect has no {@code main} of its own — the host application owns
 * startup, since only it knows which entities to register. So this mirrors
 * {@code MigrationToolGUI.open(Architect, Path)}: the host boots Architect
 * however it normally does, then hands control here with the process arguments.
 * A typical dispatch:</p>
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *     if (args.length > 0 && args[0].equals("migrate")) {
 *         Architect architect = bootArchitectOnly();
 *         System.exit(MigrationCli.run(architect, Path.of("migrations"),
 *                                      Arrays.copyOfRange(args, 1, args.length)));
 *     }
 *     startServer();
 * }
 * }</pre>
 *
 * <p>Every command is read-only except {@code apply}, {@code create} and
 * {@code baseline}. Nothing here ever drops anything —
 * {@link MigrationManager#clearDatabase(String)} is deliberately not exposed.</p>
 */
public final class MigrationCli {

    /** Everything went as asked. */
    public static final int EXIT_OK = 0;
    /** The command failed. */
    public static final int EXIT_ERROR = 1;
    /** {@code verify} found discrepancies, or {@code apply} refused because of them. */
    public static final int EXIT_PROBLEMS = 2;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private MigrationCli() {}

    public static int run(Architect architect, Path migrationDirectory, String[] args) {
        return run(architect, migrationDirectory, args, System.out, System.err);
    }

    /** Streams are injectable so tests can assert on output without capturing System.out. */
    public static int run(Architect architect, Path migrationDirectory, String[] args,
                          PrintStream out, PrintStream err) {
        if (args == null || args.length == 0) {
            usage(out);
            return EXIT_OK;
        }

        List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
        boolean dryRun = rest.remove("--dry-run");
        boolean force = rest.remove("--force");
        boolean withEnums = rest.remove("--enums");

        try {
            MigrationRunner runner = new MigrationRunner(architect, migrationDirectory);
            return switch (args[0]) {
                case "status" -> status(runner, out);
                case "verify" -> verify(runner, out, err);
                case "apply" -> apply(runner, dryRun, force, out, err);
                case "create" -> create(runner, rest, withEnums, out, err);
                case "baseline" -> baseline(runner, rest, out);
                case "enums" -> enums(runner, out);
                case "snapshot" -> snapshot(runner, out);
                case "help", "-h", "--help" -> {
                    usage(out);
                    yield EXIT_OK;
                }
                default -> {
                    err.println("Unknown command: " + args[0]);
                    usage(err);
                    yield EXIT_ERROR;
                }
            };
        } catch (Exception e) {
            err.println("error: " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    private static int status(MigrationRunner runner, PrintStream out) {
        List<MigrationRunner.Applied> applied = runner.applied();
        List<MigrationRunner.Available> pending = runner.pending();
        List<String> ignored = runner.ignored();

        out.println("Migration directory: " + runner.migrationDirectory().toAbsolutePath());
        out.println();

        if (applied.isEmpty()) {
            out.println("Applied: none — this database has never been migrated.");
        } else {
            out.println("Applied (" + applied.size() + "):");
            for (MigrationRunner.Applied a : applied) {
                out.printf("  %-12s %-40s %s (%d ms)%n",
                        a.version(), a.description(), STAMP.format(Instant.ofEpochMilli(a.appliedAt())),
                        a.executionMs());
            }
        }
        out.println();

        if (pending.isEmpty()) {
            out.println("Pending: none — the schema is up to date.");
        } else {
            out.println("Pending (" + pending.size() + "):");
            for (MigrationRunner.Available p : pending) {
                out.printf("  %-12s %s%n", p.version(), p.filename());
            }
        }

        if (!ignored.isEmpty()) {
            out.println();
            out.println("Ignored (not named V<version>__<description>.sql):");
            for (String name : ignored) {
                out.println("  " + name);
            }
        }
        return EXIT_OK;
    }

    private static int verify(MigrationRunner runner, PrintStream out, PrintStream err) {
        List<MigrationRunner.Problem> problems = runner.verify();
        if (problems.isEmpty()) {
            out.println("OK — history and migration files agree.");
            return EXIT_OK;
        }
        err.println(problems.size() + " problem(s):");
        for (MigrationRunner.Problem p : problems) {
            err.println("  [" + p.version() + "] " + p.detail());
        }
        return EXIT_PROBLEMS;
    }

    private static int apply(MigrationRunner runner, boolean dryRun, boolean force,
                             PrintStream out, PrintStream err) {
        List<MigrationRunner.Problem> problems = runner.verify();
        if (!problems.isEmpty() && !force) {
            err.println("Refusing to apply — " + problems.size() + " problem(s) found:");
            for (MigrationRunner.Problem p : problems) {
                err.println("  [" + p.version() + "] " + p.detail());
            }
            err.println("Fix them, or re-run with --force if you understand the consequences.");
            return EXIT_PROBLEMS;
        }

        List<MigrationRunner.Available> ran = runner.apply(dryRun);
        if (ran.isEmpty()) {
            out.println("Nothing to apply — the schema is up to date.");
            return EXIT_OK;
        }
        out.println((dryRun ? "Would apply " : "Applied ") + ran.size() + " migration(s):");
        for (MigrationRunner.Available m : ran) {
            out.println("  " + m.filename());
        }
        return EXIT_OK;
    }

    private static int create(MigrationRunner runner, List<String> rest, boolean withEnums,
                              PrintStream out, PrintStream err) throws IOException {
        if (rest.isEmpty()) {
            err.println("usage: create <description> [--enums]");
            return EXIT_ERROR;
        }
        String description = String.join("_", rest).replaceAll("[^a-zA-Z0-9_\\-]", "_");
        long next = nextVersion(runner);
        String filename = "V" + next + "__" + description + ".sql";

        StringBuilder body = new StringBuilder();
        body.append("-- ").append(filename).append('\n');
        body.append("-- Created: ").append(STAMP.format(Instant.now())).append('\n');
        body.append("--\n");
        body.append("-- Hand-written migration. Architect generates full schema snapshots,\n");
        body.append("-- not diffs, so the change below is yours to write.\n");
        body.append("\n");

        if (withEnums) {
            List<String> statements = runner.enumConstraintStatements();
            if (statements.isEmpty()) {
                body.append("-- No enum CHECK constraints for this dialect.\n");
            } else {
                body.append("-- Enum CHECK constraints for the current entity model.\n");
                body.append("-- Regenerated wholesale: each statement drops and recreates its\n");
                body.append("-- constraint, so applying this is idempotent.\n");
                for (String s : statements) {
                    body.append(s).append(";\n");
                }
            }
        }

        Path file = runner.migrationDirectory().resolve(filename);
        Files.createDirectories(runner.migrationDirectory());
        Files.writeString(file, body.toString());
        out.println("Created " + file.toAbsolutePath());
        if (!withEnums) {
            out.println("Write your SQL into it, then run: apply");
        }
        return EXIT_OK;
    }

    private static int baseline(MigrationRunner runner, List<String> rest, PrintStream out) {
        String version = rest.isEmpty() ? "1" : rest.get(0);
        MigrationRunner.Available baseline = runner.baseline(version);
        out.println("Baseline written and recorded as applied: " + baseline.filename());
        out.println("The snapshot was NOT executed — it describes the schema as it already stands.");
        return EXIT_OK;
    }

    private static int enums(MigrationRunner runner, PrintStream out) {
        List<String> statements = runner.enumConstraintStatements();
        if (statements.isEmpty()) {
            out.println("-- No enum CHECK constraints for this dialect.");
            return EXIT_OK;
        }
        for (String s : statements) {
            out.println(s + ";");
        }
        return EXIT_OK;
    }

    private static int snapshot(MigrationRunner runner, PrintStream out) {
        out.println(runner.manager().generateSchema());
        return EXIT_OK;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** One past the highest leading version segment on disk. */
    private static long nextVersion(MigrationRunner runner) {
        long max = 0;
        for (MigrationRunner.Available a : runner.available()) {
            List<Long> segments = a.version().segments();
            if (!segments.isEmpty()) {
                max = Math.max(max, segments.get(0));
            }
        }
        return max + 1;
    }

    private static void usage(PrintStream out) {
        out.println("""
                Architect migrations

                  status              applied and pending migrations
                  verify              check history against the files on disk
                  apply [--dry-run]   run every pending migration, in order
                        [--force]     apply even when verify reports problems
                  create <desc>       new empty migration, numbered for you
                         [--enums]    pre-fill it with current enum CHECK constraints
                  baseline [version]  snapshot the current schema and record it as
                                      applied WITHOUT running it (adopting an existing DB)
                  enums               print enum CHECK constraints for the entity model
                  snapshot            print a full schema snapshot, write nothing

                Files are named V<version>__<description>.sql and applied in numeric
                version order. Migrations are hand-written: Architect generates full
                snapshots, never diffs between two entity models.

                Exit codes: 0 ok, 1 error, 2 verification problems.""");
    }
}
