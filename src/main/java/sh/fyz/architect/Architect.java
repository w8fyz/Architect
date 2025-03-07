package sh.fyz.architect;

import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.persistant.DatabaseCredentials;
import sh.fyz.architect.persistant.SessionManager;
import sh.fyz.architect.anchor.Anchor;
import sh.fyz.architect.repositories.GenericRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Architect {

    private RedisCredentials redisCredentials;
    private DatabaseCredentials databaseCredentials;
    private boolean isReceiver = true;
    private final Anchor anchor;

    public Architect() {
        this.anchor = Anchor.get();
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

    public Architect addRepositories(GenericRepository<? extends IdentifiableEntity>... repositories) {
        if(repositories == null) {
            return this;
        }
        for (GenericRepository<? extends IdentifiableEntity> repository : repositories) {
            Class<?> entityClass = repository.getEntityClass();
            String repositoryName = entityClass.getSimpleName().toLowerCase() + "s";
            anchor.registerRepository(repositoryName, repository);
            System.out.println("Registered repository for entity: " + entityClass.getSimpleName() +
                    " using " + repository.getClass().getSimpleName());
        }
        return this;
    }

    public void start() {
        System.out.println("Starting Architect!");

        if (redisCredentials != null) {
            System.out.println("Connecting to Redis at " + redisCredentials.getHost() + ":" + redisCredentials.getPort());
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
            System.out.println("Connecting to Database (using " + databaseCredentials.getSQLAuthProvider().getClass().getSimpleName() + 
                ") at " + databaseCredentials.getSQLAuthProvider().getUrl());
            if (isReceiver) {
                System.out.println("/!\\ This instance will be used as a receiver /!\\");
            }
            SessionManager.initialize(
                databaseCredentials.getSQLAuthProvider(),
                databaseCredentials.getUser(), 
                databaseCredentials.getPassword(), 
                databaseCredentials.getPoolSize()
            );
        } else {
            SessionManager.initialize(null, null, null, 1);
        }
    }

    public void stop() {
        System.out.println("Stopping Architect!");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (redisCredentials != null) {
            System.out.println("Disconnecting from Redis at " + redisCredentials.getHost() + ":" + redisCredentials.getPort());
            RedisManager.get().shutdown();
        }

        if (databaseCredentials != null) {
            System.out.println("Disconnecting from Database at " + databaseCredentials.getSQLAuthProvider().getUrl());
            SessionManager.get().close();
        }
    }

    public Anchor getAnchor() {
        return anchor;
    }
}
