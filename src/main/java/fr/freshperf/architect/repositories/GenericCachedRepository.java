package fr.freshperf.architect.repositories;

import fr.freshperf.architect.cache.RedisQueueActionPool;
import fr.freshperf.architect.entities.DatabaseAction;
import fr.freshperf.architect.entities.IdentifiableEntity;
import fr.freshperf.architect.cache.RedisManager;

import java.util.List;

import java.util.concurrent.ConcurrentLinkedQueue;

import static fr.freshperf.architect.entities.DatabaseAction.Type.DELETE;
import static fr.freshperf.architect.entities.DatabaseAction.Type.SAVE;

public class GenericCachedRepository<T extends IdentifiableEntity> extends GenericRepository<T> {
    private final Class<T> type;
    private final ConcurrentLinkedQueue<DatabaseAction<T>> updateQueue = new ConcurrentLinkedQueue<>();
    private final String cacheKeyPrefix;

    public GenericCachedRepository(Class<T> type) {
        super(type);
        this.type = type;
        this.cacheKeyPrefix = type.getSimpleName() + ":";
        RedisManager.get().getRedisQueueActionPool().add(this);
    }

    @Override
    public T save(T entity) {
        if(entity.getId() == null) {
            entity = super.save(entity);
        }
        Long id = entity.getId();
        String key = cacheKeyPrefix + id;
        RedisManager.get().save(key, entity);
        updateQueue.add(new DatabaseAction<>(entity, SAVE));
        return entity;
    }

    @Override
    public T findById(Long id) {
        String key = cacheKeyPrefix + id;

        T cachedEntity = RedisManager.get().find(key, type);
        if (cachedEntity != null) {
            return cachedEntity;
        }
        T dbEntity = super.findById(id);
        if (dbEntity != null) {
            RedisManager.get().save(key, dbEntity);
        }
        return dbEntity;
    }
    @Override
    public void delete(T entity) {
        Long id = entity.getId();
        String key = cacheKeyPrefix + id;
        RedisManager.get().delete(key);
        updateQueue.add(new DatabaseAction<>(entity, DELETE));
    }

    public void flushUpdates() {
        DatabaseAction<T> action;
        while ((action = updateQueue.poll()) != null) {
            T entity = action.getEntity();
            switch (action.getType()) {
                case SAVE:
                    super.save(entity);
                    break;
                case DELETE:
                    super.delete(entity);
                    break;
            }
        }
    }
    @Override
    public List<T> all() {
        List<T> entities = super.all();
        for (T entity : entities) {
            Long id = entity.getId();
            String key = cacheKeyPrefix + id;

            RedisManager.get().save(key, entity);
        }
        return entities;
    }
}



