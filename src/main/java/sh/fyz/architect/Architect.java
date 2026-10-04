package sh.fyz.architect;

import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.SessionManager;
import sh.fyz.architect.repositories.GenericCachedRepository;
import sh.fyz.architect.repositories.GenericRepository;
import sh.fyz.architect.repositories.RepositoryRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class Architect {

    private RedisCredentials redisCredentials;
    private DatabaseCredentials databaseCredentials;
    private boolean isReceiver = true;
    private final List<Class<? extends IdentifiableEntity>> entityClasses = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public Architect() {
    }

    public Architect setReceiver(boolean isReceiver) {
        this.isReceiver = isReceiver;
        return this;
    }

    public Architect setRedisCredentials(RedisCredentials redisCredentials) {
        this.redisCredentials = redisCredentials;
        return this;
    }

    public Architect setDatabaseCredentials(DatabaseCredentials databaseCredentials) {
        this.databaseCredentials = databaseCredentials;
        return this;
    }

    @SafeVarargs
    public final Architect addRepositories(GenericRepository<? extends IdentifiableEntity>... repositories) {
        if (repositories == null) {
            return this;
        }
        for (GenericRepository<? extends IdentifiableEntity> repository : repositories) {
            Class<?> entityClass = repository.getEntityClass();
            String repositoryName = entityClass.getSimpleName().toLowerCase() + "s";
            RepositoryRegistry.get().register(repositoryName, repository);
        }
        return this;
    }

    public Architect addEntityClass(Class<? extends IdentifiableEntity> entityClass) {
        if (entityClass != null) {
            this.entityClasses.add(entityClass);
        }
        return this;
    }

    public List<Class<? extends IdentifiableEntity>> getEntityClasses() {
        return entityClasses;
    }

    public DatabaseCredentials getDatabaseCredentials() {
        return databaseCredentials;
    }

    public boolean isStarted() {
        return started.get();
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        boolean sessionInitialized = false;
        boolean redisInitialized = false;
        try {
            // The database first: once Redis is up, cached repositories can be called, and their
            // first call loads the cache from the database.
            if (databaseCredentials != null) {
                SessionManager.initialize(
                    entityClasses,
                    databaseCredentials.getSQLAuthProvider(),
                    databaseCredentials.getUser(),
                    databaseCredentials.getPassword(),
                    databaseCredentials.getPoolSize(),
                    databaseCredentials.getHbm2ddlAuto()
                );
                sessionInitialized = true;
            }

            if (redisCredentials != null) {
                RedisManager.initialize(
                    redisCredentials.getHost(),
                    redisCredentials.getPassword(),
                    redisCredentials.getPort(),
                    redisCredentials.getTimeout(),
                    redisCredentials.getMaxConnections(),
                    isReceiver,
                    redisCredentials.getDefaultTtlSeconds()
                );
                redisInitialized = true;
                // Cached repositories created before a stop() (the first start has none).
                GenericCachedRepository.attachAll();
            }
        } catch (RuntimeException e) {
            if (redisInitialized && RedisManager.isInitialized()) {
                try {
                    RedisManager.reset();
                } catch (RuntimeException ignored) {
                }
            }
            if (sessionInitialized && SessionManager.isInitialized()) {
                try {
                    SessionManager.reset();
                } catch (RuntimeException ignored) {
                }
            }
            started.set(false);
            throw e;
        }
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }

        // Async calls first: those of cached repositories need Redis, and a write they queue
        // after Redis' final flush would be lost (without a database, they run on Redis' own
        // executor, which its shutdown waits for). Then Redis: its shutdown flushes the pending
        // cached writes, which needs the database. Each step runs even if an earlier one
        // throws, so nothing stays half-initialized.
        runAll(
            () -> {
                if (SessionManager.isInitialized()) {
                    SessionManager.get().awaitAsyncCalls();
                }
            },
            () -> {
                if (redisCredentials != null && RedisManager.isInitialized()) {
                    RedisManager.reset();
                }
            },
            () -> {
                if (SessionManager.isInitialized()) {
                    SessionManager.reset();
                }
            },
            () -> RepositoryRegistry.get().clear()
        );
    }

    /** Runs every step, then rethrows the first failure, with the later ones suppressed. */
    private static void runAll(Runnable... steps) {
        Throwable failure = null;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException | Error e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
    }
}
