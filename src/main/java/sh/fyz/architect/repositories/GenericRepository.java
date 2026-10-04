package sh.fyz.architect.repositories;

import jakarta.persistence.Id;
import jakarta.persistence.Version;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.persistent.SessionManager;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class GenericRepository<T> {
    protected final Class<T> type;

    private static final ConcurrentHashMap<Class<?>, Set<String>> VALID_FIELDS_CACHE = new ConcurrentHashMap<>();
    // Names of the builder's own parameters. Unusual on purpose: a whereRaw() parameter with the
    // same name would silently overwrite the builder's value (or be overwritten by it).
    private static final String PARAM_PREFIX = "__architect_p";
    // Optional because ConcurrentHashMap cannot hold null, and "no id field" must be cached too.
    private static final ConcurrentHashMap<Class<?>, Optional<Field>> ID_FIELD_CACHE = new ConcurrentHashMap<>();
    // Optional because ConcurrentHashMap cannot hold null, and "no @Version field" must be cached too.
    private static final ConcurrentHashMap<Class<?>, Optional<Field>> VERSION_FIELD_CACHE = new ConcurrentHashMap<>();
    // For async calls on an instance with neither a database nor Redis, which then fail, but
    // through their error callback. Virtual threads need no shutdown.
    private static final ExecutorService FALLBACK_THREAD_POOL = Executors.newVirtualThreadPerTaskExecutor();

    public GenericRepository(Class<T> type) {
        this.type = type;
    }

    public Class<T> getEntityClass() {
        return type;
    }

    /**
     * Resolves the current session thread pool on each call. This avoids keeping a
     * stale reference after {@code architect.stop()} / {@code start()}, which used to
     * throw {@link java.util.concurrent.RejectedExecutionException} on async operations.
     * Without a database there is no session thread pool: Redis' executor is used instead
     * (a relay-only non-receiver), which {@code stop()} also waits for.
     */
    protected ExecutorService threadPool() {
        if (SessionManager.isInitialized()) {
            return SessionManager.get().getThreadPool();
        }
        return RedisManager.isInitialized() ? RedisManager.get().getAsyncExecutor() : FALLBACK_THREAD_POOL;
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

    /**
     * The {@code @Id} field, searched up the hierarchy so an id inherited from a
     * {@code @MappedSuperclass} is found; falls back to a field named {@code id}.
     */
    private Field getIdField(Class<?> clazz) {
        return ID_FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Field named = null;
            for (Class<?> current = c; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field f : current.getDeclaredFields()) {
                    if (f.isAnnotationPresent(Id.class)) {
                        return Optional.of(f);
                    }
                    if (named == null && f.getName().equals("id")) {
                        named = f;
                    }
                }
            }
            return Optional.ofNullable(named);
        }).orElse(null);
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
                beforeMerge(session, entity);
                @SuppressWarnings("unchecked")
                T savedEntity = (T) session.merge(entity);
                transaction.commit();
                return savedEntity;
            } catch (Exception e) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw new RuntimeException("Failed to save entity: " + e.getMessage(), e);
            }
        }
    }

    public void saveAsync(T entity, Consumer<T> callback, Consumer<Exception> errorCallback) {
        threadPool().submit(() -> {
            try {
                T savedEntity = save(entity);
                callback.accept(savedEntity);
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    public T findById(Object id) {
        try (Session session = openReadOnlySession()) {
            T entity = session.find(type, id);
            if (entity != null) {
                prepareDetached(session, entity);
            }
            return entity;
        }
    }

    public void findByIdAsync(Object id, Consumer<T> callback, Consumer<Exception> errorCallback) {
        threadPool().submit(() -> {
            try {
                T entity = findById(id);
                callback.accept(entity);
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    public List<T> all() {
        try (Session session = openReadOnlySession()) {
            List<T> entities = session.createQuery("from " + type.getName(), type).list();
            entities.forEach(entity -> prepareDetached(session, entity));
            return entities;
        }
    }

    public void allAsync(Consumer<List<T>> callback, Consumer<Exception> errorCallback) {
        threadPool().submit(() -> {
            try {
                List<T> entities = all();
                callback.accept(entities);
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    /**
     * Deletes the entity's row. An entity whose row no longer exists (deleted by another
     * instance, or relayed twice), or that was never saved, has nothing to delete: that is not
     * an error.
     */
    public void delete(T entity) {
        try (Session session = SessionManager.get().getSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                // Looked up first: merging a detached entity whose row is gone throws an
                // optimistic-lock exception, and merging one never saved would insert it.
                Object id = session.getSessionFactory().getPersistenceUnitUtil().getIdentifier(entity);
                if (id != null && session.find(type, id) != null) {
                    beforeMerge(session, entity);
                    // Merged onto the instance just loaded, which still checks a @Version.
                    Object managed = session.merge(entity);
                    session.remove(managed);
                }
                transaction.commit();
            } catch (Exception e) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw new RuntimeException("Failed to delete entity: " + e.getMessage(), e);
            }
        }
    }

    public void deleteAsync(T entity, Runnable callback, Consumer<Exception> errorCallback) {
        threadPool().submit(() -> {
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
        return executeQueryWithLimit(builder, builder.getLimit());
    }

    /**
     * Executes a query with an explicit limit override. Used by {@link QueryBuilder#findFirst()}
     * to request a single row without mutating the builder's state (which would be unsafe
     * for concurrent callers).
     */
    protected List<T> executeQueryWithLimit(QueryBuilder<T> builder, int explicitLimit) {
        return select(builder, explicitLimit, builder.getOffset(), true);
    }

    /**
     * Every row matching the builder's conditions, limit and offset ignored — the rows its
     * {@link QueryBuilder#delete()} removes. Only their ids are used, so they skip
     * {@link #prepareDetached}.
     */
    protected List<T> findAllMatching(QueryBuilder<T> builder) {
        return select(builder, -1, 0, false);
    }

    private List<T> select(QueryBuilder<T> builder, int limit, int offset, boolean prepare) {
        try (Session session = openReadOnlySession()) {
            String hql = buildSelectHql(builder);
            Query<T> query = session.createQuery(hql, type);
            bindParameters(query, builder);

            if (limit > 0) {
                query.setMaxResults(limit);
            }
            if (offset > 0) {
                query.setFirstResult(offset);
            }

            List<T> entities = query.list();
            if (prepare) {
                entities.forEach(entity -> prepareDetached(session, entity));
            }
            return entities;
        }
    }

    protected long executeCount(QueryBuilder<T> builder) {
        try (Session session = SessionManager.get().getSession()) {
            String hql = buildCountHql(builder);
            Query<Long> query = session.createQuery(hql, Long.class);
            bindParameters(query, builder);
            Long result = query.uniqueResult();
            return result != null ? result : 0;
        }
    }

    protected int executeDelete(QueryBuilder<T> builder) {
        if (builder.getConditions().isEmpty() && builder.getRawConditions().isEmpty()) {
            throw new IllegalStateException("Cannot execute delete without conditions. Add at least one where clause.");
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
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw new RuntimeException("Failed to execute delete query: " + e.getMessage(), e);
            }
        }
    }

    /** Called by {@link #save} and {@link #delete} inside their transaction, just before the entity is merged. */
    protected void beforeMerge(Session session, T entity) {
    }

    /**
     * Gives a {@code @Version} entity the version its row currently has, so that merging it
     * overwrites the row (last writer wins) instead of failing the version check. For copies
     * that keep the version they were read with while the row's moves on, like the ones served
     * from Redis. A deleted row is left alone, so its merge still fails.
     */
    protected void alignVersion(Session session, T entity, Object id) {
        Field versionField = VERSION_FIELD_CACHE.computeIfAbsent(entity.getClass(), c -> {
            for (Class<?> current = c; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field f : current.getDeclaredFields()) {
                    if (f.isAnnotationPresent(Version.class)) {
                        f.setAccessible(true);
                        return Optional.of(f);
                    }
                }
            }
            return Optional.empty();
        }).orElse(null);
        if (versionField == null || id == null) {
            return;
        }
        T current = session.find(type, id);
        if (current == null) {
            return;
        }
        try {
            versionField.set(entity, versionField.get(current));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access @Version field of " + type.getSimpleName(), e);
        }
    }

    /**
     * Called for each entity a read returns, while its session is still open. Subclasses that
     * need more of the entity graph than the mapping fetches eagerly load it here: once the
     * session closes, touching an uninitialized lazy association throws.
     */
    protected void prepareDetached(Session session, T entity) {
    }

    /**
     * A session for queries whose results are returned detached. Read-only entities skip the
     * copy of their loaded state Hibernate otherwise keeps for dirty checking — pure overhead
     * here, since the session closes before the caller could modify anything.
     */
    private Session openReadOnlySession() {
        Session session = SessionManager.get().getSession();
        session.setDefaultReadOnly(true);
        return session;
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
            String param = PARAM_PREFIX + i;
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
                query.setParameter(PARAM_PREFIX + i, c.value());
            }
        }

        for (QueryBuilder.RawCondition raw : builder.getRawConditions()) {
            for (var entry : raw.parameters().entrySet()) {
                query.setParameter(entry.getKey(), entry.getValue());
            }
        }
    }
}
