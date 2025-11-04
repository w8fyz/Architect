package sh.fyz.architect.repositories;

import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.persistant.SessionManager;
import sh.fyz.architect.anchor.Anchor;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;

import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

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
        }
        return entity;
    }

    @Override
    public T findById(Object id) {
        String key = cacheKeyPrefix + id;
        T cachedEntity = RedisManager.get().find(key, type);
        if (cachedEntity != null) {
            return resolveRelations(cachedEntity);
        }
        T dbEntity = super.findById(id);
        if (dbEntity != null) {
            RedisManager.get().save(key, dbEntity);
            return dbEntity;
        }
        return null;
    }

    @Override
    public T where(String fieldName, Object value) {
        List<T> entities = getAllFromCache();
        if (entities != null) {
            for (T entity : entities) {
                try {
                    entity = resolveRelations(entity);
                    Field field = entity.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object fieldValue = field.get(entity);
                    if (value.equals(fieldValue)) {
                        return entity;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
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
        List<T> entities = getAllFromCache();
        
        if (entities != null && !entities.isEmpty()) {
            List<T> resolvedEntities = new ArrayList<>();
            for (T entity : entities) {
                T resolvedEntity = resolveRelations(entity);
                if (resolvedEntity != null) {
                    resolvedEntities.add(resolvedEntity);
                }
            }
            return resolvedEntities;
        }

        entities = super.all();
        if (entities != null && !entities.isEmpty()) {
            for (T entity : entities) {
                RedisManager.get().save(cacheKeyPrefix + entity.getId(), entity);
            }
            return entities;
        } else {
            return new ArrayList<>();
        }
    }

    private T resolveRelations(T entity) {
        try {
            java.util.HashMap<Class<?>, GenericRepository<?>> repoCache = new java.util.HashMap<>();
            for (Field field : entity.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                if (field.isAnnotationPresent(OneToMany.class)) {
                    Collection<?> ids = (Collection<?>) field.get(entity);
                    if (ids != null) {
                        Collection<Object> resolvedEntities = new ArrayList<>();
                        for (Object id : ids) {
                            String repositoryName = field.getType().getComponentType().getSimpleName().toLowerCase() + "s";
                            GenericRepository<?> repository = repoCache.computeIfAbsent(
                                    field.getType().getComponentType(),
                                    k -> Anchor.get().getRepository(repositoryName)
                            );
                            if (repository != null) {
                                Object resolvedEntity = repository.findById(id);
                                if (resolvedEntity != null) {
                                    resolvedEntities.add(resolvedEntity);
                                }
                            }
                        }
                        field.set(entity, resolvedEntities);
                    }
                } else if (field.isAnnotationPresent(ManyToOne.class) || 
                         field.isAnnotationPresent(OneToOne.class)) {
                    Object id = field.get(entity);
                    if (id != null) {
                        String repositoryName = field.getType().getSimpleName().toLowerCase() + "s";
                        GenericRepository<?> repository = repoCache.computeIfAbsent(
                                field.getType(),
                                k -> Anchor.get().getRepository(repositoryName)
                        );
                        if (repository != null) {
                            Object resolvedEntity = repository.findById(id);
                            if (resolvedEntity != null) {
                                field.set(entity, resolvedEntity);
                            }
                        }
                    }
                }
            }
            return entity;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}



