package sh.fyz.architect.repositories;

import sh.fyz.architect.persistent.SessionManager;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public class GenericRepository<T> {
    protected final Class<T> type;
    protected final ExecutorService threadPool;

    private static final ConcurrentHashMap<Class<?>, Set<String>> VALID_FIELDS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field> ID_FIELD_CACHE = new ConcurrentHashMap<>();

    public GenericRepository(Class<T> type) {
        this.type = type;
        this.threadPool = SessionManager.get().getThreadPool();
    }

    public Class<T> getEntityClass() {
        return type;
    }

    // --- QUERY BUILDER ENTRY POINT ---

    public QueryBuilder<T> query() {
        return new QueryBuilder<>(this);
    }

    // --- FIELD VALIDATION ---

    protected Set<String> getValidFieldNames() {
        return VALID_FIELDS_CACHE.computeIfAbsent(type, clazz -> {
            Set<String> names = ConcurrentHashMap.newKeySet();
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    names.add(f.getName());
                }
                current = current.getSuperclass();
            }
            return names;
        });
    }

    protected void validateFieldName(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            throw new IllegalArgumentException("Field name must not be null or empty");
        }
        if (!getValidFieldNames().contains(fieldName)) {
            throw new IllegalArgumentException(
                "Invalid field name '" + fieldName + "' for entity " + type.getSimpleName()
            );
        }
    }

    // --- ID PREPARATION ---

    private Field getIdField(Class<?> clazz) {
        return ID_FIELD_CACHE.computeIfAbsent(clazz, c -> {
            try {
                return c.getDeclaredField("id");
            } catch (NoSuchFieldException e) {
                return null;
            }
        });
    }

    public Object prepareEntityId(String value) {
        Field field = getIdField(type);
        if (field == null) return value;
        Class<?> fieldType = field.getType();
        if (fieldType == Long.class || fieldType == long.class) {
            return Long.parseLong(value);
        } else if (fieldType == UUID.class) {
            return UUID.fromString(value);
        } else if (fieldType == Integer.class || fieldType == int.class) {
            return Integer.parseInt(value);
        } else if (fieldType == String.class) {
            return value;
        } else if (fieldType == Double.class || fieldType == double.class) {
            return Double.parseDouble(value);
        } else if (fieldType == Float.class || fieldType == float.class) {
            return Float.parseFloat(value);
        } else if (fieldType == Boolean.class || fieldType == boolean.class) {
            return Boolean.parseBoolean(value);
        } else {
            throw new IllegalArgumentException("Unsupported ID type: " + fieldType.getName());
        }
    }

    // --- CRUD OPERATIONS ---

    public T save(T entity) {
        try (Session session = SessionManager.get().getSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                @SuppressWarnings("unchecked")
                T savedEntity = (T) session.merge(entity);
                transaction.commit();
                return savedEntity;
            } catch (Exception e) {
                transaction.rollback();
                throw new RuntimeException("Failed to save entity: " + e.getMessage(), e);
            }
        }
    }

    public void saveAsync(T entity, Consumer<T> callback, Consumer<Exception> errorCallback) {
        threadPool.submit(() -> {
            try {
                T savedEntity = save(entity);
                callback.accept(savedEntity);
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    public T findById(Object id) {
        try (Session session = SessionManager.get().getSession()) {
            return session.get(type, id);
        }
    }

    public void findByIdAsync(Object id, Consumer<T> callback, Consumer<Exception> errorCallback) {
        threadPool.submit(() -> {
            try {
                T entity = findById(id);
                callback.accept(entity);
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    public List<T> all() {
        try (Session session = SessionManager.get().getSession()) {
            return session.createQuery("from " + type.getName(), type).list();
        }
    }

    public void allAsync(Consumer<List<T>> callback, Consumer<Exception> errorCallback) {
        threadPool.submit(() -> {
            try {
                List<T> entities = all();
                callback.accept(entities);
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    public void delete(T entity) {
        try (Session session = SessionManager.get().getSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                session.remove(entity);
                transaction.commit();
            } catch (Exception e) {
                transaction.rollback();
                throw new RuntimeException("Failed to delete entity: " + e.getMessage(), e);
            }
        }
    }

    public void deleteAsync(T entity, Runnable callback, Consumer<Exception> errorCallback) {
        threadPool.submit(() -> {
            try {
                delete(entity);
                callback.run();
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    // --- QUERY BUILDER EXECUTION (overridable by subclasses) ---

    protected List<T> executeQuery(QueryBuilder<T> builder) {
        validateQueryFields(builder);

        try (Session session = SessionManager.get().getSession()) {
            String hql = buildSelectHql(builder);
            Query<T> query = session.createQuery(hql, type);
            bindParameters(query, builder);

            if (builder.getLimit() > 0) {
                query.setMaxResults(builder.getLimit());
            }
            if (builder.getOffset() > 0) {
                query.setFirstResult(builder.getOffset());
            }

            return query.list();
        }
    }

    protected long executeCount(QueryBuilder<T> builder) {
        validateQueryFields(builder);

        try (Session session = SessionManager.get().getSession()) {
            String hql = buildCountHql(builder);
            Query<Long> query = session.createQuery(hql, Long.class);
            bindParameters(query, builder);
            Long result = query.uniqueResult();
            return result != null ? result : 0;
        }
    }

    protected int executeDelete(QueryBuilder<T> builder) {
        validateQueryFields(builder);

        if (builder.getConditions().isEmpty()) {
            throw new IllegalStateException("Cannot execute delete without conditions. Use deleteAll() or add at least one where clause.");
        }

        try (Session session = SessionManager.get().getSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                String hql = buildDeleteHql(builder);
                var query = session.createMutationQuery(hql);
                bindParameters(query, builder);
                int deleted = query.executeUpdate();
                transaction.commit();
                return deleted;
            } catch (Exception e) {
                transaction.rollback();
                throw new RuntimeException("Failed to execute delete query: " + e.getMessage(), e);
            }
        }
    }

    // --- HQL BUILDING ---

    private String buildWhereClause(QueryBuilder<T> builder) {
        List<QueryBuilder.Condition> conditions = builder.getConditions();
        List<QueryBuilder.RawCondition> rawConditions = builder.getRawConditions();

        if (conditions.isEmpty() && rawConditions.isEmpty()) return "";

        StringBuilder where = new StringBuilder(" WHERE ");
        int clauseIndex = 0;

        for (int i = 0; i < conditions.size(); i++) {
            if (clauseIndex > 0) where.append(" AND ");
            QueryBuilder.Condition c = conditions.get(i);
            String param = "p" + i;
            where.append(switch (c.operator()) {
                case EQ -> c.field() + " = :" + param;
                case NEQ -> c.field() + " <> :" + param;
                case GT -> c.field() + " > :" + param;
                case GTE -> c.field() + " >= :" + param;
                case LT -> c.field() + " < :" + param;
                case LTE -> c.field() + " <= :" + param;
                case LIKE -> c.field() + " LIKE :" + param;
                case IN -> c.field() + " IN (:" + param + ")";
                case NOT_IN -> c.field() + " NOT IN (:" + param + ")";
                case IS_NULL -> c.field() + " IS NULL";
                case IS_NOT_NULL -> c.field() + " IS NOT NULL";
            });
            clauseIndex++;
        }

        for (QueryBuilder.RawCondition raw : rawConditions) {
            if (clauseIndex > 0) where.append(" AND ");
            where.append("(").append(raw.hqlFragment()).append(")");
            clauseIndex++;
        }

        return where.toString();
    }

    private String buildOrderByClause(QueryBuilder<T> builder) {
        List<QueryBuilder.OrderBy> orderBys = builder.getOrderBys();
        if (orderBys.isEmpty()) return "";

        StringJoiner joiner = new StringJoiner(", ", " ORDER BY ", "");
        for (QueryBuilder.OrderBy o : orderBys) {
            joiner.add(o.field() + " " + o.order().name());
        }
        return joiner.toString();
    }

    private String buildSelectHql(QueryBuilder<T> builder) {
        return "FROM " + type.getName() + buildWhereClause(builder) + buildOrderByClause(builder);
    }

    private String buildCountHql(QueryBuilder<T> builder) {
        return "SELECT COUNT(*) FROM " + type.getName() + buildWhereClause(builder);
    }

    private String buildDeleteHql(QueryBuilder<T> builder) {
        return "DELETE FROM " + type.getName() + buildWhereClause(builder);
    }

    private void bindParameters(org.hibernate.query.CommonQueryContract query, QueryBuilder<T> builder) {
        List<QueryBuilder.Condition> conditions = builder.getConditions();
        for (int i = 0; i < conditions.size(); i++) {
            QueryBuilder.Condition c = conditions.get(i);
            if (c.operator() != QueryBuilder.Operator.IS_NULL && c.operator() != QueryBuilder.Operator.IS_NOT_NULL) {
                query.setParameter("p" + i, c.value());
            }
        }

        for (QueryBuilder.RawCondition raw : builder.getRawConditions()) {
            for (var entry : raw.parameters().entrySet()) {
                query.setParameter(entry.getKey(), entry.getValue());
            }
        }
    }

    private void validateQueryFields(QueryBuilder<T> builder) {
        for (QueryBuilder.Condition c : builder.getConditions()) {
            validateFieldName(c.field());
        }
        for (QueryBuilder.OrderBy o : builder.getOrderBys()) {
            validateFieldName(o.field());
        }
    }
}
