package sh.fyz.architect.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.annotations.SortComparator;
import org.hibernate.proxy.HibernateProxy;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;
import sh.fyz.architect.persistent.SessionManager;

import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class RedisManager {

    private static final Logger LOG = Logger.getLogger(RedisManager.class.getName());
    private static volatile RedisManager instance;
    private static final Object LOCK = new Object();

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private RedisQueueActionPool redisQueueActionPool;
    private final RedisClient client;
    private final ObjectMapper objectMapper;
    private final ExecutorService pubSubExecutor = Executors.newVirtualThreadPerTaskExecutor();
    // Async repository calls of an instance without a database, which has no SessionManager
    // thread pool: owned here so that shutdown waits for them while Redis is still open.
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private static final ConcurrentHashMap<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    // Optional because ConcurrentHashMap cannot hold null, and "no @Id field" must be cached too.
    private static final ConcurrentHashMap<Class<?>, Optional<Field>> ID_FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * A relation of a rebuilt entity. Its targets are in id order, null where not found in Redis,
     * and {@code ids} holds the ids of those, converted to the {@code @Id} field's type;
     * {@code collection} is null for a single-valued relation.
     */
    private record PendingRelation(Object owner, Field field, Class<?> type, List<?> rawIds,
                                   Object[] targets, Object[] ids, Collection<Object> collection) {

        String key(int index) {
            return type.getSimpleName() + ":" + rawIds.get(index);
        }

        /** Sets the field, leaving out targets found nowhere. */
        void assign() throws IllegalAccessException {
            if (collection == null) {
                field.set(owner, targets[0]);
                return;
            }
            for (Object target : targets) {
                if (target != null) {
                    collection.add(target);
                }
            }
            field.set(owner, collection);
        }
    }

    /** Channel name → its subscriber, so each channel is subscribed once per instance and unsubscribed on shutdown. */
    private final Map<String, EntityChannelPubSub<?>> subscriptions = new ConcurrentHashMap<>();
    private final HostAndPort address;
    private final JedisClientConfig clientConfig;

    private final boolean isReceiver;
    private volatile boolean isAlive = true;
    private final String keyPrefix;
    private final int defaultTtlSeconds;

    private RedisManager(String host, String password, int port, int timeout, int maxConnections,
                          boolean receiver, int defaultTtlSeconds) {
        this.address = new HostAndPort(host, port);
        this.clientConfig = DefaultJedisClientConfig.builder()
                .timeoutMillis(timeout)
                .password(password)
                .build();
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(maxConnections);
        // At least one idle connection: with maxIdle 0 every returned connection is closed and
        // the next call pays a new TCP connect + AUTH.
        poolConfig.setMaxIdle(Math.max(1, maxConnections / 2));
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTimeBetweenEvictionRuns(java.time.Duration.ofSeconds(30));
        this.client = RedisClient.builder()
                .hostAndPort(address)
                .clientConfig(clientConfig)
                .poolConfig(poolConfig)
                .build();
        this.keyPrefix = "architect:";
        this.defaultTtlSeconds = defaultTtlSeconds;
        if (receiver) {
            try {
                clearArchitectKeys();
            } catch (RuntimeException e) {
                // The constructor throws, so nobody else will ever close this client.
                client.close();
                throw e;
            }
        }
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // java.time values are written as ISO-8601 strings, which convertValue reads back.
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        // Cached JSON is read into a Map first: decimals must not pass through double (BigDecimal
        // amounts would be rounded), and offsets/zones must not be rewritten to UTC.
        this.objectMapper.configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
        this.objectMapper.configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false);
        this.isReceiver = receiver;
    }

    private void clearArchitectKeys() {
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams().match(keyPrefix + "*").count(1000);
        do {
            ScanResult<String> scan = client.scan(cursor, params);
            List<String> keys = scan.getResult();
            if (!keys.isEmpty()) {
                client.del(keys.toArray(new String[0]));
            }
            cursor = scan.getCursor();
        } while (!"0".equals(cursor));
    }

    private void createRedisPool() {
        this.redisQueueActionPool = new RedisQueueActionPool(isReceiver);
    }

    public boolean isReceiver() {
        return isReceiver;
    }

    /**
     * The Redis client Architect uses, with its connection pool. Architect's own keys are
     * prefixed {@code architect:}; the client does not add the prefix.
     */
    public RedisClient getRedisClient() {
        return client;
    }

    /** Runs the async repository calls of an instance without a database. */
    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public ExecutorService getPubSubExecutor() {
        return pubSubExecutor;
    }

    /**
     * A client of its own, outside the command pool, for a pub/sub subscription. A subscribed
     * connection is blocked for as long as the subscription lives; taking it from the command
     * pool would permanently shrink the pool by one per relayed entity type, and exhaust it once
     * there are as many types as {@code maxConnections} — every later Redis call would then wait
     * forever.
     */
    RedisClient openSubscriberClient() {
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(1);
        return RedisClient.builder()
                .hostAndPort(address)
                .clientConfig(clientConfig)
                .poolConfig(poolConfig)
                .build();
    }

    /**
     * Records a channel's subscriber. Returns false when the channel already has one on this
     * instance. Scoped to the instance rather than static, so that after {@code stop()} /
     * {@code start()} relay repositories subscribe again.
     */
    boolean registerSubscription(String channel, EntityChannelPubSub<?> subscriber) {
        return subscriptions.putIfAbsent(channel, subscriber) == null;
    }

    public RedisQueueActionPool getRedisQueueActionPool() {
        return redisQueueActionPool;
    }

    public static void initialize(String host, String password, int port, int timeout, int maxConnections,
                                   boolean receiver) {
        initialize(host, password, port, timeout, maxConnections, receiver, 0);
    }

    public static void initialize(String host, String password, int port, int timeout, int maxConnections,
                                   boolean receiver, int defaultTtlSeconds) {
        synchronized (LOCK) {
            if (instance == null) {
                instance = new RedisManager(host, password, port, timeout, maxConnections, receiver, defaultTtlSeconds);
                instance.createRedisPool();
            } else {
                throw new IllegalStateException("RedisManager is already initialized!");
            }
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            if (instance != null) {
                try {
                    instance.shutdown();
                } finally {
                    // Cleared even if shutdown failed, so that a later initialize() is not refused.
                    instance = null;
                }
            }
        }
    }

    public boolean isAlive() {
        return isAlive;
    }

    public static RedisManager get() {
        RedisManager local = instance;
        if (local == null) {
            throw new IllegalStateException("RedisManager is not initialized! Call initialize() first.");
        }
        return local;
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    public int getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public <T> void save(String key, T entity) {
        save(key, entity, false);
    }

    /**
     * Caches the entity unless the key already holds one (SET NX): for a state read from the
     * database, which a state cached meanwhile may be newer than.
     */
    public <T> void saveIfAbsent(String key, T entity) {
        save(key, entity, true);
    }

    /**
     * {@link #saveIfAbsent} for many entities, by key, pipelined: loading a table one round trip
     * per row takes seconds per ten thousand rows.
     */
    public void saveAllIfAbsent(Map<String, ?> entities) {
        try {
            SetParams params = SetParams.setParams().nx();
            if (defaultTtlSeconds > 0) {
                params.ex(defaultTtlSeconds);
            }
            Iterator<? extends Map.Entry<String, ?>> it = entities.entrySet().iterator();
            while (it.hasNext()) {
                // In chunks, so that neither side buffers a whole table's replies.
                try (Pipeline pipeline = client.pipelined()) {
                    List<Response<String>> replies = new ArrayList<>();
                    for (int n = 0; n < 1000 && it.hasNext(); n++) {
                        Map.Entry<String, ?> entry = it.next();
                        replies.add(pipeline.set(keyPrefix + entry.getKey(),
                                objectMapper.writeValueAsString(prepareForSave(entry.getValue())), params));
                    }
                    pipeline.sync();
                    // sync() does not report a refused write (out of memory, read-only replica):
                    // get() throws it, or a partly loaded cache would pass for the whole table.
                    for (Response<String> reply : replies) {
                        reply.get();
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save entities to Redis: " + e.getMessage(), e);
        }
    }

    private <T> void save(String key, T entity, boolean ifAbsent) {
        try {
            Map<String, Object> processedEntity = prepareForSave(entity);
            String prefixedKey = keyPrefix + key;
            String value = objectMapper.writeValueAsString(processedEntity);
            SetParams params = SetParams.setParams();
            if (ifAbsent) {
                params.nx();
            }
            if (defaultTtlSeconds > 0) {
                params.ex(defaultTtlSeconds);
            }
            client.set(prefixedKey, value, params);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save entity to Redis: " + e.getMessage(), e);
        }
    }

    public <T> T find(String key, Class<T> type) {
        List<PendingRelation> pending = new ArrayList<>();
        T entity = find(key, type, new HashMap<>(), pending);
        resolvePending(pending);
        return entity;
    }

    /**
     * Rebuilds a cached entity, adding its relations to {@code pending}, shared by every entity
     * rebuilt in one {@link #find} or {@link #findAll} call and set at its end by
     * {@link #resolvePending}.
     *
     * @param inProgress entities of the graph being rebuilt, by full key. A relation pointing back
     *                   to one of them (Owner → Pet → Owner) reuses that instance instead of
     *                   rebuilding it forever.
     */
    private <T> T find(String key, Class<T> type, Map<String, Object> inProgress,
                      List<PendingRelation> pending) {
        String fullKey = keyPrefix + key;
        Object existing = inProgress.get(fullKey);
        if (type.isInstance(existing)) {
            return type.cast(existing);
        }
        try {
            // Each command borrows a pooled connection only while it runs: resolving relations
            // below looks up further keys without holding one per nesting level.
            String data = client.get(fullKey);
            if (data == null) {
                return null;
            }
            Map<String, Object> rawData = objectMapper.readValue(data, MAP_TYPE_REF);
            return reconstructEntity(rawData, type, fullKey, inProgress, pending);
        } catch (Exception e) {
            throw new RuntimeException("Failed to find entity in Redis: " + e.getMessage(), e);
        }
    }

    public <T> List<T> findAll(String pattern, Class<T> type) {
        // Fetched first and reconstructed after the connection is back in the pool: rebuilding
        // relations performs lookups of its own (see find).
        Map<String, String> entries = new LinkedHashMap<>();
        try {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(keyPrefix + pattern).count(1000);
            do {
                ScanResult<String> scan = client.scan(cursor, params);
                List<String> keys = scan.getResult();
                if (!keys.isEmpty()) {
                    try (Pipeline pipeline = client.pipelined()) {
                        List<Response<String>> responses = new ArrayList<>(keys.size());
                        for (String key : keys) {
                            responses.add(pipeline.get(key));
                        }
                        pipeline.sync();
                        for (int i = 0; i < keys.size(); i++) {
                            String data = responses.get(i).get();
                            if (data != null) {
                                entries.put(keys.get(i), data);
                            }
                        }
                    }
                }
                cursor = scan.getCursor();
            } while (!"0".equals(cursor));
        } catch (Exception e) {
            throw new RuntimeException("Failed to find all entities in Redis: " + e.getMessage(), e);
        }

        List<T> result = new ArrayList<>(entries.size());
        List<PendingRelation> pending = new ArrayList<>();
        // Shared by the whole call: an entity already rebuilt as another's relation (every Pet
        // pointing to its Owner, and the Owner to every Pet) is reused, not rebuilt with its
        // whole graph once per entity, which costs one Redis GET per node each time.
        List<String> rebuiltKeys = new ArrayList<>();
        Map<String, Object> rebuilt = new HashMap<>() {
            @Override
            public Object put(String key, Object value) {
                Object previous = super.put(key, value);
                if (previous == null) {
                    rebuiltKeys.add(key);
                }
                return previous;
            }
        };
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            Object existing = rebuilt.get(entry.getKey());
            if (type.isInstance(existing)) {
                result.add(type.cast(existing));
                continue;
            }
            int keysBefore = rebuiltKeys.size();
            int pendingBefore = pending.size();
            try {
                Map<String, Object> rawData = objectMapper.readValue(entry.getValue(), MAP_TYPE_REF);
                T entity = reconstructEntity(rawData, type, entry.getKey(), rebuilt, pending);
                if (entity != null) result.add(entity);
            } catch (Exception e) {
                // Everything this entity's graph rebuilt is discarded: an entity rebuilt along the
                // way may point to one left half-built by the failure, and must not be returned.
                List<String> discarded = rebuiltKeys.subList(keysBefore, rebuiltKeys.size());
                discarded.forEach(rebuilt::remove);
                discarded.clear();
                pending.subList(pendingBefore, pending.size()).clear();
                LOG.warning("Failed to deserialize cached entity: " + e.getMessage());
            }
        }
        // Outside the loop: a database failure is not a corrupt entry, and skipping the entities
        // it concerns would return a partial result as if complete.
        resolvePending(pending);
        return result;
    }

    public void delete(String key) {
        try {
            client.del(keyPrefix + key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete key from Redis: " + e.getMessage(), e);
        }
    }

    private Map<String, Field> getCachedFields(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> fieldMap = new LinkedHashMap<>();
            Class<?> current = c;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    // Static fields (serialVersionUID, constants, loggers) are not entity state:
                    // serialising them bloats every entry, and writing a static final one back
                    // makes reconstructEntity throw. Synthetic fields are compiler artefacts.
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        continue;
                    }
                    field.setAccessible(true);
                    fieldMap.putIfAbsent(field.getName(), field);
                }
                current = current.getSuperclass();
            }
            return Collections.unmodifiableMap(fieldMap);
        });
    }

    private <T> Map<String, Object> prepareForSave(T entity) throws IllegalAccessException {
        Map<String, Object> jsonMap = new HashMap<>();
        Map<String, Field> fields = getCachedFields(entity.getClass());

        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            Field field = entry.getValue();
            Object value = field.get(entity);

            if (value != null) {
                if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                    // A related entity never saved has no id yet: nothing to point to.
                    Object relatedId = value instanceof HibernateProxy || getIdField(value.getClass()) != null
                            ? idOf(value) : null;
                    if (relatedId != null) {
                        jsonMap.put(field.getName() + "_id", relatedId);
                    }
                } else if (field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class)) {
                    if (value instanceof Collection) {
                        List<Object> ids = new ArrayList<>();
                        for (Object item : (Collection<?>) value) {
                            // Nor an element never saved: a null id would fail every read of this
                            // entity, and every query of its type.
                            Object itemId = item instanceof HibernateProxy || (item != null && getIdField(item.getClass()) != null)
                                    ? idOf(item) : null;
                            if (itemId != null) {
                                ids.add(itemId);
                            }
                        }
                        if (!ids.isEmpty()) {
                            jsonMap.put(field.getName() + "_ids", ids);
                        }
                    }
                } else {
                    jsonMap.put(field.getName(), value);
                }
            }
        }
        return jsonMap;
    }

    /**
     * The id of a related entity. A lazy association holds an uninitialized Hibernate proxy,
     * whose own fields are all null: its id is only known to the proxy's initializer.
     */
    private Object idOf(Object related) throws IllegalAccessException {
        if (related instanceof HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getIdentifier();
        }
        return getIdField(related.getClass()).get(related);
    }

    /**
     * Resolves a relation's targets from Redis; those not there (their type is not cached, their
     * key expired...) are read from the database by {@link #resolvePending}. Every relation
     * waits in {@code pending} to be set, even a complete one: a {@code HashSet} files its
     * elements by hashCode, which may depend on their relations, so the collections are filled
     * once every single-valued relation of the call is set.
     */
    private void relate(Object owner, Field field, Class<?> type, List<?> rawIds, Collection<Object> collection,
                        Map<String, Object> inProgress, List<PendingRelation> pending) {
        PendingRelation relation = new PendingRelation(owner, field, type, rawIds,
                new Object[rawIds.size()], new Object[rawIds.size()], collection);
        Field idField = getIdField(type);
        for (int i = 0; i < rawIds.size(); i++) {
            relation.targets()[i] = find(relation.key(i), type, inProgress, pending);
            if (relation.targets()[i] == null) {
                // Converted here, where an unreadable id only fails this entity (JSON reads a
                // Long id back as an Integer).
                Object rawId = rawIds.get(i);
                relation.ids()[i] = idField == null ? rawId : objectMapper.convertValue(rawId, idField.getType());
            }
        }
        pending.add(relation);
    }

    /**
     * Reads the targets missing from Redis from the database, then sets the pending relations:
     * single-valued ones first, then collections (see {@link #relate}).
     * Leaving those targets out would not only return a wrong entity: saving it back would clear
     * the foreign key or the collection's rows. Many cached entities typically point to the same
     * uncached ones (every Pet to its Owner), so each type is read in one query for the whole
     * call, and each row once. Targets found nowhere, or not in Redis on an instance without a
     * database, are left out.
     */
    private void resolvePending(List<PendingRelation> pending) {
        if (pending.isEmpty()) {
            return;
        }
        // Type → key → id, for the targets missing from Redis.
        Map<Class<?>, Map<String, Object>> missing = new LinkedHashMap<>();
        for (PendingRelation relation : pending) {
            for (int i = 0; i < relation.targets().length; i++) {
                if (relation.targets()[i] == null) {
                    missing.computeIfAbsent(relation.type(), t -> new LinkedHashMap<>())
                            .putIfAbsent(relation.key(i), relation.ids()[i]);
                }
            }
        }
        if (!missing.isEmpty() && SessionManager.isInitialized()) {
            // Key → row, null for no row.
            Map<String, Object> loaded = new HashMap<>();
            try (Session session = SessionManager.get().getSession()) {
                session.setDefaultReadOnly(true);
                for (Map.Entry<Class<?>, Map<String, Object>> entry : missing.entrySet()) {
                    List<String> keys = new ArrayList<>(entry.getValue().keySet());
                    // Same order as the ids, null for a missing row.
                    List<?> rows = session.findMultiple(entry.getKey(), new ArrayList<>(entry.getValue().values()));
                    for (int k = 0; k < keys.size(); k++) {
                        Object row = rows.get(k);
                        if (row != null) {
                            initializeCollections(row);
                        }
                        loaded.put(keys.get(k), row);
                    }
                }
            } catch (RuntimeException e) {
                throw new RuntimeException("Failed to load related "
                        + missing.keySet().stream().map(Class::getSimpleName).toList()
                        + " entities from the database: " + e.getMessage(), e);
            }
            for (PendingRelation relation : pending) {
                for (int i = 0; i < relation.targets().length; i++) {
                    if (relation.targets()[i] == null) {
                        relation.targets()[i] = loaded.get(relation.key(i));
                    }
                }
            }
        }
        try {
            for (PendingRelation relation : pending) {
                if (relation.collection() == null) {
                    relation.assign();
                }
            }
            for (PendingRelation relation : pending) {
                if (relation.collection() != null) {
                    relation.assign();
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot set a relation of a cached entity", e);
        }
    }

    private <T> T reconstructEntity(Map<String, Object> rawData, Class<T> type,
                                    String fullKey, Map<String, Object> inProgress,
                                    List<PendingRelation> pending) {
        try {
            T entity = type.getDeclaredConstructor().newInstance();
            inProgress.put(fullKey, entity);
            Map<String, Field> fields = getCachedFields(type);

            for (Map.Entry<String, Field> entry : fields.entrySet()) {
                Field field = entry.getValue();
                String fieldName = field.getName();

                if (rawData.containsKey(fieldName)) {
                    Object value = rawData.get(fieldName);
                    value = convertValue(value, field);
                    field.set(entity, value);
                } else if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                    Object idValue = rawData.get(fieldName + "_id");
                    if (idValue != null) {
                        relate(entity, field, field.getType(), List.of(idValue), null, inProgress, pending);
                    }
                } else if (field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class)) {
                    List<?> ids = (List<?>) rawData.get(fieldName + "_ids");
                    if (ids != null && !ids.isEmpty()) {
                        Class<?> genericType = getGenericType(field);
                        if (genericType != null) {
                            relate(entity, field, genericType, ids, newCollection(field, genericType),
                                    inProgress, pending);
                        }
                    }
                }
            }
            return entity;
        } catch (Exception e) {
            throw new RuntimeException("Failed to reconstruct entity of type " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Loads the lazy collections of an entity read from the database while its session is open,
     * like a repository's own database reads do: saving it to Redis later reads them.
     */
    private void initializeCollections(Object entity) {
        try {
            for (Field field : getCachedFields(entity.getClass()).values()) {
                if (field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class)
                        || field.isAnnotationPresent(ElementCollection.class)) {
                    Object value = field.get(entity);
                    if (value != null && !Hibernate.isInitialized(value)) {
                        Hibernate.initialize(value);
                    }
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read the collections of " + entity.getClass().getSimpleName(), e);
        }
    }

    /**
     * An empty collection the field can hold, sorted like Hibernate sorts it. Checked while the
     * entity is rebuilt, so that a field it cannot fill fails this entity only, not the whole
     * {@link #findAll}: the elements are added once every entity is rebuilt.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Collection<Object> newCollection(Field field, Class<?> elementType) {
        Class<?> type = field.getType();
        if (type.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<>();
        }
        // Linked: keeps the order the ids were cached in.
        if (type.isAssignableFrom(LinkedHashSet.class)) {
            return new LinkedHashSet<>();
        }
        if (type.isAssignableFrom(TreeSet.class)) {
            SortComparator sortComparator = field.getAnnotation(SortComparator.class);
            if (sortComparator != null) {
                try {
                    return new TreeSet<>((Comparator) sortComparator.value().getDeclaredConstructor().newInstance());
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot create the comparator of " + field.getName(), e);
                }
            }
            if (!Comparable.class.isAssignableFrom(elementType)) {
                throw new IllegalStateException(field.getName() + " is sorted, but " + elementType.getSimpleName()
                        + " is not Comparable and no @SortComparator is set");
            }
            return new TreeSet<>();
        }
        throw new IllegalStateException("Unsupported collection type " + type.getName() + " for " + field.getName());
    }

    private Class<?> getGenericType(Field field) {
        try {
            Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType parameterizedType) {
                Type[] typeArgs = parameterizedType.getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> clazz) {
                    return clazz;
                }
            }
        } catch (Exception e) {
            LOG.warning("Failed to resolve generic type for field " + field.getName() + ": " + e.getMessage());
        }
        return null;
    }

    private Object convertValue(Object value, Field field) {
        if (value == null) {
            return null;
        }

        Class<?> targetType = field.getType();
        // Collections and maps are always rebuilt with their declared element types: read back
        // as plain JSON, the elements of a List<Long> are Integers and those of a List<Double>
        // BigDecimals (see USE_BIG_DECIMAL_FOR_FLOATS).
        boolean container = value instanceof Collection<?> || value instanceof Map<?, ?>;
        if (!container && targetType.isAssignableFrom(value.getClass())) {
            return value;
        }

        if (targetType == Long.class || targetType == long.class) {
            if (value instanceof Integer) {
                return ((Integer) value).longValue();
            }
            if (value instanceof String) {
                return Long.parseLong((String) value);
            }
        }

        if (targetType == Integer.class || targetType == int.class) {
            if (value instanceof Long) {
                return ((Long) value).intValue();
            }
            if (value instanceof String) {
                return Integer.parseInt((String) value);
            }
        }

        if (targetType == UUID.class && value instanceof String) {
            return UUID.fromString((String) value);
        }

        if (targetType.isPrimitive() && MethodType.methodType(targetType).wrap().returnType().isInstance(value)) {
            return value;
        }

        // Everything JSON has no native type for (enums, java.time, BigDecimal, float, short,
        // arrays, embeddables, typed collections...) comes back from Redis as a String, Number,
        // List or Map, which Field.set would reject or the caller could not use.
        return objectMapper.convertValue(value, objectMapper.getTypeFactory().constructType(field.getGenericType()));
    }

    private Field getIdField(Class<?> clazz) {
        return ID_FIELD_CACHE.computeIfAbsent(clazz, c -> {
            for (Field field : getCachedFields(c).values()) {
                if (field.isAnnotationPresent(Id.class)) {
                    return Optional.of(field);
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    public void setTTL(String key, int seconds) {
        try {
            client.expire(keyPrefix + key, seconds);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set TTL on key: " + e.getMessage(), e);
        }
    }

    public void shutdown() {
        // Cleared for the waits below, restored at the end: called from a thread already
        // interrupted, every wait would throw at once, cutting off the async calls and skipping
        // the final flush (see RedisQueueActionPool.shutdown).
        boolean interrupted = Thread.interrupted();
        // Async calls still queued or running write to Redis: let them finish first.
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            interrupted = true;
            asyncExecutor.shutdownNow();
        }
        isAlive = false;
        // Unblocks each subscriber's blocking subscribe() so its thread can exit; otherwise
        // shutdown waits the full 5 s below and the subscriber thread outlives the manager.
        for (EntityChannelPubSub<?> subscriber : subscriptions.values()) {
            subscriber.unsubscribe();
        }
        try {
            // Subscribers first: unsubscribe() is asynchronous, and a message a subscriber still
            // reads would be queued after the final drain of the relayed actions, and lost.
            pubSubExecutor.shutdown();
            try {
                if (!pubSubExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    pubSubExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                interrupted = true;
                pubSubExecutor.shutdownNow();
            }
            if (redisQueueActionPool != null) {
                redisQueueActionPool.shutdown();
            }
        } finally {
            // Even if the final flush threw: the connections go anyway.
            client.close();
            if (interrupted || Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
