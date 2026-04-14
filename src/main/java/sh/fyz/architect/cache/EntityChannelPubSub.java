package sh.fyz.architect.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.persistant.SessionManager;
import sh.fyz.architect.repositories.GenericRepository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class EntityChannelPubSub<T> {

    private static final Set<String> channels = ConcurrentHashMap.newKeySet();

    private final GenericRepository<T> hotRepository;
    private final Class<T> entityClass;
    private final String channelName;

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

        RedisManager.get().getPubSubExecutor().submit(() -> {
            try (Jedis jedis = RedisManager.get().getJedisPool().getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        try {
                            ObjectMapper mapper = RedisManager.get().getObjectMapper();
                            JavaType actionType = mapper.getTypeFactory()
                                .constructParametricType(DatabaseAction.class, entityClass);
                            DatabaseAction<T> entity = mapper.readValue(message, actionType);

                            String className = entity.getClassName();
                            if (!SessionManager.get().isRegisteredEntity(className)) {
                                System.err.println("WARN: Rejected pub/sub message with unknown entity class: " + className);
                                return;
                            }

                            RedisManager.get().getRedisQueueActionPool().add(entity, hotRepository);
                        } catch (JsonProcessingException e) {
                            System.err.println("ERROR: Failed to deserialize pub/sub message: " + e.getMessage());
                        }
                    }
                }, channelName);
            } catch (Exception e) {
                System.err.println("ERROR: Pub/sub subscription failed for channel " + channelName + ": " + e.getMessage());
            }
        });
    }
}
