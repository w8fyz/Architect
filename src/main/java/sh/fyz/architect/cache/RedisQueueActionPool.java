package sh.fyz.architect.cache;

import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.persistent.DatabaseFailures;
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
    private static final int MAX_RELAY_ATTEMPTS = 25;
    /** About 30 s of passes, 200 ms apart. */
    private static final int MAX_RELAY_UNAVAILABLE_ATTEMPTS = 150;
    private static final long FAILURE_REPORT_INTERVAL_MS = 30_000;

    private final CopyOnWriteArrayList<GenericCachedRepository<?>> queue = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<AbstractMap.SimpleEntry<DatabaseAction<?>, GenericRepository<?>>> pubSubQueue = new ConcurrentLinkedQueue<>();
    private final ExecutorService threadPool;
    private final boolean isReceiver;
    private volatile boolean running = true;
    /** Failed passes of the first relayed action, and when one was last logged; drain thread only. */
    private int relayAttempts;
    private long lastRelayFailureReport;
    // Entity types whose cache was loaded from the database since this pool (Redis manager) started.
    private final java.util.Set<Class<?>> loadedTypes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Claims loading the cache of an entity type from the database, once per pool: true for the
     * first caller only, until {@link #releaseCacheLoad} if that load fails.
     */
    public boolean claimCacheLoad(Class<?> type) {
        return loadedTypes.add(type);
    }

    public void releaseCacheLoad(Class<?> type) {
        loadedTypes.remove(type);
    }

    /** Flushes this repository's queued writes from now on; adding it again does nothing. */
    public void add(GenericCachedRepository<?> repository) {
        queue.addIfAbsent(repository);
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
                drainPubSubQueue(false);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    /**
     * Applies the relayed actions in order. One that may still succeed stays first in the queue
     * and is retried by the next pass: dropped, it would be lost for good once the next receiver
     * start clears Redis, where only the sender's copy of it remains. The queue is shared by
     * every entity type, so only a database that cannot be reached at all holds it up for as
     * long as it lasts; a failure tied to the write (lock or statement timeout, missing
     * privilege) gets {@link #MAX_RELAY_UNAVAILABLE_ATTEMPTS} passes, another database error
     * (a foreign key another write may still satisfy) {@link #MAX_RELAY_ATTEMPTS}, and anything
     * else (a stale row, an entity's own invalid state, a bug) is dropped at once.
     * Only the drain worker calls it, then {@link #flushRemaining} once that worker has stopped.
     *
     * @param finalPass at shutdown: nothing retries afterwards, so a failing action is dropped
     *                  and the next ones still tried, unless the database cannot be reached
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void drainPubSubQueue(boolean finalPass) {
        AbstractMap.SimpleEntry<DatabaseAction<?>, GenericRepository<?>> entry;
        while ((entry = pubSubQueue.peek()) != null) {
            if (!SessionManager.isInitialized()) {
                return;
            }
            DatabaseAction<?> action = entry.getKey();
            GenericRepository repository = entry.getValue();
            try {
                String className = action.getClassName();
                if (!SessionManager.get().isRegisteredEntity(className)) {
                    LOG.warning("Rejected action with unknown entity class: " + className);
                } else {
                    Class<?> entityClass = SessionManager.get().getEntityClass(className);
                    Object entity;
                    try {
                        entity = RedisManager.get().getObjectMapper().convertValue(action.getEntity(), entityClass);
                    } catch (IllegalArgumentException e) {
                        // A malformed message stays malformed: retrying it would only hold up the others.
                        LOG.severe("Dropped relayed " + action.getType() + " of " + className
                                + ", which could not be read: " + e.getMessage());
                        pubSubQueue.poll();
                        relayAttempts = 0;
                        continue;
                    }
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
                        default -> { }
                    }
                }
            } catch (Exception e) {
                if (DatabaseFailures.isConnectionLost(e)) {
                    reportRelayFailure(action, e);
                    return;
                }
                if (!finalPass && shouldRetry(e)) {
                    reportRelayFailure(action, e);
                    return;
                }
                LOG.severe("Dropped relayed " + action.getType() + " of " + action.getClassName()
                        + ", which could not be applied: " + e.getMessage());
            }
            pubSubQueue.poll();
            relayAttempts = 0;
        }
    }

    /** Whether the first relayed action, which failed with {@code e}, gets another pass. */
    private boolean shouldRetry(Exception e) {
        if (DatabaseFailures.isStaleRow(e) || DatabaseFailures.isPermanentRejection(e)
                || !DatabaseFailures.isDatabaseError(e)) {
            return false;
        }
        int limit = DatabaseFailures.isUnavailable(e) ? MAX_RELAY_UNAVAILABLE_ATTEMPTS : MAX_RELAY_ATTEMPTS;
        return ++relayAttempts < limit;
    }

    /** Logs a relayed action that will be retried, at most every {@link #FAILURE_REPORT_INTERVAL_MS}. */
    private void reportRelayFailure(DatabaseAction<?> action, Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastRelayFailureReport >= FAILURE_REPORT_INTERVAL_MS) {
            LOG.warning("Failed to apply relayed " + action.getType() + " of " + action.getClassName() + " ("
                    + pubSubQueue.size() + " relayed action(s) waiting, retrying): " + e.getMessage());
            lastRelayFailureReport = now;
        }
    }

    public void shutdown() {
        running = false;
        if (threadPool == null) {
            return;
        }
        // Cleared for the wait and the final flush, restored after: stop() called from a thread
        // already interrupted (an async callback, interrupted by stop() itself) would otherwise
        // skip both and leave the queued writes in memory.
        boolean interrupted = Thread.interrupted();
        try {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                    threadPool.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                threadPool.shutdownNow();
            }
            // A worker still running would drain the relayed actions concurrently (cached
            // repositories serialize their own flushes), and two transactions writing one
            // entity could commit its states out of order.
            if (threadPool.isTerminated()) {
                flushRemaining();
            } else {
                LOG.warning("Flush workers did not stop; pending cached writes were not flushed");
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
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
        // Once normally, then again after the cached writes, on which a relayed action may depend
        // (a parent's delete waiting for its children's), dropping what still fails.
        drainPubSubQueue(false);
        for (GenericCachedRepository<?> repository : queue) {
            try {
                repository.flushUpdates();
            } catch (Exception e) {
                LOG.warning("Error flushing updates for repository on shutdown: " + e.getMessage());
            }
            // Reported here, not rate-limited like the flush failures: after stop() nothing
            // retries them until this Architect starts again, and they are lost if the JVM exits.
            int left = repository.pendingWriteCount();
            if (left > 0) {
                LOG.warning(left + " queued write(s) of " + repository.getEntityClass().getSimpleName()
                        + " could not be flushed on shutdown: they are applied if Architect starts"
                        + " again in this process while the repository is still in use, and lost otherwise");
            }
        }
        drainPubSubQueue(true);
        if (!pubSubQueue.isEmpty()) {
            LOG.warning(pubSubQueue.size() + " relayed action(s) could not be applied on shutdown"
                    + " (database unreachable) and are lost");
        }
    }
}
