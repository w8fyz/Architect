package sh.fyz.architect.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.Session;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.exceptions.JedisException;
import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.persistent.SessionManager;
import sh.fyz.architect.repositories.GenericRepository;

import java.util.logging.Logger;

public class EntityChannelPubSub<T> {

    private static final Logger LOG = Logger.getLogger(EntityChannelPubSub.class.getName());

    private static final long INITIAL_BACKOFF_MS = 100L;
    private static final long MAX_BACKOFF_MS = 5_000L;

    private final GenericRepository<T> hotRepository;
    private final Class<T> entityClass;
    private final String channelName;

    private volatile JedisPubSub activeSubscription;

    public EntityChannelPubSub(Class<T> entityClass) {
        this.entityClass = entityClass;
        this.hotRepository = new GenericRepository<>(entityClass) {
            /**
             * A relayed entity was read from the sender's Redis copy, which keeps the version it
             * was cached with while the row's is incremented by each relayed save: without this,
             * each save after the first (and any delete) of a {@code @Version} entity would fail
             * and be dropped.
             * Last writer wins, like the cached repositories' own flushes.
             */
            @Override
            protected void beforeMerge(Session session, T entity) {
                if (entity instanceof IdentifiableEntity identifiable) {
                    alignVersion(session, entity, identifiable.getId());
                }
            }
        };
        this.channelName = "database-action:" + entityClass.getSimpleName();
    }

    public void publish(DatabaseAction<T> action) {
        try {
            String message = RedisManager.get().getObjectMapper().writeValueAsString(action);
            RedisManager.get().getRedisClient().publish(channelName, message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize database action for pub/sub", e);
        }
    }

    /**
     * Starts listening on this entity's channel. Only the receiver consumes relayed actions (it
     * is the only instance that writes to the database), so other instances do not subscribe:
     * they would hold a Redis connection open just to queue messages nobody processes.
     */
    public void subscribe() {
        RedisManager manager = RedisManager.get();
        if (!manager.isReceiver() || !manager.registerSubscription(channelName, this)) {
            return;
        }

        manager.getPubSubExecutor().submit(() -> subscribeLoop(manager));
    }

    /**
     * Bound to the manager that started it: after {@code stop()} / {@code start()} a new manager
     * starts its own loop, and this one must not carry on against the new instance.
     */
    private void subscribeLoop(RedisManager manager) {
        long backoff = INITIAL_BACKOFF_MS;
        while (manager.isAlive()) {
            try (RedisClient subscriber = manager.openSubscriberClient()) {
                JedisPubSub pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleMessage(message);
                    }

                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        // Shutdown may have run unsubscribe() before this subscription existed.
                        if (!manager.isAlive()) {
                            unsubscribe();
                        }
                    }
                };
                activeSubscription = pubSub;
                subscriber.subscribe(pubSub, channelName);
                backoff = INITIAL_BACKOFF_MS;
            } catch (JedisException e) {
                if (!manager.isAlive()) {
                    return;
                }
                LOG.warning("Pub/sub connection for channel " + channelName + " lost: " + e.getMessage()
                        + " — retrying in " + backoff + "ms");
                sleepQuietly(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            } catch (Exception e) {
                if (!manager.isAlive()) {
                    return;
                }
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
     * Unblocks the blocking {@code subscribe}. Called by {@link RedisManager#shutdown()}
     * after it clears the {@code isAlive} flag, so the loop then exits instead of resubscribing.
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
