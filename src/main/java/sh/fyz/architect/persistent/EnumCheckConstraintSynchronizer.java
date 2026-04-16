package sh.fyz.architect.persistent;

import jakarta.persistence.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hibernate's hbm2ddl=update never updates existing CHECK constraints when Java enum values
 * change. On PostgreSQL this causes INSERT failures for any newly added enum constant.
 * This synchronizer runs once after SessionFactory creation, detects stale constraints
 * on enum-mapped columns, and replaces them with constraints that reflect the current
 * Java enum definitions.
 */
class EnumCheckConstraintSynchronizer {

    private static final Logger LOG = Logger.getLogger(EnumCheckConstraintSynchronizer.class.getName());
    private static final Pattern QUOTED_VALUE = Pattern.compile("'([^']*)'");
    private static final Pattern BETWEEN_RANGE = Pattern.compile(
            "BETWEEN\\s+(\\d+)\\s+AND\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private EnumCheckConstraintSynchronizer() {}

    static void synchronize(SessionFactory sessionFactory, Collection<Class<?>> entityClasses, String dialect) {
        if (!dialect.toLowerCase().contains("postgresql")) {
            return;
        }

        List<EnumColumnMapping> mappings = collectEnumMappings(entityClasses);
        if (mappings.isEmpty()) return;

        try (Session session = sessionFactory.openSession()) {
            session.doWork(connection -> {
                boolean wasAutoCommit = connection.getAutoCommit();
                try {
                    if (!wasAutoCommit) connection.setAutoCommit(true);
                    for (EnumColumnMapping mapping : mappings) {
                        syncConstraint(connection, mapping);
                    }
                } finally {
                    connection.setAutoCommit(wasAutoCommit);
                }
            });
        } catch (Exception e) {
            LOG.warning("Failed to synchronize enum CHECK constraints: " + e.getMessage());
        }
    }

    private record EnumColumnMapping(
            String tableName,
            String columnName,
            Class<? extends Enum<?>> enumClass,
            EnumType enumType
    ) {}

    private static List<EnumColumnMapping> collectEnumMappings(Collection<Class<?>> entityClasses) {
        List<EnumColumnMapping> mappings = new ArrayList<>();
        for (Class<?> entityClass : entityClasses) {
            String tableName = resolveTableName(entityClass);
            for (Field field : getAllFields(entityClass)) {
                collectFieldMappings(mappings, field, tableName);
            }
        }
        return mappings;
    }

    @SuppressWarnings("unchecked")
    private static void collectFieldMappings(List<EnumColumnMapping> mappings, Field field, String entityTableName) {
        if (field.isAnnotationPresent(Transient.class) || Modifier.isTransient(field.getModifiers())) {
            return;
        }

        if (field.getType().isEnum()) {
            Enumerated enumerated = field.getAnnotation(Enumerated.class);
            EnumType enumType = (enumerated != null) ? enumerated.value() : EnumType.ORDINAL;
            String columnName = resolveColumnName(field);
            mappings.add(new EnumColumnMapping(
                    entityTableName, columnName,
                    (Class<? extends Enum<?>>) field.getType(), enumType
            ));
        }

        if (field.isAnnotationPresent(ElementCollection.class)) {
            Class<?> elementType = resolveCollectionElementType(field);
            if (elementType != null && elementType.isEnum()) {
                CollectionTable ct = field.getAnnotation(CollectionTable.class);
                String collTableName = (ct != null && !ct.name().isEmpty())
                        ? ct.name()
                        : entityTableName + "_" + field.getName();

                Enumerated enumerated = field.getAnnotation(Enumerated.class);
                EnumType enumType = (enumerated != null) ? enumerated.value() : EnumType.ORDINAL;

                Column col = field.getAnnotation(Column.class);
                String colName = (col != null && !col.name().isEmpty()) ? col.name() : field.getName();

                mappings.add(new EnumColumnMapping(
                        collTableName, colName,
                        (Class<? extends Enum<?>>) elementType, enumType
                ));
            }
        }
    }

    private static void syncConstraint(Connection connection, EnumColumnMapping mapping) {
        String constraintName = mapping.tableName() + "_" + mapping.columnName() + "_check";
        try {
            String existingDef = queryConstraintDefinition(connection, mapping.tableName(), constraintName);
            if (existingDef == null) return;

            if (mapping.enumType() == EnumType.STRING) {
                syncStringEnum(connection, mapping, constraintName, existingDef);
            } else {
                syncOrdinalEnum(connection, mapping, constraintName, existingDef);
            }
        } catch (SQLException e) {
            LOG.warning("Failed to sync CHECK constraint \"" + constraintName + "\": " + e.getMessage());
        }
    }

    private static void syncStringEnum(Connection conn, EnumColumnMapping mapping,
                                        String constraintName, String existingDef) throws SQLException {
        Set<String> existing = new TreeSet<>();
        Matcher m = QUOTED_VALUE.matcher(existingDef);
        while (m.find()) existing.add(m.group(1));

        Set<String> current = Arrays.stream(mapping.enumClass().getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        if (existing.equals(current)) return;

        String values = current.stream()
                .map(v -> "'" + v.replace("'", "''") + "'::character varying")
                .collect(Collectors.joining(", "));
        String expr = "(\"" + mapping.columnName() + "\")::text = ANY (ARRAY[" + values + "]::text[])";
        replaceConstraint(conn, mapping.tableName(), constraintName, expr);
    }

    private static void syncOrdinalEnum(Connection conn, EnumColumnMapping mapping,
                                         String constraintName, String existingDef) throws SQLException {
        Matcher m = BETWEEN_RANGE.matcher(existingDef);
        if (!m.find()) return;

        int existingMax = Integer.parseInt(m.group(2));
        int currentMax = mapping.enumClass().getEnumConstants().length - 1;
        if (existingMax == currentMax) return;

        String expr = "\"" + mapping.columnName() + "\" BETWEEN 0 AND " + currentMax;
        replaceConstraint(conn, mapping.tableName(), constraintName, expr);
    }

    private static void replaceConstraint(Connection conn, String tableName,
                                           String constraintName, String checkExpr) throws SQLException {
        LOG.info("Synchronizing CHECK constraint \"" + constraintName + "\" on \"" + tableName + "\"");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE \"" + tableName + "\" DROP CONSTRAINT IF EXISTS \"" + constraintName + "\"");
            stmt.execute("ALTER TABLE \"" + tableName + "\" ADD CONSTRAINT \"" + constraintName
                    + "\" CHECK (" + checkExpr + ")");
        }
    }

    private static String queryConstraintDefinition(Connection conn, String tableName,
                                                     String constraintName) throws SQLException {
        String sql = "SELECT pg_get_constraintdef(con.oid) " +
                "FROM pg_constraint con " +
                "JOIN pg_class rel ON con.conrelid = rel.oid " +
                "WHERE con.contype = 'c' AND rel.relname = ? AND con.conname = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String resolveTableName(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table != null && !table.name().isEmpty()) {
            return table.name();
        }
        return entityClass.getSimpleName();
    }

    private static String resolveColumnName(Field field) {
        Column col = field.getAnnotation(Column.class);
        if (col != null && !col.name().isEmpty()) {
            return col.name();
        }
        return field.getName();
    }

    private static Class<?> resolveCollectionElementType(Field field) {
        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType pt) {
            Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length == 1 && typeArgs[0] instanceof Class<?> c) {
                return c;
            }
        }
        return null;
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }
}
