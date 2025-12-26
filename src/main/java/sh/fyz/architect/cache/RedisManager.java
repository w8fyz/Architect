package sh.fyz.architect.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RedisManager {

    private RedisQueueActionPool redisQueueActionPool;
    private static RedisManager instance;
    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    private final ExecutorService pubSubExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private boolean isReceiver;
    private boolean isAlive = true;

    private RedisManager(String host, String password, int port, int timeout, int maxConnections, boolean receiver) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(maxConnections);
        config.setMaxIdle(maxConnections / 2);
        config.setMinIdle(1);
        this.jedisPool = new JedisPool(config, host, port, timeout, password);
        if (receiver) {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.flushAll();
            }
        }
        this.objectMapper = new ObjectMapper();
        this.isReceiver = receiver;
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


    public RedisQueueActionPool getRedisQueueActionPool() {
        return redisQueueActionPool;
    }

    public static void initialize(String host, String password, int port, int timeout, int maxConnections, boolean receiver) {
        if (instance == null) {
            instance = new RedisManager(host, password, port, timeout, maxConnections, receiver);
            instance.createRedisPool();
        } else {
            throw new IllegalStateException("RedisManager is already initialized!");
        }
    }

    public boolean isAlive() {
        return isAlive;
    }

    public static RedisManager get() {
        if (instance == null) {
            throw new IllegalStateException("RedisManager is not initialized! Call initialize() first.");
        }
        return instance;
    }


    public <T> void save(String key, T entity) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, Object> processedEntity = prepareForSave(entity);
            jedis.set(key, objectMapper.writeValueAsString(processedEntity));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public <T> T find(String key, Class<T> type) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get(key);
            if (data != null) {
                Map<String, Object> rawData = objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {});
                return reconstructEntity(rawData, type);
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public <T> List<T> findAll(String pattern, Class<T> type) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<T> result = new ArrayList<>();
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(pattern).count(1000);
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
                        for (Response<String> resp : responses) {
                            String data = resp.get();
                            if (data != null) {
                                try {
                                    Map<String, Object> rawData = objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {});
                                    T entity = reconstructEntity(rawData, type);
                                    if (entity != null) result.add(entity);
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
                cursor = scan.getCursor();
            } while (!"0".equals(cursor));
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void delete(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private <T> Map<String, Object> prepareForSave(T entity) throws IllegalAccessException {
        Map<String, Object> jsonMap = new HashMap<>();

        for (Field field : entity.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(entity);

            if (value != null) {
                if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                    // Store the ID of the related entity
                    Field idField = getIdField(value.getClass());
                    if (idField != null) {
                        idField.setAccessible(true);
                        jsonMap.put(field.getName() + "_id", idField.get(value));
                    }
                } 
                else if (field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class)) {
                    if (value instanceof Collection) {
                        List<Object> ids = new ArrayList<>();
                        for (Object item : (Collection<?>) value) {
                            if (item != null) {
                                Field idField = getIdField(item.getClass());
                                if (idField != null) {
                                    idField.setAccessible(true);
                                    ids.add(idField.get(item));
                                }
                            }
                        }
                        if (!ids.isEmpty()) {
                            jsonMap.put(field.getName() + "_ids", ids);
                        }
                    }
                } 
                else {
                    jsonMap.put(field.getName(), value);
                }
            }
        }
        return jsonMap;
    }

    private <T> T reconstructEntity(Map<String, Object> rawData, Class<T> type) {
        try {
            T entity = type.getDeclaredConstructor().newInstance();

            for (Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                String fieldName = field.getName();

                if (rawData.containsKey(fieldName)) {
                    Object value = rawData.get(fieldName);
                    // Convert value if necessary
                    value = convertValue(value, field.getType());
                    field.set(entity, value);
                } 
                // Handle relationships
                else if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                    Object idValue = rawData.get(fieldName + "_id");
                    if (idValue != null) {
                        Object relatedEntity = find(field.getType().getSimpleName() + ":" + idValue, field.getType());
                        field.set(entity, relatedEntity);
                    }
                }
                else if (field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class)) {
                    List<?> ids = (List<?>) rawData.get(fieldName + "_ids");
                    if (ids != null && !ids.isEmpty()) {
                        Collection<Object> relatedEntities;
                        
                        // Create appropriate collection type
                        if (List.class.isAssignableFrom(field.getType())) {
                            relatedEntities = new ArrayList<>();
                        } else {
                            relatedEntities = new HashSet<>();
                        }

                        // Get the generic type of the collection
                        Class<?> genericType = getGenericType(field);
                        if (genericType != null) {
                            for (Object idValue : ids) {
                                Object relatedEntity = find(genericType.getSimpleName() + ":" + idValue, genericType);
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
            e.printStackTrace();
            return null;
        }
    }

    private Class<?> getGenericType(Field field) {
        try {
            String typeName = field.getGenericType().getTypeName();
            if (typeName.contains("<")) {
                String genericTypeName = typeName.substring(typeName.indexOf("<") + 1, typeName.indexOf(">"));
                return Class.forName(genericTypeName);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }

        if (targetType.isAssignableFrom(value.getClass())) {
            return value; // No conversion needed
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



    private boolean isEntity(Class<?> clazz) {
        return getIdField(clazz) != null;
    }

    private Field getIdField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                return field;
            }
        }
        return null;
    }

    public void setTTL(String key, int seconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.expire(key, seconds);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void shutdown() {
        if (redisQueueActionPool != null) {
            redisQueueActionPool.shutdown();
        }
        jedisPool.close();
        isAlive = false;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}

