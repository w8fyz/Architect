package sh.fyz.architect.migration;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.TargetType;
import org.hibernate.tool.schema.internal.ExceptionHandlerLoggedImpl;
import org.hibernate.tool.schema.spi.ContributableMatcher;
import org.hibernate.tool.schema.spi.ExceptionHandler;
import org.hibernate.tool.schema.spi.ExecutionOptions;
import org.hibernate.tool.schema.spi.SchemaManagementTool;
import org.hibernate.tool.schema.spi.SchemaMigrator;
import org.hibernate.tool.schema.spi.ScriptTargetOutput;
import org.hibernate.tool.schema.spi.TargetDescriptor;
import sh.fyz.architect.persistent.EnumCheckConstraintSynchronizer;
import sh.fyz.architect.persistent.sql.SQLAuthProvider;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Computes the SQL that brings a target database up to the current entity model.
 *
 * <p>Two passes, because no single mechanism covers both directions:</p>
 *
 * <ol>
 *   <li><b>Additive</b> — delegated to Hibernate's own {@link SchemaMigrator}, driven with a
 *       {@code SCRIPT}-only target so it writes the statements instead of executing them. This is
 *       the engine behind {@code hbm2ddl=update}: it reads the live schema, compares it to the
 *       mapping model, and emits the {@code CREATE TABLE} / {@code ALTER TABLE ADD COLUMN} /
 *       index / unique-key / foreign-key statements needed. Dialect-correct for free, and it
 *       converges — running it against a database that already matches yields nothing.</li>
 *   <li><b>Destructive</b> — written here, because {@code AbstractSchemaMigrator} has no drop
 *       logic at all. A column or table removed from the model leaves Hibernate silent, so
 *       nothing would ever clean it up. Detected by comparing JDBC metadata against the model.</li>
 * </ol>
 *
 * <p>Destructive statements are reported separately and never mixed into the applicable set:
 * dropping a column is unrecoverable, and a removal is indistinguishable from a rename (see
 * {@link Result#renameHints()}). The caller decides how to present them — {@link #toSql} comments
 * them out.</p>
 */
public class SchemaDiff {

    private final SQLAuthProvider target;
    private final String user;
    private final String password;
    private final Collection<Class<?>> entityClasses;

    public SchemaDiff(SQLAuthProvider target, String user, String password,
                      Collection<Class<?>> entityClasses) {
        this.target = target;
        this.user = user;
        this.password = password;
        this.entityClasses = entityClasses;
    }

    /** A table or column present in the database but absent from the model. */
    public record Removal(String table, String column, String sql) {
        /** Whole-table removal when {@code column} is null. */
        public boolean isTable() { return column == null; }
    }

    /** A column whose database type no longer matches the model's. */
    public record TypeChange(String table, String column, String from, String to, String sql) {}

    /** A removal and an addition in the same table that may really be one rename. */
    public record RenameHint(String table, String removed, String added) {}

    public record Result(List<String> additive,
                         List<Removal> removals,
                         List<TypeChange> typeChanges,
                         List<RenameHint> renameHints,
                         List<String> enumConstraints) {

        /** True when the model and the database already agree in every respect. */
        public boolean isEmpty() {
            return additive.isEmpty() && removals.isEmpty()
                    && typeChanges.isEmpty() && enumConstraints.isEmpty();
        }

        /** True when there is something the caller can apply without editing. */
        public boolean hasApplicableChanges() {
            return !additive.isEmpty() || !enumConstraints.isEmpty();
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public Result compute() {
        Map<String, Object> settings = settings();
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySettings(settings).build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            for (Class<?> entity : entityClasses) {
                sources.addAnnotatedClass(entity);
            }
            Metadata metadata = sources.buildMetadata();

            List<String> additive = additivePass(registry, settings, metadata);
            Map<String, Set<String>> model = modelTables(metadata);
            Map<String, Map<String, String>> live = liveTables();

            List<Removal> removals = new ArrayList<>();
            List<TypeChange> typeChanges = new ArrayList<>();
            destructivePass(model, live, metadata, removals, typeChanges);

            List<RenameHint> hints = renameHints(removals, additive);
            List<String> enums = enumConstraintDelta();

            return new Result(additive, removals, typeChanges, hints, enums);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /**
     * Renders a result as a migration script.
     *
     * <p>Destructive statements are emitted commented out, under a warning. That is deliberate:
     * the additive half is machine-verified (Hibernate converges on it), the destructive half is
     * a judgement call the machine cannot make — a dropped column and a renamed column look
     * identical from here.</p>
     */
    public static String toSql(Result result, String description, String generatedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ").append(description).append('\n');
        sb.append("-- Generated: ").append(generatedAt).append('\n');
        sb.append("-- Additive statements computed by Hibernate's SchemaMigrator against the\n");
        sb.append("-- reference database; destructive ones detected by comparing JDBC metadata.\n");
        sb.append('\n');

        if (result.isEmpty()) {
            sb.append("-- No differences: the model and the reference database already agree.\n");
            return sb.toString();
        }

        if (!result.additive().isEmpty()) {
            sb.append("-- ── Schema additions ──────────────────────────────────────\n");
            for (String s : result.additive()) {
                sb.append(terminate(s)).append('\n');
            }
            sb.append('\n');
        }

        if (!result.enumConstraints().isEmpty()) {
            sb.append("-- ── Enum CHECK constraints ────────────────────────────────\n");
            sb.append("-- Regenerated wholesale (drop + recreate), so re-applying is safe.\n");
            for (String s : result.enumConstraints()) {
                sb.append(terminate(s)).append('\n');
            }
            sb.append('\n');
        }

        if (!result.removals().isEmpty() || !result.typeChanges().isEmpty()) {
            sb.append("-- ══ DESTRUCTIVE — commented out on purpose ════════════════\n");
            sb.append("-- Read every line before uncommenting. Dropping a column destroys its\n");
            sb.append("-- data and cannot be undone by a later migration.\n");

            if (!result.renameHints().isEmpty()) {
                sb.append("--\n-- Possible renames (a drop and an add of the same type in one table\n");
                sb.append("-- are indistinguishable from a rename):\n");
                for (RenameHint hint : result.renameHints()) {
                    sb.append("--   ").append(hint.table()).append(": ")
                      .append(hint.removed()).append(" -> ").append(hint.added())
                      .append("  ? If so, replace both statements with a single ALTER ... RENAME COLUMN.\n");
                }
            }
            sb.append("--\n");

            for (Removal r : result.removals()) {
                sb.append("-- ").append(terminate(r.sql())).append('\n');
            }
            for (TypeChange t : result.typeChanges()) {
                sb.append("-- ").append(t.table()).append('.').append(t.column())
                  .append(" : ").append(t.from()).append(" -> ").append(t.to()).append('\n');
                sb.append("-- ").append(terminate(t.sql())).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ── Pass 1: additive, via Hibernate ──────────────────────────────────────

    private List<String> additivePass(StandardServiceRegistry registry,
                                      Map<String, Object> settings, Metadata metadata) {
        List<String> collected = new ArrayList<>();
        ScriptTargetOutput output = new ScriptTargetOutput() {
            public void prepare() {}
            public void accept(String command) { collected.add(command); }
            public void release() {}
        };
        TargetDescriptor descriptor = new TargetDescriptor() {
            public EnumSet<TargetType> getTargetTypes() { return EnumSet.of(TargetType.SCRIPT); }
            public ScriptTargetOutput getScriptTargetOutput() { return output; }
        };
        ExecutionOptions options = new ExecutionOptions() {
            public Map<String, Object> getConfigurationValues() { return settings; }
            public boolean shouldManageNamespaces() { return false; }
            public ExceptionHandler getExceptionHandler() { return ExceptionHandlerLoggedImpl.INSTANCE; }
        };

        SchemaManagementTool tool = registry.getService(SchemaManagementTool.class);
        SchemaMigrator migrator = tool.getSchemaMigrator(settings);
        migrator.doMigration(metadata, options, ContributableMatcher.ALL, descriptor);
        return collected;
    }

    // ── Pass 2: destructive, by comparison ───────────────────────────────────

    /** Model tables → lowercase column names. */
    private Map<String, Set<String>> modelTables(Metadata metadata) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
            for (Table table : namespace.getTables()) {
                if (!table.isPhysicalTable()) {
                    continue;
                }
                Set<String> columns = new LinkedHashSet<>();
                for (Column column : table.getColumns()) {
                    columns.add(column.getName().toLowerCase(Locale.ROOT));
                }
                out.put(table.getName().toLowerCase(Locale.ROOT), columns);
            }
        }
        return out;
    }

    /** Live tables → column name → SQL type name, read straight from JDBC metadata. */
    private Map<String, Map<String, String>> liveTables() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(target.getUrl(), user, password)) {
            DatabaseMetaData meta = connection.getMetaData();
            String schema = target.getDialect().toLowerCase(Locale.ROOT).contains("postgresql")
                    ? "public" : null;
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = meta.getTables(null, schema, null, new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            for (String table : tables) {
                Map<String, String> columns = new LinkedHashMap<>();
                try (ResultSet rs = meta.getColumns(null, schema, table, null)) {
                    while (rs.next()) {
                        columns.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                                rs.getString("TYPE_NAME"));
                    }
                }
                out.put(table.toLowerCase(Locale.ROOT), columns);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the reference database schema", e);
        }
        return out;
    }

    private void destructivePass(Map<String, Set<String>> model,
                                 Map<String, Map<String, String>> live,
                                 Metadata metadata,
                                 List<Removal> removals,
                                 List<TypeChange> typeChanges) {
        for (Map.Entry<String, Map<String, String>> entry : live.entrySet()) {
            String table = entry.getKey();

            // Migration history tables belong to the tool, not to any model. Prefix
            // match, not equality: a database hosting several migration streams has
            // one history table per stream (architect_schema_history_<stream>), and
            // none of them is ever part of any stream's entity model.
            if (table.toLowerCase(Locale.ROOT).startsWith(MigrationRunner.HISTORY_TABLE)) {
                continue;
            }

            Set<String> modelColumns = model.get(table);
            if (modelColumns == null) {
                removals.add(new Removal(table, null,
                        "DROP TABLE IF EXISTS " + quote(table) + " CASCADE"));
                continue;
            }
            for (String column : entry.getValue().keySet()) {
                if (!modelColumns.contains(column)) {
                    removals.add(new Removal(table, column,
                            "ALTER TABLE " + quote(table) + " DROP COLUMN " + quote(column)));
                }
            }
        }
        // Type changes are compared on the model side, where the dialect's own type name is
        // available; going the other way would mean mapping JDBC type names back to the model.
        detectTypeChanges(metadata, live, typeChanges);
    }

    private void detectTypeChanges(Metadata metadata,
                                   Map<String, Map<String, String>> live,
                                   List<TypeChange> typeChanges) {
        for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
            for (Table table : namespace.getTables()) {
                if (!table.isPhysicalTable()) {
                    continue;
                }
                String tableName = table.getName().toLowerCase(Locale.ROOT);
                Map<String, String> liveColumns = live.get(tableName);
                if (liveColumns == null) {
                    continue;
                }
                for (Column column : table.getColumns()) {
                    String columnName = column.getName().toLowerCase(Locale.ROOT);
                    String liveType = liveColumns.get(columnName);
                    if (liveType == null) {
                        continue;
                    }
                    String modelType;
                    try {
                        modelType = column.getSqlType(metadata);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (modelType == null || compatible(liveType, modelType)) {
                        continue;
                    }
                    typeChanges.add(new TypeChange(tableName, columnName, liveType, modelType,
                            "ALTER TABLE " + quote(tableName) + " ALTER COLUMN " + quote(columnName)
                                    + " TYPE " + modelType));
                }
            }
        }
    }

    /**
     * Whether a JDBC-reported type name and a model type describe the same thing.
     *
     * <p>Necessarily fuzzy: PostgreSQL reports {@code int8} where the dialect writes
     * {@code bigint}, {@code varchar} carries its length in the model but not in the report, and
     * {@code bool} / {@code boolean} differ only in spelling. The list below covers the pairs
     * Architect's dialects actually produce; anything unrecognised is reported rather than
     * hidden, which is why type changes are commented out rather than applied.</p>
     */
    private boolean compatible(String liveType, String modelType) {
        String live = normaliseType(liveType);
        String model = normaliseType(modelType);
        if (live.equals(model)) {
            return true;
        }
        Set<String> pair = Set.of(live, model);
        return ALIASES.stream().anyMatch(group -> group.containsAll(pair));
    }

    private static final List<Set<String>> ALIASES = List.of(
            Set.of("int8", "bigint", "bigserial"),
            Set.of("int4", "int", "integer", "serial"),
            Set.of("int2", "smallint"),
            Set.of("bool", "boolean", "bit"),
            // "float" appears in both float groups on purpose: Hibernate writes
            // double as float(53) and real as float(24); once the precision is
            // stripped both read "float", which must not be flagged against
            // either live type. A real float4<->float8 change still surfaces,
            // since JDBC reports those names, not "float".
            Set.of("float8", "double precision", "double", "float"),
            Set.of("float4", "real", "float"),
            Set.of("varchar", "character varying"),
            Set.of("bpchar", "char", "character"),
            Set.of("text", "clob"),
            Set.of("bytea", "blob", "varbinary"),
            Set.of("timestamp", "timestamptz", "timestamp with time zone",
                   "timestamp without time zone"),
            Set.of("numeric", "decimal")
    );

    /**
     * Lowercases, strips surrounding double quotes, drops the length/precision
     * suffix and collapses whitespace.
     *
     * <p>The quote stripping matters: with {@code GLOBALLY_QUOTED_IDENTIFIERS}
     * a {@code columnDefinition}-provided type reaches the model side as
     * {@code "text"} (quotes included), which must compare equal to the bare
     * {@code text} JDBC reports — otherwise every such column is flagged as a
     * type change on every diff.</p>
     */
    private static String normaliseType(String type) {
        String t = type.toLowerCase(Locale.ROOT).trim();
        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).trim();
        }
        // Remove length/precision but KEEP what follows: "varchar(255) array"
        // must keep its array suffix, "timestamp(6) with time zone" its zone.
        t = t.replaceAll("\\([^)]*\\)", " ").replaceAll("\\s+", " ").trim();
        // The three spellings of an array type: JDBC metadata reports the
        // internal "_varchar", DDL writes "varchar array" or "varchar[]".
        if (t.startsWith("_")) {
            t = t.substring(1) + " array";
        } else if (t.endsWith("[]")) {
            t = t.substring(0, t.length() - 2).trim() + " array";
        }
        return t;
    }

    // ── Rename hints ─────────────────────────────────────────────────────────

    /**
     * Pairs a dropped column with a column added to the same table. A rename is undecidable from
     * a schema comparison — every differ has this limitation — so this only annotates, never acts.
     */
    private List<RenameHint> renameHints(List<Removal> removals, List<String> additive) {
        List<RenameHint> hints = new ArrayList<>();
        for (Removal removal : removals) {
            if (removal.isTable()) {
                continue;
            }
            for (String statement : additive) {
                String lower = statement.toLowerCase(Locale.ROOT);
                if (!lower.contains("add column")) {
                    continue;
                }
                if (!mentionsTable(lower, removal.table())) {
                    continue;
                }
                String added = addedColumnName(statement);
                if (added != null && !added.equalsIgnoreCase(removal.column())) {
                    hints.add(new RenameHint(removal.table(), removal.column(), added));
                }
            }
        }
        return hints;
    }

    private boolean mentionsTable(String lowerStatement, String table) {
        return lowerStatement.contains("\"" + table + "\"") || lowerStatement.contains(" " + table + " ");
    }

    /** Pulls the column name out of {@code alter table X add column "name" type}. */
    private String addedColumnName(String statement) {
        String marker = "add column";
        int idx = statement.toLowerCase(Locale.ROOT).indexOf(marker);
        if (idx < 0) {
            return null;
        }
        String rest = statement.substring(idx + marker.length()).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            return end > 1 ? rest.substring(1, end) : null;
        }
        int space = rest.indexOf(' ');
        return space > 0 ? rest.substring(0, space) : null;
    }

    // ── Enum CHECK constraints ───────────────────────────────────────────────

    /** Only the constraints whose live definition differs from the model's. */
    private List<String> enumConstraintDelta() {
        try (Connection connection = DriverManager.getConnection(target.getUrl(), user, password)) {
            return EnumCheckConstraintSynchronizer.generateEnumConstraintsDelta(
                    entityClasses, target.getDialect(), connection);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read enum constraints from the reference database", e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> settings() {
        Map<String, Object> settings = new HashMap<>();
        settings.put(AvailableSettings.JAKARTA_JDBC_DRIVER, target.getDriver());
        settings.put(AvailableSettings.JAKARTA_JDBC_URL, target.getUrl());
        settings.put(AvailableSettings.JAKARTA_JDBC_USER, user);
        settings.put(AvailableSettings.JAKARTA_JDBC_PASSWORD, password);
        settings.put(AvailableSettings.DIALECT, target.getDialect());
        settings.put(AvailableSettings.GLOBALLY_QUOTED_IDENTIFIERS, "true");
        settings.put(AvailableSettings.HBM2DDL_AUTO, "none");
        return settings;
    }

    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    private static String terminate(String statement) {
        String s = statement.trim();
        return s.endsWith(";") ? s : s + ";";
    }
}
