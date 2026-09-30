package sh.fyz.architect.repositories;

import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.cache.RedisManager;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;

import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.Transaction;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GenericCachedRepository<T extends IdentifiableEntity> extends GenericRepository<T> {

    private static final Logger LOG = Logger.getLogger(GenericCachedRepository.class.getName());
    private static final ConcurrentHashMap<String, Pattern> LIKE_PATTERN_CACHE = new ConcurrentHashMap<>();
    // LIKE patterns often embed user input ("%" + keyword + "%"): without a bound, every distinct
    // search would stay in memory for good.
    private static final int LIKE_PATTERN_CACHE_MAX = 1024;
    // Reflection lookups are cached: in-memory filtering and relation resolution run once per
    // cached entity on every query, and getDeclaredField(s) copies Field objects on each call.
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>> FIELD_LOOKUP_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, List<RelationField>> RELATION_FIELDS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, List<Field>> COLLECTION_FIELDS_CACHE = new ConcurrentHashMap<>();
    // How many flushes (200 ms apart) retry a write refused by a foreign-key or unique constraint
    // before dropping it: another queued write may be what it is waiting for.
    private static final int MAX_CONSTRAINT_RETRIES = 25;

    /** A relation-annotated field, with what resolving it needs precomputed. */
    private record RelationField(Field field, boolean oneToMany, Class<?> elementType, String repositoryName) {}

    private final Class<T> type;
    private final ConcurrentLinkedQueue<DatabaseAction<T>> updateQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedDeque<DatabaseAction<T>> retryQueue = new ConcurrentLinkedDeque<>();
    // A lock rather than synchronized: flushes do JDBC work, and a virtual thread (async calls)
    // blocked in or on a monitor pins its carrier thread before JDK 24.
    private final ReentrantLock flushLock = new ReentrantLock();
    /** Constraint rejections per queued action, guarded by {@link #flushLock}. */
    private final IdentityHashMap<DatabaseAction<T>, Integer> constraintRejections = new IdentityHashMap<>();
    private final String cacheKeyPrefix;
    private final String allEntitiesKey;

    public GenericCachedRepository(Class<T> type) {
        super(type);
        this.type = type;
        this.cacheKeyPrefix = type.getSimpleName() + ":";
        this.allEntitiesKey = cacheKeyPrefix + "*";
        RedisManager.get().getRedisQueueActionPool().add(this);
    }

    @Override
    public T save(T entity) {
        if (entity.getId() == null) {
            if (RedisManager.get().isReceiver()) {
                entity = super.save(entity);
            } else {
                throw new UnsupportedOperationException(
                    "Cannot create new entities (null ID) on a non-receiver instance. " +
                    "New entities must be created on the receiver."
                );
            }
        }

        String key = cacheKeyPrefix + entity.getId();
        RedisManager.get().save(key, entity);

        if (RedisManager.get().isReceiver()) {
            updateQueue.add(new DatabaseAction<>(entity, DatabaseAction.Type.SAVE));
        }
        return entity;
    }

    @Override
    public T findById(Object id) {
        String key = cacheKeyPrefix + id;
        T cachedEntity = RedisManager.get().find(key, type);
        if (cachedEntity != null) {
            return resolveRelations(cachedEntity);
        }
        T dbEntity = super.findById(id);
        if (dbEntity != null) {
            RedisManager.get().save(key, dbEntity);
            return dbEntity;
        }
        return null;
    }

    @Override
    public void delete(T entity) {
        evictFromCache(entity.getId());
        if (RedisManager.get().isReceiver()) {
            updateQueue.add(new DatabaseAction<>(entity, DatabaseAction.Type.DELETE));
        } else {
            super.delete(entity);
        }
    }

    /**
     * A non-receiver deletes straight from the database a copy read from Redis, whose
     * {@code @Version} may be behind the row's (see {@link #applyInTransaction}).
     */
    @Override
    protected void beforeMerge(Session session, T entity) {
        alignVersion(session, entity, entity.getId());
    }

    /** Removes one entity from Redis, leaving the database alone. */
    protected void evictFromCache(Object id) {
        RedisManager.get().delete(cacheKeyPrefix + id);
    }

    private List<T> getAllFromCache() {
        return RedisManager.get().findAll(allEntitiesKey, type);
    }

    /**
     * Serialized so that the flush worker and a caller (see {@link #executeDelete}) never
     * commit two batches of the same queue concurrently, possibly out of order.
     */
    public void flushUpdates() {
        flushLock.lock();
        try {
            flushPending();
        } finally {
            flushLock.unlock();
        }
    }

    private void flushPending() {
        List<DatabaseAction<T>> batch = new ArrayList<>();
        DatabaseAction<T> retry;
        while ((retry = retryQueue.pollFirst()) != null) {
            batch.add(retry);
        }
        DatabaseAction<T> action;
        while ((action = updateQueue.poll()) != null) {
            batch.add(action);
        }
        if (batch.isEmpty()) return;

        try {
            applyInTransaction(batch);
        } catch (Exception e) {
            if (isStaleRow(e) || rejection(e) != null) {
                // One entity's row was deleted or changed meanwhile, or the database refuses one
                // entity's state, which fails the whole transaction. Replaying the batch as is
                // would fail forever and block every later write of this type, so apply each
                // action on its own and drop only the failing ones.
                flushIndividually(batch);
                return;
            }
            for (int i = batch.size() - 1; i >= 0; i--) {
                retryQueue.addFirst(batch.get(i));
            }
            LOG.warning("Failed to flush updates for " + type.getSimpleName() + ": " + e.getMessage());
            return;
        }
        for (int i = 0; i < batch.size(); i++) {
            constraintRejections.remove(batch.get(i));
            evictCommittedDelete(batch, i);
        }
    }

    private void flushIndividually(List<DatabaseAction<T>> batch) {
        for (int i = 0; i < batch.size(); i++) {
            DatabaseAction<T> item = batch.get(i);
            try {
                applyInTransaction(List.of(item));
                constraintRejections.remove(item);
                evictCommittedDelete(batch, i);
            } catch (Exception e) {
                if (isStaleRow(e)) {
                    constraintRejections.remove(item);
                    LOG.warning("Dropped " + item.getType() + " of " + type.getSimpleName() + " "
                            + item.getEntity().getId() + ": " + staleReason(item.getEntity()));
                    refreshDropped(item, batch, i);
                    continue;
                }
                Rejection rejection = rejection(e);
                boolean retry = rejection == Rejection.RETRYABLE
                        && constraintRejections.merge(item, 1, Integer::sum) <= MAX_CONSTRAINT_RETRIES;
                if (rejection != null && !retry) {
                    // Retrying cannot succeed (any more): the same state would be refused again.
                    constraintRejections.remove(item);
                    LOG.severe("Dropped " + item.getType() + " of " + type.getSimpleName() + " "
                            + item.getEntity().getId() + ", rejected by the database: " + e.getMessage());
                    refreshDropped(item, batch, i);
                    continue;
                }
                // Stop at the first other failure and retry it with everything after it, so a
                // later write of the same entity cannot commit before an earlier one.
                for (int j = batch.size() - 1; j >= i; j--) {
                    retryQueue.addFirst(batch.get(j));
                }
                LOG.warning("Failed to flush updates for " + type.getSimpleName() + ": " + e.getMessage());
                return;
            }
        }
    }

    /**
     * A dropped write leaves Redis holding a state the database never got: replace it with the
     * row's (a plain eviction would drop the row from all() and query(), which read the cache as
     * the whole table). Unless a later write of the same entity is still to be applied, whose
     * state the cache holds and must keep serving.
     */
    private void refreshDropped(DatabaseAction<T> dropped, List<DatabaseAction<T>> batch, int index) {
        Object id = dropped.getEntity().getId();
        if (id == null || hasLaterAction(id, batch, index)) {
            return;
        }
        try {
            T row = super.findById(id);
            if (row != null) {
                RedisManager.get().save(cacheKeyPrefix + id, row);
            } else {
                evictFromCache(id);
            }
        } catch (RuntimeException e) {
            LOG.warning("Failed to refresh " + type.getSimpleName() + " " + id + " in the cache: " + e.getMessage());
        }
    }

    /** Whether an action on this id follows position {@code index} of the batch, or is queued. */
    private boolean hasLaterAction(Object id, List<DatabaseAction<T>> batch, int index) {
        for (int j = index + 1; j < batch.size(); j++) {
            if (id.equals(batch.get(j).getEntity().getId())) {
                return true;
            }
        }
        for (DatabaseAction<T> queued : updateQueue) {
            if (id.equals(queued.getEntity().getId())) {
                return true;
            }
        }
        return false;
    }

    private String staleReason(T entity) {
        try {
            return super.findById(entity.getId()) == null
                    ? "its row was deleted from the database"
                    : "version conflict, the row was changed since this entity was read";
        } catch (Exception e) {
            return "its row was deleted or changed meanwhile";
        }
    }

    private void applyInTransaction(List<DatabaseAction<T>> batch) {
        try (Session session = sh.fyz.architect.persistent.SessionManager.get().getSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                int count = 0;
                for (DatabaseAction<T> item : batch) {
                    T entity = item.getEntity();
                    switch (item.getType()) {
                        // Merging a detached entity whose row was deleted throws an
                        // optimistic-lock exception (see alignVersion for @Version entities).
                        case SAVE -> {
                            // The copy saved here and served back from Redis keeps the version
                            // it was read with, while every flush increments the row's: without
                            // this, each save after the first would be dropped as stale. The
                            // cache is last-writer-wins, like for unversioned entities.
                            alignVersion(session, entity, entity.getId());
                            session.merge(entity);
                        }
                        case DELETE -> {
                            // A null id was never persisted, so there is no row to delete.
                            T managed = entity.getId() == null ? null : session.find(type, entity.getId());
                            if (managed != null) {
                                session.remove(managed);
                            }
                        }
                    }
                    count++;
                    if (count % 20 == 0) {
                        session.flush();
                        session.clear();
                    }
                }
                transaction.commit();
            } catch (RuntimeException e) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw e;
            }
        }
    }

    /**
     * delete() evicted the entity when it was queued, but a read before the delete was committed
     * found the row still in the database and cached it again: evict once the row is really gone.
     */
    private void evictCommittedDelete(List<DatabaseAction<T>> batch, int index) {
        DatabaseAction<T> item = batch.get(index);
        Object id = item.getEntity().getId();
        if (item.getType() == DatabaseAction.Type.DELETE && id != null && !hasLaterAction(id, batch, index)) {
            evictQuietly(id);
        }
    }

    /**
     * Evicts after a flush step, whose database side is already settled: a Redis failure here
     * must not abort the flush, which would lose the rest of the batch.
     */
    private void evictQuietly(Object id) {
        try {
            evictFromCache(id);
        } catch (RuntimeException e) {
            LOG.warning("Failed to evict " + type.getSimpleName() + " " + id + " from the cache: " + e.getMessage());
        }
    }

    private enum Rejection {
        /** Refused for the entity's own state: replaying it fails the same way. */
        PERMANENT,
        /** Refused against other rows (foreign key, unique): may pass once other writes commit. */
        RETRYABLE
    }

    /**
     * Whether the database refused the entity's state (constraint violation, value out of range
     * or too long, missing non-null property), unlike a lost connection or a lock timeout; null
     * for any other failure.
     */
    private static Rejection rejection(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                return switch (cve.getKind()) {
                    case NOT_NULL, CHECK -> Rejection.PERMANENT;
                    // Each repository flushes on its own: a child's delete queued in another
                    // repository may commit after its parent's, which fails until then.
                    case FOREIGN_KEY, UNIQUE, OTHER -> Rejection.RETRYABLE;
                };
            }
            if (t instanceof org.hibernate.exception.DataException
                    || t instanceof org.hibernate.PropertyValueException) {
                return Rejection.PERMANENT;
            }
        }
        return null;
    }

    /** Whether the failure is an optimistic-lock one: the entity's row was deleted or changed meanwhile. */
    private static boolean isStaleRow(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof jakarta.persistence.OptimisticLockException
                    || t instanceof org.hibernate.StaleStateException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<T> all() {
        List<T> entities = getAllFromCache();

        if (entities != null && !entities.isEmpty()) {
            List<T> resolvedEntities = new ArrayList<>();
            for (T entity : entities) {
                T resolvedEntity = resolveRelations(entity);
                if (resolvedEntity != null) {
                    resolvedEntities.add(resolvedEntity);
                }
            }
            return resolvedEntities;
        }

        entities = super.all();
        if (entities != null && !entities.isEmpty()) {
            for (T entity : entities) {
                RedisManager.get().save(cacheKeyPrefix + entity.getId(), entity);
            }
            return entities;
        } else {
            return new ArrayList<>();
        }
    }

    /**
     * Database reads end up in Redis, which stores association collections as their elements'
     * ids and element collections as values: either way a lazy collection must be loaded while
     * the session is open, or caching the entity throws.
     */
    @Override
    protected void prepareDetached(Session session, T entity) {
        try {
            for (Field field : getCollectionFields(entity.getClass())) {
                Object value = field.get(entity);
                if (value != null && !Hibernate.isInitialized(value)) {
                    Hibernate.initialize(value);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read the collections of " + type.getSimpleName(), e);
        }
    }

    /** The association and element collections of {@code clazz} and its superclasses, made accessible once. */
    private static List<Field> getCollectionFields(Class<?> clazz) {
        return COLLECTION_FIELDS_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> fields = new ArrayList<>();
            for (Class<?> current = c; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())
                            && (field.isAnnotationPresent(OneToMany.class)
                                || field.isAnnotationPresent(ManyToMany.class)
                                || field.isAnnotationPresent(ElementCollection.class))) {
                        field.setAccessible(true);
                        fields.add(field);
                    }
                }
            }
            return List.copyOf(fields);
        });
    }

    // --- QUERY BUILDER EXECUTION (cache-first) ---

    @Override
    protected List<T> executeQueryWithLimit(QueryBuilder<T> builder, int explicitLimit) {
        validateQueryFields(builder);
        if (builder.hasRawConditions()) {
            return super.executeQueryWithLimit(builder, explicitLimit);
        }

        List<T> cached = getAllFromCache();
        if (cached != null && !cached.isEmpty()) {
            Stream<T> stream = cached.stream();

            for (QueryBuilder.Condition condition : builder.getConditions()) {
                stream = stream.filter(entity -> matchesCondition(entity, condition));
            }

            stream = stream.map(this::resolveRelations).filter(Objects::nonNull);

            if (!builder.getOrderBys().isEmpty()) {
                stream = stream.sorted(buildComparator(builder.getOrderBys()));
            }

            if (builder.getOffset() > 0) {
                stream = stream.skip(builder.getOffset());
            }
            if (explicitLimit > 0) {
                stream = stream.limit(explicitLimit);
            }

            return stream.collect(Collectors.toList());
        }

        List<T> dbResults = super.executeQueryWithLimit(builder, explicitLimit);
        if (dbResults != null) {
            for (T entity : dbResults) {
                if (entity instanceof IdentifiableEntity ie && ie.getId() != null) {
                    RedisManager.get().save(cacheKeyPrefix + ie.getId(), entity);
                }
            }
        }
        return dbResults;
    }

    @Override
    protected long executeCount(QueryBuilder<T> builder) {
        validateQueryFields(builder);
        if (builder.hasRawConditions()) {
            return super.executeCount(builder);
        }

        List<T> cached = getAllFromCache();
        if (cached != null && !cached.isEmpty()) {
            return cached.stream()
                .filter(entity -> {
                    for (QueryBuilder.Condition c : builder.getConditions()) {
                        if (!matchesCondition(entity, c)) return false;
                    }
                    return true;
                })
                .count();
        }
        return super.executeCount(builder);
    }

    @Override
    protected int executeDelete(QueryBuilder<T> builder) {
        validateQueryFields(builder);
        if (RedisManager.get().isReceiver()) {
            // The delete runs against the database, but rows are matched on the cache, which
            // already holds the queued writes: apply them first, or the delete misses rows the
            // cache matched and a queued write then brings a deleted row back.
            flushUpdates();
        }
        List<Object> matchedIds = new ArrayList<>();
        if (builder.hasRawConditions()) {
            // Raw HQL cannot be evaluated in memory, and evicting on the other conditions alone
            // would drop rows that stay in the database from a cache that queries treat as the
            // whole table. Ask the database which rows the delete will remove.
            for (T entity : findAllMatching(builder)) {
                if (entity.getId() != null) {
                    matchedIds.add(entity.getId());
                }
            }
        } else {
            List<T> cached = getAllFromCache();
            if (cached != null) {
                for (T entity : cached) {
                    boolean matches = true;
                    for (QueryBuilder.Condition c : builder.getConditions()) {
                        if (!matchesCondition(entity, c)) {
                            matches = false;
                            break;
                        }
                    }
                    if (matches && entity.getId() != null) {
                        matchedIds.add(entity.getId());
                    }
                }
            }
        }

        int deleted = super.executeDelete(builder);
        for (Object id : matchedIds) {
            evictFromCache(id);
        }
        return deleted;
    }

    // --- IN-MEMORY CONDITION MATCHING ---

    /**
     * Evaluates a condition the way the database would, so that a warm cache returns the same
     * rows: under SQL's three-valued logic a comparison involving NULL is never true (only
     * IS NULL / IS NOT NULL test for it), and numbers compare by value, not by boxed type.
     */
    private boolean matchesCondition(T entity, QueryBuilder.Condition condition) {
        Object fieldValue = getFieldValue(entity, condition.field());
        Object condValue = condition.value();

        switch (condition.operator()) {
            case IS_NULL:
                return fieldValue == null;
            case IS_NOT_NULL:
                return fieldValue != null;
            case NOT_IN:
                if (condValue instanceof Collection<?> c && c.isEmpty()) {
                    return true;
                }
                break;
            default:
                break;
        }
        if (fieldValue == null || condValue == null) {
            return false;
        }

        return switch (condition.operator()) {
            case EQ -> valuesEqual(fieldValue, condValue);
            case NEQ -> !valuesEqual(fieldValue, condValue);
            case GT -> compareValues(fieldValue, condValue) > 0;
            case GTE -> compareValues(fieldValue, condValue) >= 0;
            case LT -> compareValues(fieldValue, condValue) < 0;
            case LTE -> compareValues(fieldValue, condValue) <= 0;
            case LIKE -> matchesLike(fieldValue, condValue);
            case IN -> condValue instanceof Collection<?> c
                    && c.stream().anyMatch(v -> v != null && valuesEqual(fieldValue, v));
            // x NOT IN (..., NULL) is never true in SQL.
            // contains(null) would throw on List.of / Set.of.
            case NOT_IN -> condValue instanceof Collection<?> c && c.stream().noneMatch(Objects::isNull)
                    && c.stream().noneMatch(v -> valuesEqual(fieldValue, v));
            case IS_NULL, IS_NOT_NULL -> throw new IllegalStateException("handled above");
        };
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return compareNumbers(na, nb) == 0;
        }
        return Objects.equals(a, b);
    }

    private static int compareNumbers(Number a, Number b) {
        if (isIntegral(a) && isIntegral(b)) {
            return Long.compare(a.longValue(), b.longValue());
        }
        if (isFloating(a) && isFloating(b)) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        // Mixed or arbitrary-precision operands: a double would round large longs and
        // BigDecimals, making distinct values compare equal.
        try {
            return toBigDecimal(a).compareTo(toBigDecimal(b));
        } catch (NumberFormatException e) {
            return Double.compare(a.doubleValue(), b.doubleValue()); // NaN / Infinity
        }
    }

    private static boolean isFloating(Number n) {
        return n instanceof Double || n instanceof Float;
    }

    private static java.math.BigDecimal toBigDecimal(Number n) {
        if (n instanceof java.math.BigDecimal bd) return bd;
        if (n instanceof java.math.BigInteger bi) return new java.math.BigDecimal(bi);
        return new java.math.BigDecimal(n.toString());
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a == null || b == null) return 0;

        if (a instanceof Number na && b instanceof Number nb) {
            return compareNumbers(na, nb);
        }

        if (a instanceof Comparable ca && a.getClass().isAssignableFrom(b.getClass())) {
            return ca.compareTo(b);
        }

        return a.toString().compareTo(b.toString());
    }

    private boolean matchesLike(Object fieldValue, Object pattern) {
        if (fieldValue == null || pattern == null) return false;
        String value = fieldValue.toString();
        String pat = pattern.toString();
        if (LIKE_PATTERN_CACHE.size() >= LIKE_PATTERN_CACHE_MAX) {
            LIKE_PATTERN_CACHE.clear();
        }
        Pattern compiled = LIKE_PATTERN_CACHE.computeIfAbsent(pat, p -> {
            String regex = "^" + Pattern.quote(p)
                .replace("%", "\\E.*\\Q")
                .replace("_", "\\E.\\Q") + "$";
            // DOTALL: SQL's % and _ match line breaks too.
            return Pattern.compile(regex, Pattern.DOTALL);
        });
        return compiled.matcher(value).matches();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Comparator<T> buildComparator(List<QueryBuilder.OrderBy> orderBys) {
        Comparator<T> comparator = null;
        for (QueryBuilder.OrderBy order : orderBys) {
            Comparator<T> fieldComparator = (a, b) -> {
                Object va = getFieldValue(a, order.field());
                Object vb = getFieldValue(b, order.field());
                if (va == null && vb == null) return 0;
                if (va == null) return 1;
                if (vb == null) return -1;
                if (va instanceof Comparable ca) {
                    return ca.compareTo(vb);
                }
                return va.toString().compareTo(vb.toString());
            };
            if (order.order() == QueryBuilder.SortOrder.DESC) {
                fieldComparator = fieldComparator.reversed();
            }
            comparator = comparator == null ? fieldComparator : comparator.thenComparing(fieldComparator);
        }
        return comparator;
    }

    // --- REFLECTION UTILITIES ---

    private Object getFieldValue(Object entity, String fieldName) {
        try {
            Field field = findField(entity.getClass(), fieldName);
            if (field == null) return null;
            return field.get(entity);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves {@code @OneToMany} collections (stored as id lists in Redis). For
     * {@code @ManyToOne} / {@code @OneToOne}, RedisManager.reconstructEntity already
     * resolves the referenced entity, so the field may already hold the target entity
     * (or null). We guard against the historical bug where the field was treated as
     * holding an ID while actually holding the resolved entity.
     */
    private T resolveRelations(T entity) {
        try {
            // Keyed by repository name: keying by field type would make every @OneToMany
            // collection (all typed List or Set) share whichever repository was looked up first.
            HashMap<String, GenericRepository<?>> repoCache = new HashMap<>();
            for (RelationField relation : getRelationFields(entity.getClass())) {
                Field field = relation.field();
                if (relation.oneToMany()) {
                    Object raw = field.get(entity);
                    if (raw instanceof Collection<?> ids && !ids.isEmpty() && isIdCollection(relation.elementType(), ids)) {
                        Collection<Object> resolvedEntities = new ArrayList<>();
                        GenericRepository<?> repository = repoCache.computeIfAbsent(
                                relation.repositoryName(),
                                name -> RepositoryRegistry.get().getRepository(name)
                        );
                        if (repository != null) {
                            for (Object id : ids) {
                                Object resolvedEntity = repository.findById(id);
                                if (resolvedEntity != null) {
                                    resolvedEntities.add(resolvedEntity);
                                }
                            }
                        }
                        field.set(entity, resolvedEntities);
                    }
                } else {
                    Object value = field.get(entity);
                    if (value == null) continue;

                    if (field.getType().isInstance(value)) {
                        continue;
                    }

                    GenericRepository<?> repository = repoCache.computeIfAbsent(
                            relation.repositoryName(),
                            name -> RepositoryRegistry.get().getRepository(name)
                    );
                    if (repository != null) {
                        Object resolvedEntity = repository.findById(value);
                        if (resolvedEntity != null) {
                            field.set(entity, resolvedEntity);
                        }
                    }
                }
            }
            return entity;
        } catch (Exception e) {
            LOG.warning("Failed to resolve relations for " + type.getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /** The relation fields declared directly on {@code clazz}, made accessible once. */
    private List<RelationField> getRelationFields(Class<?> clazz) {
        return RELATION_FIELDS_CACHE.computeIfAbsent(clazz, c -> {
            List<RelationField> relations = new ArrayList<>();
            for (Field field : c.getDeclaredFields()) {
                if (field.isAnnotationPresent(OneToMany.class)) {
                    field.setAccessible(true);
                    relations.add(new RelationField(field, true,
                            resolveCollectionElementType(field), guessRepositoryName(field)));
                } else if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                    field.setAccessible(true);
                    relations.add(new RelationField(field, false, null,
                            field.getType().getSimpleName().toLowerCase() + "s"));
                }
            }
            return List.copyOf(relations);
        });
    }

    /**
     * Heuristic: an {@code @OneToMany} collection holds raw IDs (from Redis) if the
     * first element is not an instance of the field's generic element type.
     */
    private boolean isIdCollection(Class<?> element, Collection<?> values) {
        if (element == null) return true;
        for (Object v : values) {
            if (v == null) continue;
            return !element.isInstance(v);
        }
        return false;
    }

    private Class<?> resolveCollectionElementType(Field field) {
        java.lang.reflect.Type genericType = field.getGenericType();
        if (genericType instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> c) {
                return c;
            }
        }
        return null;
    }

    private String guessRepositoryName(Field field) {
        Class<?> element = resolveCollectionElementType(field);
        if (element != null) {
            return element.getSimpleName().toLowerCase() + "s";
        }
        return field.getName();
    }

    /** The named field, searched up the hierarchy and made accessible; null if absent or inaccessible. */
    private Field findField(Class<?> clazz, String fieldName) {
        if (clazz == null || fieldName == null) return null;
        ConcurrentHashMap<String, Field> fields = FIELD_LOOKUP_CACHE.computeIfAbsent(clazz, c -> new ConcurrentHashMap<>());
        Field field = fields.get(fieldName);
        if (field == null) {
            // Misses are not cached: field names reach here unvalidated, so caching them would
            // let arbitrary names grow the map without bound.
            field = lookupField(clazz, fieldName);
            if (field != null) {
                fields.putIfAbsent(fieldName, field);
            }
        }
        return field;
    }

    private static Field lookupField(Class<?> clazz, String fieldName) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // keep walking up
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
