package sh.fyz.architect.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

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
    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;
    private final ExecutorService pubSubExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private static final ConcurrentHashMap<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    // Optional because ConcurrentHashMap cannot hold null, and "no @Id field" must be cached too.
    private static final ConcurrentHashMap<Class<?>, Optional<Field>> ID_FIELD_CACHE = new ConcurrentHashMap<>();

    /** Channel name → its subscriber, so each channel is subscribed once per instance and unsubscribed on shutdown. */
    private final Map<String, EntityChannelPubSub<?>> subscriptions = new ConcurrentHashMap<>();
    private final HostAndPort address;
    private final JedisClientConfig subscriberConfig;

    private final boolean isReceiver;
    private volatile boolean isAlive = true;
    private final String keyPrefix;
    private final int defaultTtlSeconds;

    private RedisManager(String host, String password, int port, int timeout, int maxConnections,
                          boolean receiver, int defaultTtlSeconds) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(maxConnections);
        // At least one idle connection: with maxIdle 0 every returned connection is closed and
        // the next call pays a new TCP connect + AUTH.
        config.setMaxIdle(Math.max(1, maxConnections / 2));
        config.setMinIdle(1);
        config.setTestOnBorrow(true);
        config.setTimeBetweenEvictionRuns(java.time.Duration.ofSeconds(30));
        this.jedisPool = new JedisPool(config, host, port, timeout, password);
        this.address = new HostAndPort(host, port);
        this.subscriberConfig = DefaultJedisClientConfig.builder()
                .timeoutMillis(timeout)
                .password(password)
                .build();
        this.keyPrefix = "architect:";
        this.defaultTtlSeconds = defaultTtlSeconds;
        if (receiver) {
            try {
                clearArchitectKeys();
            } catch (RuntimeException e) {
                // The constructor throws, so nobody else will ever close this pool.
                jedisPool.close();
                throw e;
            }
        }
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        this.isReceiver = receiver;
    }

    private void clearArchitectKeys() {
        try (Jedis jedis = jedisPool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(keyPrefix + "*").count(1000);
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                List<String> keys = scan.getResult();
                if (!keys.isEmpty()) {
                    jedis.del(keys.toArray(new String[0]));
                }
                cursor = scan.getCursor();
            } while (!"0".equals(cursor));
        }
    }

    private void createRedisPool() {
        this.redisQueueActionPool = new RedisQueueActionPool(isReceiver);
    }

    public boolean isReceiver() {
        return isReceiver;
    }

    public JedisPool getJedisPool() {
        return jedisPool;
    }

    public ExecutorService getPubSubExecutor() {
        return pubSubExecutor;
    }

    /**
     * A connection outside the pool, for a pub/sub subscription. A subscribed connection is
     * blocked for as long as the subscription lives; taking it from the pool would permanently
     * shrink the pool by one per relayed entity type, and exhaust it once there are as many
     * types as {@code maxConnections} — every later Redis call would then wait forever.
     */
    Jedis openSubscriberConnection() {
        return new Jedis(address, subscriberConfig);
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
                instance.shutdown();
                instance = null;
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
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, Object> processedEntity = prepareForSave(entity);
            String prefixedKey = keyPrefix + key;
            String value = objectMapper.writeValueAsString(processedEntity);
            if (defaultTtlSeconds > 0) {
                jedis.set(prefixedKey, value, SetParams.setParams().ex(defaultTtlSeconds));
            } else {
                jedis.set(prefixedKey, value);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save entity to Redis: " + e.getMessage(), e);
        }
    }

    public <T> T find(String key, Class<T> type) {
        return find(key, type, new HashMap<>());
    }

    /**
     * @param inProgress entities of the graph being rebuilt, by full key. A relation pointing back
     *                   to one of them (Owner → Pet → Owner) reuses that instance instead of
     *                   rebuilding it forever.
     */
    private <T> T find(String key, Class<T> type, Map<String, Object> inProgress) {
        String fullKey = keyPrefix + key;
        Object existing = inProgress.get(fullKey);
        if (type.isInstance(existing)) {
            return type.cast(existing);
        }
        try {
            // The connection is released before reconstructing: resolving relations looks up
            // further keys, and holding one connection per nesting level can drain the pool.
            String data;
            try (Jedis jedis = jedisPool.getResource()) {
                data = jedis.get(fullKey);
            }
            if (data == null) {
                return null;
            }
            Map<String, Object> rawData = objectMapper.readValue(data, MAP_TYPE_REF);
            return reconstructEntity(rawData, type, fullKey, inProgress);
        } catch (Exception e) {
            throw new RuntimeException("Failed to find entity in Redis: " + e.getMessage(), e);
        }
    }

    public <T> List<T> findAll(String pattern, Class<T> type) {
        // Fetched first and reconstructed after the connection is back in the pool: rebuilding
        // relations performs lookups of its own (see find).
        Map<String, String> entries = new LinkedHashMap<>();
        try (Jedis jedis = jedisPool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(keyPrefix + pattern).count(1000);
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                List<String> keys = scan.getResult();
                if (!keys.isEmpty()) {
                    try (Pipeline pipeline = jedis.pipelined()) {
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
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            try {
                Map<String, Object> rawData = objectMapper.readValue(entry.getValue(), MAP_TYPE_REF);
                T entity = reconstructEntity(rawData, type, entry.getKey(), new HashMap<>());
                if (entity != null) result.add(entity);
            } catch (Exception e) {
                LOG.warning("Failed to deserialize cached entity: " + e.getMessage());
            }
        }
        return result;
    }

    public void delete(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(keyPrefix + key);
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
                    Field idField = getIdField(value.getClass());
                    if (idField != null) {
                        jsonMap.put(field.getName() + "_id", idField.get(value));
                    }
                } else if (field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class)) {
                    if (value instanceof Collection) {
                        List<Object> ids = new ArrayList<>();
                        for (Object item : (Collection<?>) value) {
                            if (item != null) {
                                Field idField = getIdField(item.getClass());
                                if (idField != null) {
                                    ids.add(idField.get(item));
                                }
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

    private <T> T reconstructEntity(Map<String, Object> rawData, Class<T> type,
                                    String fullKey, Map<String, Object> inProgress) {
        try {
            T entity = type.getDeclaredConstructor().newInstance();
            inProgress.put(fullKey, entity);
            Map<String, Field> fields = getCachedFields(type);

            for (Map.Entry<String, Field> entry : fields.entrySet()) {
                Field field = entry.getValue();
                String fieldName = field.getName();

                if (rawData.containsKey(fieldName)) {
                    Object value = rawData.get(fieldName);
                    value = convertValue(value, field.getType());
                    field.set(entity, value);
                } else if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                    Object idValue = rawData.get(fieldName + "_id");
                    if (idValue != null) {
                        Object relatedEntity = find(field.getType().getSimpleName() + ":" + idValue, field.getType(), inProgress);
                        field.set(entity, relatedEntity);
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
                            for (Object idValue : ids) {
                                Object relatedEntity = find(genericType.getSimpleName() + ":" + idValue, genericType, inProgress);
                                if (relatedEntity != null) {
                                    relatedEntities.add(relatedEntity);
                                }
                            }
                            field.set(entity, relatedEntities);
                        }
                    }
                }
            }
            return entity;
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

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }

        if (targetType.isAssignableFrom(value.getClass())) {
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

        return value;
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
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.expire(keyPrefix + key, seconds);
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
        jedisPool.close();
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
