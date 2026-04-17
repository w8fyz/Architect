package sh.fyz.architect;

import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.SessionManager;
import sh.fyz.architect.repositories.GenericRepository;
import sh.fyz.architect.repositories.RepositoryRegistry;

import java.util.ArrayList;
import java.util.List;

public class Architect {

    private RedisCredentials redisCredentials;
    private DatabaseCredentials databaseCredentials;
    private boolean isReceiver = true;
    private final List<Class<? extends IdentifiableEntity>> entityClasses = new ArrayList<>();

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

    public void start() {
        if (redisCredentials != null) {
            RedisManager.initialize(
                redisCredentials.getHost(),
                redisCredentials.getPassword(),
                redisCredentials.getPort(),
                redisCredentials.getTimeout(),
                redisCredentials.getMaxConnections(),
                isReceiver
            );
        }

        if (databaseCredentials != null) {
            SessionManager.initialize(
                entityClasses,
                databaseCredentials.getSQLAuthProvider(),
                databaseCredentials.getUser(),
                databaseCredentials.getPassword(),
                databaseCredentials.getPoolSize(),
                databaseCredentials.getThreadPoolSize(),
                databaseCredentials.getHbm2ddlAuto()
            );
        }
    }

    public void stop() {
        if (redisCredentials != null) {
            RedisManager.reset();
        }

        if (SessionManager.isInitialized()) {
            SessionManager.reset();
        }

        RepositoryRegistry.get().clear();
    }
}
