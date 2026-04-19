package sh.fyz.architect.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisException;
import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.persistent.SessionManager;
import sh.fyz.architect.repositories.GenericRepository;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class EntityChannelPubSub<T> {

    private static final Logger LOG = Logger.getLogger(EntityChannelPubSub.class.getName());
    private static final Set<String> channels = ConcurrentHashMap.newKeySet();

    private static final long INITIAL_BACKOFF_MS = 100L;
    private static final long MAX_BACKOFF_MS = 5_000L;

    private final GenericRepository<T> hotRepository;
    private final Class<T> entityClass;
    private final String channelName;

    private volatile JedisPubSub activeSubscription;

    public EntityChannelPubSub(Class<T> entityClass) {
        this.entityClass = entityClass;
        this.hotRepository = new GenericRepository<>(entityClass);
        this.channelName = "database-action:" + entityClass.getSimpleName();
    }

    public void publish(DatabaseAction<T> action) {
        try {
            String message = RedisManager.get().getObjectMapper().writeValueAsString(action);
            try (Jedis jedis = RedisManager.get().getJedisPool().getResource()) {
                jedis.publish(channelName, message);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize database action for pub/sub", e);
        }
    }

    public void subscribe() {
        if (!channels.add(channelName)) {
            return;
        }

        RedisManager.get().getPubSubExecutor().submit(this::subscribeLoop);
    }

    private void subscribeLoop() {
        long backoff = INITIAL_BACKOFF_MS;
        while (RedisManager.isInitialized() && RedisManager.get().isAlive()) {
            try (Jedis jedis = RedisManager.get().getJedisPool().getResource()) {
                JedisPubSub pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleMessage(message);
                    }
                };
                activeSubscription = pubSub;
                jedis.subscribe(pubSub, channelName);
                backoff = INITIAL_BACKOFF_MS;
            } catch (JedisException e) {
                if (!RedisManager.isInitialized() || !RedisManager.get().isAlive()) {
                    return;
                }
                LOG.warning("Pub/sub connection for channel " + channelName + " lost: " + e.getMessage()
                        + " — retrying in " + backoff + "ms");
                sleepQuietly(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            } catch (Exception e) {
                LOG.warning("Pub/sub loop for channel " + channelName + " failed: " + e.getMessage());
                sleepQuietly(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            } finally {
                activeSubscription = null;
            }
        }
    }

    private void handleMessage(String message) {
        try {
            ObjectMapper mapper = RedisManager.get().getObjectMapper();
            JavaType actionType = mapper.getTypeFactory()
                .constructParametricType(DatabaseAction.class, entityClass);
            DatabaseAction<T> entity = mapper.readValue(message, actionType);

            String className = entity.getClassName();
            if (!SessionManager.get().isRegisteredEntity(className)) {
                LOG.warning("Rejected pub/sub message with unknown entity class: " + className);
                return;
            }

            RedisManager.get().getRedisQueueActionPool().add(entity, hotRepository);
        } catch (JsonProcessingException e) {
            LOG.warning("Failed to deserialize pub/sub message: " + e.getMessage());
        }
    }

    /**
     * Signals the subscribe loop to exit. Called from {@link RedisManager#shutdown()} indirectly
     * via the {@code isAlive} flag; this call unblocks the blocking {@code jedis.subscribe}.
     */
    public void unsubscribe() {
        JedisPubSub pubSub = activeSubscription;
        if (pubSub != null && pubSub.isSubscribed()) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignored) {
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
