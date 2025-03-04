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

    private final ArrayList<GenericCachedRepository> queue = new ArrayList<>();
    private final HashMap<DatabaseAction, GenericRepository> pubSubQueue = new HashMap<>();
    private final ExecutorService threadPool;
    private volatile boolean running = true;

    public void add(GenericCachedRepository repository) {
        System.out.println("ADDING REPOSITORY TO RedisQueueActionPool : "+repository.getClass().getSimpleName());
        queue.add(repository);
        repository.all(); // Load all entities from the database
    }

    public void add(DatabaseAction action, GenericRepository repository) {
        pubSubQueue.put(action, repository);
    }

    public RedisQueueActionPool(boolean isReceiver) {
        if (!isReceiver) {
            threadPool = null;
            return;
        }
        threadPool = Executors.newFixedThreadPool(2);
        threadPool.submit(() -> {
            System.out.println("Starting RedisQueueActionPool thread");
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
            System.out.println("Second submited");
            while (RedisManager.get().isAlive()) {
                System.out.println("Second thread alive");
                Iterator<Map.Entry<DatabaseAction, GenericRepository>> iterator = pubSubQueue.entrySet().iterator();
                System.out.println("PubSubQueue : "+pubSubQueue.entrySet().size());
                while (iterator.hasNext()) {
                    Map.Entry<DatabaseAction, GenericRepository> entry = iterator.next();
                    DatabaseAction action = entry.getKey();
                    GenericRepository repository = entry.getValue();
                    
                    System.out.println("Processing action: " + action.getType() + " for entity: " + action.getEntity());
                    
                    try {
                        // Convertir l'entité Map en objet
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
                    
                    System.out.println("Completed size : "+pubSubQueue.entrySet().size());
                    iterator.remove();
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    ignored.printStackTrace();
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
            // Convertir la Map en JSON puis en objet de la classe cible
            String json = mapper.writeValueAsString(mapObject);
            return mapper.readValue(json, entityClass);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert Map to entity: " + e.getMessage(), e);
        }
    }
}
