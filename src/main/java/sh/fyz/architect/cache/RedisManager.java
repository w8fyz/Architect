package sh.fyz.architect.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import org.hibernate.Session;
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

    private static final ConcurrentHashMap<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    // Optional because ConcurrentHashMap cannot hold null, and "no @Id field" must be cached too.
    private static final ConcurrentHashMap<Class<?>, Optional<Field>> ID_FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * A related entity could not be read from the database. Not a corrupt cache entry: findAll
     * must not skip the entity (and return a partial result as if complete), it rethrows.
     */
    private static final class RelatedLoadException extends RuntimeException {
        RelatedLoadException(String message, Throwable cause) {
            super(message, cause);
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
        try {
            Map<String, Object> processedEntity = prepareForSave(entity);
            String prefixedKey = keyPrefix + key;
            String value = objectMapper.writeValueAsString(processedEntity);
            if (defaultTtlSeconds > 0) {
                client.set(prefixedKey, value, SetParams.setParams().ex(defaultTtlSeconds));
            } else {
                client.set(prefixedKey, value);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save entity to Redis: " + e.getMessage(), e);
        }
    }

    public <T> T find(String key, Class<T> type) {
        return find(key, type, new HashMap<>(), new HashMap<>());
    }

    /**
     * @param loaded     related entities read from the database (see {@link #findRelated}), by
     *                   full key, shared by every entity rebuilt in one call
     * @param inProgress entities of the graph being rebuilt, by full key. A relation pointing back
     *                   to one of them (Owner → Pet → Owner) reuses that instance instead of
     *                   rebuilding it forever.
     */
    private <T> T find(String key, Class<T> type, Map<String, Object> inProgress, Map<String, Object> loaded) {
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
            return reconstructEntity(rawData, type, fullKey, inProgress, loaded);
        } catch (RelatedLoadException e) {
            throw e;
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
        // Many cached entities typically point to the same uncached one (every Pet to its Owner):
        // read each from the database once per call, not once per entity.
        Map<String, Object> loaded = new HashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            try {
                Map<String, Object> rawData = objectMapper.readValue(entry.getValue(), MAP_TYPE_REF);
                T entity = reconstructEntity(rawData, type, entry.getKey(), new HashMap<>(), loaded);
                if (entity != null) result.add(entity);
            } catch (RelatedLoadException e) {
                throw e;
            } catch (Exception e) {
                LOG.warning("Failed to deserialize cached entity: " + e.getMessage());
            }
        }
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
                    if (value instanceof HibernateProxy || getIdField(value.getClass()) != null) {
                        jsonMap.put(field.getName() + "_id", idOf(value));
                    }
                } else if (field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class)) {
                    if (value instanceof Collection) {
                        List<Object> ids = new ArrayList<>();
                        for (Object item : (Collection<?>) value) {
                            if (item instanceof HibernateProxy || (item != null && getIdField(item.getClass()) != null)) {
                                ids.add(idOf(item));
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
     * Related entities, in the order of their ids. Those not in Redis (their type is not cached,
     * their key expired...) are read from the database instead, in one query per call: leaving
     * them out would not only return a wrong entity, saving it back would clear the foreign key
     * or the collection's rows. Ids found nowhere, or not in Redis on an instance without a
     * database, are left out.
     */
    private List<Object> findRelated(Class<?> type, List<?> rawIds, Map<String, Object> inProgress,
                                     Map<String, Object> loaded) {
        Object[] found = new Object[rawIds.size()];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < rawIds.size(); i++) {
            String key = type.getSimpleName() + ":" + rawIds.get(i);
            Object cached = find(key, type, inProgress, loaded);
            if (cached != null) {
                found[i] = cached;
            } else if (loaded.containsKey(key)) {
                found[i] = loaded.get(key);
            } else {
                missing.add(i);
            }
        }
        if (!missing.isEmpty() && SessionManager.isInitialized()) {
            Field idField = getIdField(type);
            List<Object> ids = new ArrayList<>(missing.size());
            for (int i : missing) {
                Object rawId = rawIds.get(i);
                ids.add(idField == null ? rawId : objectMapper.convertValue(rawId, idField.getType()));
            }
            List<?> rows;
            try (Session session = SessionManager.get().getSession()) {
                session.setDefaultReadOnly(true);
                // Same order as the ids, null for a missing row.
                rows = session.findMultiple(type, ids);
            } catch (RuntimeException e) {
                throw new RelatedLoadException("Failed to load " + type.getSimpleName() + " " + ids
                        + " from the database: " + e.getMessage(), e);
            }
            for (int k = 0; k < missing.size(); k++) {
                int i = missing.get(k);
                found[i] = rows.get(k);
                loaded.put(type.getSimpleName() + ":" + rawIds.get(i), rows.get(k));
            }
        }
        List<Object> result = new ArrayList<>(found.length);
        for (Object entity : found) {
            if (entity != null) {
                result.add(entity);
            }
        }
        return result;
    }

    private <T> T reconstructEntity(Map<String, Object> rawData, Class<T> type,
                                    String fullKey, Map<String, Object> inProgress,
                                    Map<String, Object> loaded) {
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
                        List<Object> related = findRelated(field.getType(), List.of(idValue), inProgress, loaded);
                        field.set(entity, related.isEmpty() ? null : related.get(0));
                    }
                } else if (field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class)) {
                    List<?> ids = (List<?>) rawData.get(fieldName + "_ids");
                    if (ids != null && !ids.isEmpty()) {
                        Collection<Object> relatedEntities;
                        if (List.class.isAssignableFrom(field.getType())) {
                            relatedEntities = new ArrayList<>();
                        } else {
                            relatedEntities = new HashSet<>();
                        }

                        Class<?> genericType = getGenericType(field);
                        if (genericType != null) {
                            relatedEntities.addAll(findRelated(genericType, ids, inProgress, loaded));
                            field.set(entity, relatedEntities);
                        }
                    }
                }
            }
            return entity;
        } catch (RelatedLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to reconstruct entity of type " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
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
        isAlive = false;
        // Unblocks each subscriber's blocking subscribe() so its thread can exit; otherwise
        // shutdown waits the full 5 s below and the subscriber thread outlives the manager.
        for (EntityChannelPubSub<?> subscriber : subscriptions.values()) {
            subscriber.unsubscribe();
        }
        if (redisQueueActionPool != null) {
            redisQueueActionPool.shutdown();
        }
        pubSubExecutor.shutdown();
        try {
            if (!pubSubExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                pubSubExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pubSubExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        client.close();
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
