package sh.fyz.architect.repositories;

import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.cache.RedisQueueActionPool;
import sh.fyz.architect.persistent.DatabaseFailures;

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
import java.util.concurrent.atomic.AtomicReference;
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
    // How many flushes (200 ms apart) retry a write refused by a foreign-key or unique constraint,
    // or failing for another reason than the database's unavailability, before dropping it:
    // another queued write may be what it is waiting for.
    private static final int MAX_CONSTRAINT_RETRIES = 25;
    // While the database is unavailable every flush (200 ms apart) fails the same way: reported
    // at most this often per repository.
    private static final long FAILURE_REPORT_INTERVAL_MS = 30_000;

    // Every live cached repository, held weakly: Architect.start() attaches those created before
    // a stop() to the new Redis manager (see attachAll).
    private static final Set<GenericCachedRepository<?>> INSTANCES =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

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
    /** Failed flushes not reported yet, and when the last was; guarded by {@link #flushLock}. */
    private int unreportedFailures;
    private long lastFailureReport;
    private boolean failing;
    /**
     * Serialize, per entity, a cache write with its queuing (save, delete) against a flush's
     * check for later writes and its own cache write (see {@link #refreshDropped}): a save
     * landing in between would otherwise be overwritten in Redis by the older row. Striped by id,
     * so saves of different entities rarely wait for each other's Redis round trip.
     */
    private final ReentrantLock[] cacheLocks = new ReentrantLock[32];
    /** Writes taken from the queues by the flush in progress; written under {@link #flushLock}. */
    private volatile int inFlight;
    private final String cacheKeyPrefix;
    private final String allEntitiesKey;
    /** The flush pool this repository is registered with; each Redis manager has its own. */
    private final AtomicReference<RedisQueueActionPool> registeredPool = new AtomicReference<>();

    public GenericCachedRepository(Class<T> type) {
        super(type);
        this.type = type;
        this.cacheKeyPrefix = type.getSimpleName() + ":";
        this.allEntitiesKey = cacheKeyPrefix + "*";
        for (int i = 0; i < cacheLocks.length; i++) {
            cacheLocks[i] = new ReentrantLock();
        }
        attach();
        INSTANCES.add(this);
    }

    /**
     * Prepares the live cached repositories for the current Redis manager (see
     * {@link #onRestart}). Called by {@code Architect.start()}: a repository kept across
     * {@code stop()} / {@code start()} would otherwise queue writes that no flush worker applies.
     */
    public static void attachAll() {
        List<GenericCachedRepository<?>> live;
        synchronized (INSTANCES) {
            live = new ArrayList<>(INSTANCES);
        }
        for (GenericCachedRepository<?> repository : live) {
            // Its entity is not managed by the database this start() connected to.
            if (sh.fyz.architect.persistent.SessionManager.isInitialized()
                    && sh.fyz.architect.persistent.SessionManager.get()
                            .getEntityClass(repository.type.getSimpleName()) != repository.type) {
                continue;
            }
            try {
                repository.onRestart();
            } catch (RuntimeException e) {
                LOG.warning("Failed to attach the cached repository of " + repository.type.getSimpleName()
                        + " to Redis, or to load its cache: " + e.getMessage());
            }
        }
    }

    /**
     * For a repository created before a restart. Only one with writes still queued attaches at
     * once, since they must be flushed; the others attach on their next call. The flush pool keeps
     * an attached repository for the whole run: attaching every live one would also keep, run
     * after run, those the application dropped but the garbage collector has not reclaimed yet.
     */
    protected void onRestart() {
        if (pendingWriteCount() > 0) {
            attach();
        }
    }

    /**
     * Registers with the current Redis manager's flush pool and loads the cache, once per manager;
     * every call of this repository does it first. Writes still queued from before a restart are
     * then cached again, or Redis would serve the rows' older state until the next save overwrote
     * them. The flush lock is held throughout: the pool's worker, already running, could
     * otherwise commit a queued write between the database read and the recache, which would then
     * find nothing to put back over the older state just cached.
     */
    protected void attach() {
        RedisQueueActionPool pool = RedisManager.get().getRedisQueueActionPool();
        if (pool == null) {
            throw new IllegalStateException("Redis is still starting");
        }
        if (registeredPool.get() == pool) {
            return;
        }
        flushLock.lock();
        try {
            if (registeredPool.get() == pool) {
                return;
            }
            pool.add(this);
            try {
                // Without a database there is nothing to load.
                if (sh.fyz.architect.persistent.SessionManager.isInitialized()) {
                    loadCache(pool);
                }
            } finally {
                // Even if loading failed: part of the cache may hold older states.
                recachePendingWrites();
            }
            // Only once loaded: a failed load (database not started yet) is retried by the next call.
            registeredPool.set(pool);
        } finally {
            flushLock.unlock();
        }
    }

    /**
     * Loads the cache from the database. The receiver loads the whole table once per Redis
     * manager and entity type, whatever the cache already holds: its start cleared Redis, and a
     * few entities cached since by another instance are not the whole table, which all() and
     * query() take the cache for. Other instances only fill an empty cache: reloading a warm
     * one at each start would cost a full table read, and could cache again a row another
     * instance deletes meanwhile. Rows already cached are kept (SET NX) either way: a state
     * cached meanwhile (a relayed save, another repository's write) may be newer.
     */
    private void loadCache(RedisQueueActionPool pool) {
        if (!RedisManager.get().isReceiver()) {
            loadAll();
            return;
        }
        if (!pool.claimCacheLoad(type)) {
            return;
        }
        try {
            cacheRows(super.all());
        } catch (RuntimeException e) {
            // Retried by the next call of a repository of this type.
            pool.releaseCacheLoad(type);
            throw e;
        }
    }

    /**
     * Applies the queued writes to Redis, in order; the flush worker applies them to the
     * database. Called with {@link #flushLock} held.
     */
    private void recachePendingWrites() {
        for (Iterator<DatabaseAction<T>> it = pendingWrites(); it.hasNext(); ) {
            DatabaseAction<T> action = it.next();
            Object id = action.getEntity().getId();
            if (id == null) {
                continue;
            }
            switch (action.getType()) {
                case SAVE -> RedisManager.get().save(cacheKeyPrefix + id, action.getEntity());
                case DELETE -> evictFromCache(id);
                default -> { }
            }
        }
    }

    /** The writes not applied to the database yet, oldest first. */
    private Iterator<DatabaseAction<T>> pendingWrites() {
        return Stream.concat(retryQueue.stream(), updateQueue.stream()).iterator();
    }

    /**
     * How many writes are not applied to the database yet, those of a flush in progress included.
     * Exact once the flushes have stopped; while one runs, an instant's approximation.
     */
    public int pendingWriteCount() {
        return retryQueue.size() + updateQueue.size() + inFlight;
    }

    @Override
    public T save(T entity) {
        attach();
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
        ReentrantLock lock = cacheLock(entity.getId());
        lock.lock();
        try {
            RedisManager.get().save(key, entity);

            if (RedisManager.get().isReceiver()) {
                updateQueue.add(new DatabaseAction<>(entity, DatabaseAction.Type.SAVE));
            }
        } finally {
            lock.unlock();
        }
        return entity;
    }

    @Override
    public T findById(Object id) {
        attach();
        String key = cacheKeyPrefix + id;
        T cachedEntity = RedisManager.get().find(key, type);
        if (cachedEntity != null) {
            return resolveRelations(cachedEntity);
        }
        T dbEntity = super.findById(id);
        if (dbEntity != null) {
            // Unless a state was cached meanwhile, which may be newer than this read.
            RedisManager.get().saveIfAbsent(key, dbEntity);
            return dbEntity;
        }
        return null;
    }

    @Override
    public void delete(T entity) {
        attach();
        if (RedisManager.get().isReceiver()) {
            ReentrantLock lock = cacheLock(entity.getId());
            lock.lock();
            try {
                evictFromCache(entity.getId());
                updateQueue.add(new DatabaseAction<>(entity, DatabaseAction.Type.DELETE));
            } finally {
                lock.unlock();
            }
        } else {
            evictFromCache(entity.getId());
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

    /** The lock of {@link #cacheLocks} guarding this entity's cache entry; null ids share one. */
    private ReentrantLock cacheLock(Object id) {
        return cacheLocks[id == null ? 0 : Math.floorMod(id.hashCode(), cacheLocks.length)];
    }

    /** Removes one entity from Redis, leaving the database alone. */
    protected void evictFromCache(Object id) {
        RedisManager.get().delete(cacheKeyPrefix + id);
    }

    private List<T> getAllFromCache() {
        return RedisManager.get().findAll(allEntitiesKey, type);
    }

    /**
     * Serialized so that the flush worker and a caller never commit two batches of the same
     * queue concurrently, possibly out of order ({@link #executeDelete} holds the lock too).
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
        // No database (yet, or any more): the writes wait for one instead of failing, which
        // would count as attempts and eventually drop them.
        if (!sh.fyz.architect.persistent.SessionManager.isInitialized()) {
            return;
        }
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
        inFlight = batch.size();
        try {
            flushBatch(batch);
        } finally {
            // Failed writes are back in retryQueue by now.
            inFlight = 0;
        }
    }

    private void flushBatch(List<DatabaseAction<T>> batch) {

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
            reportFailure(e);
            return;
        }
        for (int i = 0; i < batch.size(); i++) {
            constraintRejections.remove(batch.get(i));
            evictCommittedDelete(batch, i);
        }
        reportRecovery();
    }

    /** Logs a failed flush that will be retried, at most every {@link #FAILURE_REPORT_INTERVAL_MS}. */
    private void reportFailure(Exception e) {
        failing = true;
        unreportedFailures++;
        long now = System.currentTimeMillis();
        if (now - lastFailureReport >= FAILURE_REPORT_INTERVAL_MS) {
            LOG.warning("Failed to flush updates for " + type.getSimpleName() + " (" + unreportedFailures
                    + " attempt(s) since the last report, retrying): " + e.getMessage());
            lastFailureReport = now;
            unreportedFailures = 0;
        }
    }

    /** Logs that flushes succeed again after failures, so that the next failure is reported at once. */
    private void reportRecovery() {
        if (failing) {
            LOG.info("Flushing updates for " + type.getSimpleName() + " works again");
            failing = false;
            unreportedFailures = 0;
            lastFailureReport = 0;
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
                    // Retrying cannot succeed (any more): the same write would fail again.
                    constraintRejections.remove(item);
                    LOG.severe("Dropped " + item.getType() + " of " + type.getSimpleName() + " "
                            + item.getEntity().getId() + ", which could not be applied: " + e.getMessage());
                    refreshDropped(item, batch, i);
                    continue;
                }
                // Stop at the first other failure and retry it with everything after it, so a
                // later write of the same entity cannot commit before an earlier one.
                for (int j = batch.size() - 1; j >= i; j--) {
                    retryQueue.addFirst(batch.get(j));
                }
                reportFailure(e);
                return;
            }
        }
        reportRecovery();
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
            ReentrantLock lock = cacheLock(id);
            lock.lock();
            try {
                // Checked again: a save since the first check cached a newer state than this row.
                if (hasLaterAction(id, batch, index)) {
                    return;
                }
                if (row != null) {
                    RedisManager.get().save(cacheKeyPrefix + id, row);
                } else {
                    evictFromCache(id);
                }
            } finally {
                lock.unlock();
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
        if (item.getType() != DatabaseAction.Type.DELETE || id == null) {
            return;
        }
        ReentrantLock lock = cacheLock(id);
        lock.lock();
        try {
            if (!hasLaterAction(id, batch, index)) {
                evictQuietly(id);
            }
        } finally {
            lock.unlock();
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
        /**
         * Refused against other rows (foreign key, unique, a related entity with no row), or for
         * a reason not known to be the database's unavailability: may pass once other writes
         * commit, and is dropped after {@link #MAX_CONSTRAINT_RETRIES} attempts otherwise.
         */
        RETRYABLE
    }

    /**
     * How the write was refused, or null when the database could not process it at all (see
     * {@link DatabaseFailures#isUnavailable}): such a write is fine and waits for the database, however long
     * that takes. Any other failure is bounded, or one write would block every later write of
     * this type (and {@link #executeDelete}) for good.
     */
    private static Rejection rejection(Throwable e) {
        if (DatabaseFailures.isUnavailable(e)) {
            return null;
        }
        // Foreign keys and unique keys (each repository flushes on its own: a child's delete
        // queued in another repository may commit after its parent's, which fails until then),
        // but also a relation pointing to a row deleted meanwhile (EntityNotFoundException) or to
        // an entity no longer persistent: the other rows may still change, so the write gets a
        // few more attempts.
        return DatabaseFailures.isPermanentRejection(e) ? Rejection.PERMANENT : Rejection.RETRYABLE;
    }

    /** Whether the failure is an optimistic-lock one: the entity's row was deleted or changed meanwhile. */
    private static boolean isStaleRow(Throwable e) {
        return DatabaseFailures.isStaleRow(e);
    }

    @Override
    public List<T> all() {
        attach();
        return loadAll();
    }

    /** The whole table: from the cache, or from the database into the cache if it is empty. */
    private List<T> loadAll() {
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
            cacheRows(entities);
            return entities;
        } else {
            return new ArrayList<>();
        }
    }

    /** Caches rows read from the database, keeping any state cached meanwhile (see {@link #loadCache}). */
    private void cacheRows(List<T> rows) {
        Map<String, T> byKey = new LinkedHashMap<>();
        for (T row : rows) {
            byKey.put(cacheKeyPrefix + row.getId(), row);
        }
        RedisManager.get().saveAllIfAbsent(byKey);
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
        attach();
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
                    RedisManager.get().saveIfAbsent(cacheKeyPrefix + ie.getId(), entity);
                }
            }
        }
        return dbResults;
    }

    @Override
    protected long executeCount(QueryBuilder<T> builder) {
        attach();
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
        attach();
        if (!RedisManager.get().isReceiver()) {
            return deleteMatching(builder);
        }
        // Held throughout, so the flush worker cannot commit queued writes between the flush
        // below and the delete.
        flushLock.lock();
        try {
            // The delete runs against the database, but rows are matched on the cache, which
            // already holds the queued writes: apply them first, or the delete misses rows the
            // cache matched and a queued write then brings a deleted row back.
            flushPending();
            if (!retryQueue.isEmpty()) {
                // Writes the database did not take yet (connection lost, constraint waiting for
                // another write): deleting now would test the conditions against rows they are
                // about to change, and the retried writes could bring deleted rows back.
                throw new IllegalStateException("Cannot delete " + type.getSimpleName() + " rows while "
                        + retryQueue.size() + " queued write(s) wait to be retried; try again later");
            }
            return deleteMatching(builder);
        } finally {
            flushLock.unlock();
        }
    }

    private int deleteMatching(QueryBuilder<T> builder) {
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
            // Misses are not cached, so that names absent from the class cannot grow the map
            // without bound.
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
