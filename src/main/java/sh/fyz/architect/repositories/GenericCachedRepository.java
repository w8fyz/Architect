package sh.fyz.architect.repositories;

import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.persistant.SessionManager;
import org.hibernate.Session;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.lang.reflect.Field;

public class GenericCachedRepository<T extends IdentifiableEntity> extends GenericRepository<T> {
    private final Class<T> type;
    private final ConcurrentLinkedQueue<DatabaseAction<T>> updateQueue = new ConcurrentLinkedQueue<>();
    private final String cacheKeyPrefix;
    private final String allEntitiesKey;

    public GenericCachedRepository(Class<T> type) {
        super(type);
        this.type = type;
        this.cacheKeyPrefix = type.getSimpleName() + ":";
        this.allEntitiesKey = cacheKeyPrefix + "*";
        RedisManager.get().getRedisQueueActionPool().add(this);
    }

    @Override
    public T save(T entity) {
        if (entity.getId() == null) {
            if (RedisManager.get().isReceiver()) {
                entity = super.save(entity);
            } else {
                return null;
            }
        }
            String key = cacheKeyPrefix + entity.getId();
            RedisManager.get().save(key, entity);
            if(RedisManager.get().isReceiver()) {
                updateQueue.add(new DatabaseAction<>(entity, DatabaseAction.Type.SAVE));
                System.out.print("Added action to updateQueue on "+(RedisManager.get().isReceiver() ? "receiver" : "server (what)"));
            }
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
    public T where(String fieldName, Object value) {
        List<T> entities = getAllFromCache();
        if (entities != null) {
            for (T entity : entities) {
                try {
                    Field field = entity.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    if (value.equals(field.get(entity))) {
                        return entity;
                    }
                } catch (Exception ignored) {}
            }
        }

        T entity = super.where(fieldName, value);
        if (entity != null) {
            RedisManager.get().save(cacheKeyPrefix + entity.getId(), entity);
        }
        return entity;
    }

    @Override
    public List<T> whereList(String fieldName, String value) {
        List<T> entities = getAllFromCache();
        if (entities != null) {
            List<T> result = new ArrayList<>();
            for (T entity : entities) {
                try {
                    Field field = entity.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    if (value.equals(field.get(entity))) {
                        result.add(entity);
                    }
                } catch (Exception ignored) {}
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        List<T> dbEntities = super.whereList(fieldName, value);
        if (dbEntities != null && !dbEntities.isEmpty()) {
            for (T entity : dbEntities) {
                RedisManager.get().save(cacheKeyPrefix + entity.getId(), entity);
            }
        }
        return dbEntities;
    }

    @Override
    public void delete(T entity) {
        String key = cacheKeyPrefix + entity.getId();
        RedisManager.get().delete(key);
        if (RedisManager.get().isReceiver()) updateQueue.add(new DatabaseAction<>(entity, DatabaseAction.Type.DELETE));
    }

    private List<T> getAllFromCache() {
        return RedisManager.get().findAll(allEntitiesKey, type);
    }

    public void flushUpdates() {
        DatabaseAction<T> action;
        while ((action = updateQueue.poll()) != null) {
            T entity = action.getEntity();
            System.out.print("Detected updateQueue not null on "+(RedisManager.get().isReceiver() ? "receiver" : "server (what)"));
            System.out.print("Processing action SAVE: " + action.getType());
            switch (action.getType()) {
                case DatabaseAction.Type.SAVE:
                    super.save(entity);
                    break;
                case DatabaseAction.Type.DELETE:
                    super.delete(entity);
                    break;
            }
        }
    }

    @Override
    public List<T> all() {
        System.out.println("LISTING ALL FROM GENERICCAHCEDREPOSITORY");
        List<T> entities = super.all();
        if (entities != null && !entities.isEmpty()) {
            System.out.println("got something");
            for (T entity : entities) {
                System.out.println("Saving entity: " + cacheKeyPrefix + entity.getId());
                RedisManager.get().save(cacheKeyPrefix + entity.getId(), entity);
            }
        } else {
            System.out.println("Entities is null or empty");
        }
        return entities;
    }
}



