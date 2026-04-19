package sh.fyz.architect.cache;

import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.persistent.SessionManager;
import sh.fyz.architect.repositories.GenericCachedRepository;
import sh.fyz.architect.repositories.GenericRepository;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.AbstractMap;
import java.util.logging.Logger;

public class RedisQueueActionPool {

    private static final Logger LOG = Logger.getLogger(RedisQueueActionPool.class.getName());

    private final CopyOnWriteArrayList<GenericCachedRepository<?>> queue = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<AbstractMap.SimpleEntry<DatabaseAction<?>, GenericRepository<?>>> pubSubQueue = new ConcurrentLinkedQueue<>();
    private final ExecutorService threadPool;
    private volatile boolean running = true;

    public void add(GenericCachedRepository<?> repository) {
        queue.add(repository);
        repository.all();
    }

    public void add(DatabaseAction<?> action, GenericRepository<?> repository) {
        pubSubQueue.add(new AbstractMap.SimpleEntry<>(action, repository));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RedisQueueActionPool(boolean isReceiver) {
        if (!isReceiver) {
            threadPool = null;
            return;
        }
        threadPool = Executors.newFixedThreadPool(2);
        threadPool.submit(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                if (!RedisManager.get().isAlive()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                for (GenericCachedRepository<?> repository : queue) {
                    try {
                        repository.flushUpdates();
                    } catch (Exception e) {
                        LOG.warning("Error flushing updates for repository: " + e.getMessage());
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
            while (running && RedisManager.get().isAlive()) {
                AbstractMap.SimpleEntry<DatabaseAction<?>, GenericRepository<?>> entry;
                while ((entry = pubSubQueue.poll()) != null) {
                    DatabaseAction<?> action = entry.getKey();
                    GenericRepository repository = entry.getValue();
                    try {
                        String className = action.getClassName();
                        if (!SessionManager.get().isRegisteredEntity(className)) {
                            LOG.warning("Rejected action with unknown entity class: " + className);
                            continue;
                        }
                        Class<?> entityClass = SessionManager.get().getEntityClass(className);
                        Object entity = RedisManager.get().getObjectMapper()
                            .convertValue(action.getEntity(), entityClass);
                        switch (action.getType()) {
                            case SAVE -> repository.save(entity);
                            case DELETE -> repository.delete(entity);
                        }
                    } catch (Exception e) {
                        LOG.warning("Error processing pub/sub action: " + e.getMessage());
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
    }

    public void shutdown() {
        running = false;
        if (threadPool != null) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
