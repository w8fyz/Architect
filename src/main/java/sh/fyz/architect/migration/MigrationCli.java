package sh.fyz.architect.migration;

import sh.fyz.architect.Architect;
import sh.fyz.architect.persistent.SessionManager;
import sh.fyz.architect.persistent.sql.SQLAuthProvider;

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
 * Headless entrypoint for {@link MigrationRunner} and {@link SchemaDiff}, the counterpart to
 * {@link MigrationToolGUI} for machines without a display.
 *
 * <p>Architect has no {@code main} of its own — the host application owns startup, since only it
 * knows which entities to register. So this mirrors {@code MigrationToolGUI.open(Architect, Path)}:
 * the host boots Architect however it normally does, then hands control here with the process
 * arguments. A typical dispatch:</p>
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
 * <p>Only {@code apply}, {@code diff} and {@code baseline} write anything. Nothing here ever drops
 * anything from the application's database — {@link MigrationManager#clearDatabase(String)} is
 * deliberately not exposed, and the only wipe is of a shadow database guarded by
 * {@link ShadowDatabase#assertNotMainDatabase}.</p>
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
        return run(architect, migrationDirectory, MigrationRunner.HISTORY_TABLE, args, out, err);
    }

    /**
     * Same, with the migration stream's history table spelled out — for a database
     * hosting several independent streams. See
     * {@link MigrationRunner#MigrationRunner(Architect, Path, String)} for the
     * naming constraint.
     */
    public static int run(Architect architect, Path migrationDirectory, String historyTable,
                          String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0) {
            usage(out);
            return EXIT_OK;
        }

        try {
            List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
            boolean dryRun = rest.remove("--dry-run");
            boolean force = rest.remove("--force");
            String shadowUrl = takeValue(rest, "--shadow-url");
            String targetUrl = takeValue(rest, "--target-url");
            String shadowUser = takeValue(rest, "--shadow-user");
            String shadowPassword = takeValue(rest, "--shadow-password");

            // A misspelt flag would otherwise be swallowed into a description silently.
            for (String arg : rest) {
                if (arg.startsWith("--")) {
                    err.println("Unknown option: " + arg);
                    return EXIT_ERROR;
                }
            }

            MigrationRunner runner = new MigrationRunner(architect, migrationDirectory, historyTable);
            return switch (args[0]) {
                case "status" -> status(runner, out);
                case "verify" -> verify(runner, out, err);
                case "apply" -> apply(runner, dryRun, force, out, err);
                case "baseline" -> baseline(runner, rest, out);
                case "enums" -> enums(runner, out);
                case "snapshot" -> snapshot(runner, out);
                case "diff" -> diff(architect, runner, rest, migrationDirectory,
                        shadowUrl, targetUrl, shadowUser, shadowPassword, dryRun, out, err);
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
            err.println("error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return EXIT_ERROR;
        }
    }

    // ── diff ─────────────────────────────────────────────────────────────────

    /**
     * Generates the migration bringing a reference database up to the entity model.
     *
     * <p>Two references, and the choice matters. A <b>shadow</b> database is wiped and rebuilt
     * from the committed migrations, so the diff is a pure function of the repository and
     * reproducible by anyone on the same commit — this is the normal mode. A <b>target</b> is an
     * existing database used as-is, for catching up a schema that has drifted; the drift then
     * shows up in the generated file, which is the point.</p>
     */
    private static int diff(Architect architect, MigrationRunner runner, List<String> rest,
                            Path migrationDirectory, String shadowUrl, String targetUrl,
                            String shadowUser, String shadowPassword,
                            boolean dryRun, PrintStream out, PrintStream err) throws IOException {
        if (rest.isEmpty()) {
            err.println("usage: diff <description> (--shadow-url <jdbc> | --target-url <jdbc>) [--dry-run]");
            return EXIT_ERROR;
        }
        if ((shadowUrl == null) == (targetUrl == null)) {
            err.println("diff needs exactly one of --shadow-url or --target-url.");
            err.println("  --shadow-url  a scratch database, WIPED and rebuilt from the migration");
            err.println("                files. Reproducible; this is the normal mode.");
            err.println("  --target-url  an existing database, used as-is and never written to.");
            return EXIT_ERROR;
        }

        var credentials = architect.getDatabaseCredentials();
        String user = shadowUser != null ? shadowUser : credentials.getUser();
        String password = shadowPassword != null ? shadowPassword : credentials.getPassword();
        String mainUrl = credentials.getSQLAuthProvider().getUrl();

        SQLAuthProvider reference;
        if (shadowUrl != null) {
            reference = providerFor(shadowUrl, credentials.getSQLAuthProvider());
            ShadowDatabase shadow = new ShadowDatabase(reference, user, password);
            List<String> replayed = shadow.prepare(mainUrl, migrationDirectory);
            out.println("Shadow rebuilt from " + replayed.size() + " migration(s).");
        } else {
            reference = providerFor(targetUrl, credentials.getSQLAuthProvider());
            out.println("Diffing against " + targetUrl + " (read-only).");
        }

        SchemaDiff schemaDiff = new SchemaDiff(reference, user, password,
                SessionManager.get().getRegisteredEntityClasses());
        SchemaDiff.Result result = schemaDiff.compute();

        String description = String.join("_", rest).replaceAll("[^a-zA-Z0-9_\\-]", "_");

        if (result.isEmpty()) {
            out.println("No differences — the entity model and the reference database agree.");
            return EXIT_OK;
        }

        summarise(result, out);

        long next = nextVersion(runner);
        String filename = "V" + next + "__" + description + ".sql";
        String sql = SchemaDiff.toSql(result, filename, STAMP.format(Instant.now()));

        if (dryRun) {
            out.println();
            out.println(sql);
            return EXIT_OK;
        }

        Files.createDirectories(migrationDirectory);
        Path file = migrationDirectory.resolve(filename);
        Files.writeString(file, sql);
        out.println();
        out.println("Written: " + file.toAbsolutePath());
        if (!result.removals().isEmpty() || !result.typeChanges().isEmpty()) {
            out.println("Read the DESTRUCTIVE section before applying — it is commented out.");
        }
        return EXIT_OK;
    }

    private static void summarise(SchemaDiff.Result result, PrintStream out) {
        out.println("  additions        : " + result.additive().size());
        out.println("  enum constraints : " + result.enumConstraints().size());
        out.println("  removals         : " + result.removals().size() + " (commented out)");
        out.println("  type changes     : " + result.typeChanges().size() + " (commented out)");
        if (!result.renameHints().isEmpty()) {
            out.println("  possible renames :");
            for (SchemaDiff.RenameHint hint : result.renameHints()) {
                out.println("      " + hint.table() + ": " + hint.removed() + " -> " + hint.added() + " ?");
            }
        }
    }

    /** Wraps a JDBC URL, borrowing driver and dialect from the application's own provider. */
    private static SQLAuthProvider providerFor(String url, SQLAuthProvider like) {
        return new SQLAuthProvider() {
            public String getDialect() { return like.getDialect(); }
            public String getDriver() { return like.getDriver(); }
            public String getUrl() { return url; }
        };
    }

    // ── Other commands ───────────────────────────────────────────────────────

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

    /** Pulls {@code --flag value} out of the argument list, returning the value. */
    private static String takeValue(List<String> args, String flag) {
        int i = args.indexOf(flag);
        if (i < 0) {
            return null;
        }
        if (i + 1 >= args.size()) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        args.remove(i);
        return args.remove(i);
    }

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

                  diff <desc>         generate the migration bringing a reference database up to
                                      the entity model, and write it as the next V<n>__<desc>.sql
                    --shadow-url <u>  scratch database, WIPED and rebuilt from the migration files.
                                      Reproducible from the repository alone — the normal mode.
                    --target-url <u>  an existing database, read as-is and never written to.
                    --shadow-user     credentials for the reference database, if they differ from
                    --shadow-password the application's own
                    --dry-run         print the migration instead of writing it

                  status              applied and pending migrations
                  verify              check history against the files on disk
                  apply [--dry-run]   run every pending migration, in order
                        [--force]     apply even when verify reports problems
                  baseline [version]  snapshot the current schema and record it as applied
                                      WITHOUT running it (adopting an existing database)
                  enums               print enum CHECK constraints for the entity model
                  snapshot            print a full schema snapshot, write nothing

                Files are named V<version>__<description>.sql and applied in numeric version
                order. Additions and enum constraints are generated ready to apply; drops and
                type changes are written commented out, because a removal is indistinguishable
                from a rename and dropping a column cannot be undone.

                Exit codes: 0 ok, 1 error, 2 verification problems.""");
    }
}
