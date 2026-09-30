package sh.fyz.architect.cache;

import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.entities.IdentifiableEntity;
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
    private final boolean isReceiver;
    private volatile boolean running = true;

    public void add(GenericCachedRepository<?> repository) {
        queue.add(repository);
        repository.all();
    }

    public void add(DatabaseAction<?> action, GenericRepository<?> repository) {
        // Only a receiver drains this queue; elsewhere it would grow without bound.
        if (!isReceiver) {
            return;
        }
        pubSubQueue.add(new AbstractMap.SimpleEntry<>(action, repository));
    }

    public RedisQueueActionPool(boolean isReceiver) {
        this.isReceiver = isReceiver;
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
                drainPubSubQueue();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void drainPubSubQueue() {
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
                    case DELETE -> {
                        repository.delete(entity);
                        // The sender evicted its cached copy, but a read in the meantime may have
                        // cached the row again: evict once the row is really gone.
                        if (entity instanceof IdentifiableEntity identifiable && identifiable.getId() != null) {
                            RedisManager.get().delete(className + ":" + identifiable.getId());
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warning("Error processing pub/sub action: " + e.getMessage());
            }
        }
    }

    public void shutdown() {
        running = false;
        if (threadPool != null) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                    threadPool.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            // A worker still running would drain the relayed actions concurrently (cached
            // repositories serialize their own flushes), and two transactions writing one
            // entity could commit its states out of order.
            if (threadPool.isTerminated()) {
                flushRemaining();
            } else {
                LOG.warning("Flush workers did not stop; pending cached writes were not flushed");
            }
        }
    }

    /**
     * The worker threads poll every 200 ms, so at shutdown up to one interval of writes is still
     * queued. Those entities exist only in Redis, which the next receiver start clears: without
     * this last pass they are lost. Needs the database, hence {@code Architect.stop()} shuts
     * Redis down before Hibernate.
     */
    private void flushRemaining() {
        if (!SessionManager.isInitialized()) {
            LOG.warning("Database already shut down; pending cached writes could not be flushed");
            return;
        }
        drainPubSubQueue();
        for (GenericCachedRepository<?> repository : queue) {
            try {
                repository.flushUpdates();
            } catch (Exception e) {
                LOG.warning("Error flushing updates for repository on shutdown: " + e.getMessage());
            }
        }
    }
}
