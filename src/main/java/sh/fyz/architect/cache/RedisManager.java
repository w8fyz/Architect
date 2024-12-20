package sh.fyz.architect.cache;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import com.fasterxml.jackson.databind.ObjectMapper;

import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RedisManager {

    private final RedisQueueActionPool redisQueueActionPool;
    private static RedisManager instance;
    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    private final ExecutorService pubSubExecutor = Executors.newCachedThreadPool();

    private boolean isAlive = true;

    private RedisManager(String host, String password, int port, int timeout, int maxConnections) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(maxConnections);
        config.setMaxIdle(maxConnections / 2);
        config.setMinIdle(1);
        this.jedisPool = new JedisPool(config, host, port, timeout, password);
        this.objectMapper = new ObjectMapper();
        this.redisQueueActionPool = new RedisQueueActionPool();
        jedisPool.getResource().flushAll();
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

    public static void initialize(String host, String password, int port, int timeout, int maxConnections) {
        if (instance == null) {
            instance = new RedisManager(host, password, port, timeout, maxConnections);
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
            jedis.set(key, objectMapper.writeValueAsString(entity));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public <T> T find(String key, Class<T> type) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get(key);
            return data != null ? objectMapper.readValue(data, type) : null;
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

    public void shutdown() {
        jedisPool.close();
        isAlive = false;
    }
}

