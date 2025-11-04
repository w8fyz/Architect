package sh.fyz.architect.cache;

import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.persistant.SessionManager;
import sh.fyz.architect.repositories.GenericCachedRepository;
import sh.fyz.architect.repositories.GenericRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static sh.fyz.architect.entities.DatabaseAction.Type.DELETE;
import static sh.fyz.architect.entities.DatabaseAction.Type.SAVE;

import com.fasterxml.jackson.databind.ObjectMapper;

public class RedisQueueActionPool {

    private final java.util.concurrent.CopyOnWriteArrayList<GenericCachedRepository> queue = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<java.util.AbstractMap.SimpleEntry<DatabaseAction, GenericRepository>> pubSubQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final ExecutorService threadPool;
    private volatile boolean running = true;

    public void add(GenericCachedRepository repository) {
        queue.add(repository);
        repository.all();
    }

    public void add(DatabaseAction action, GenericRepository repository) {
        pubSubQueue.add(new java.util.AbstractMap.SimpleEntry<>(action, repository));
    }

    public RedisQueueActionPool(boolean isReceiver) {
        if (!isReceiver) {
            threadPool = null;
            return;
        }
        threadPool = Executors.newFixedThreadPool(2);
        threadPool.submit(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                if (!RedisManager.get().isAlive()) {
                    System.out.println("Redis connection lost, waiting...");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                for (GenericCachedRepository repository : queue) {
                    try {
                        repository.flushUpdates();
                    } catch (Exception e) {
                        System.err.println("Error flushing updates for repository: " + e.getMessage());
                    }
                }
                
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        threadPool.submit(() -> {
            while (RedisManager.get().isAlive()) {
                java.util.AbstractMap.SimpleEntry<DatabaseAction, GenericRepository> entry;
                while ((entry = pubSubQueue.poll()) != null) {
                    DatabaseAction action = entry.getKey();
                    GenericRepository repository = entry.getValue();
                    try {
                        Object entity = convertMapToEntity(action.getEntity(), SessionManager.get().getEntityClass(action.getClassName()));
                        switch (action.getType()) {
                            case SAVE:
                                repository.save(entity);
                                break;
                            case DELETE:
                                repository.delete(entity);
                                break;
                        }
                    } catch (Exception e) {
                        System.err.println("Error processing action: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    public void shutdown() {
        running = false;
        if (threadPool != null) {
            threadPool.shutdown();
        }
    }

    private Object convertMapToEntity(Object mapObject, Class<?> entityClass) {
        if (!(mapObject instanceof Map)) {
            return mapObject;
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(mapObject);
            return mapper.readValue(json, entityClass);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert Map to entity: " + e.getMessage(), e);
        }
    }
}
