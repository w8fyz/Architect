package sh.fyz.architect.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.repositories.GenericRepository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;

public class EntityChannelPubSub<T> {

    private static final ArrayList<String> channels = new ArrayList<>();

    private GenericRepository<T> hotRepository;

    private final DatabaseAction<T> type;

    public EntityChannelPubSub(DatabaseAction<T> type) {
        this.type = type;
        hotRepository = new GenericRepository<T>((Class<T>)type.getEntity().getClass());
    }

    public void publish(DatabaseAction<T> action) {
        try {
            String message = new ObjectMapper().writeValueAsString(action);
            try(Jedis jedis = RedisManager.get().getJedisPool().getResource()) {
                jedis.publish("database-action:"+type.getEntity().getClass().getSimpleName(), message);
                System.out.println("Published message: " + message);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public void subscribe() {
        String channel = "database-action:"+type.getEntity().getClass().getSimpleName();
        RedisManager.get().getPubSubExecutor().submit(() -> {
            try(Jedis jedis = RedisManager.get().getJedisPool().getResource()) {
                if(channels.contains(channel)) return;
                System.out.println("Subscribing to channel: " + channel);
                channels.add(channel);
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        try {
                            DatabaseAction<T> entity = new ObjectMapper().readValue(message, type.getClass());
                            RedisManager.get().getRedisQueueActionPool().add(entity, hotRepository);
                            System.out.println("Received message: " + message);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }, channel);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

}
