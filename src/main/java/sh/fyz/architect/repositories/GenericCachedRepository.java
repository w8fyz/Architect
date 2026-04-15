package sh.fyz.architect.repositories;

import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.cache.RedisManager;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;

import org.hibernate.Session;
import org.hibernate.Transaction;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
                throw new UnsupportedOperationException(
                    "Cannot create new entities (null ID) on a non-receiver instance. " +
                    "New entities must be created on the receiver."
                );
            }
        }

        String key = cacheKeyPrefix + entity.getId();
        RedisManager.get().save(key, entity);

        if (RedisManager.get().isReceiver()) {
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
    public void delete(T entity) {
        String key = cacheKeyPrefix + entity.getId();
        RedisManager.get().delete(key);
        if (RedisManager.get().isReceiver()) {
            updateQueue.add(new DatabaseAction<>(entity, DatabaseAction.Type.DELETE));
        } else {
            super.delete(entity);
        }
    }

    private List<T> getAllFromCache() {
        return RedisManager.get().findAll(allEntitiesKey, type);
    }

    public void flushUpdates() {
        List<DatabaseAction<T>> batch = new ArrayList<>();
        DatabaseAction<T> action;
        while ((action = updateQueue.poll()) != null) {
            batch.add(action);
        }
        if (batch.isEmpty()) return;

        try (Session session = sh.fyz.architect.persistent.SessionManager.get().getSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                int count = 0;
                for (DatabaseAction<T> item : batch) {
                    T entity = item.getEntity();
                    switch (item.getType()) {
                        case SAVE -> session.merge(entity);
                        case DELETE -> {
                            Object managed = session.merge(entity);
                            session.remove(managed);
                        }
                    }
                    count++;
                    if (count % 20 == 0) {
                        session.flush();
                        session.clear();
                    }
                }
                transaction.commit();
            } catch (Exception e) {
                updateQueue.addAll(batch);
                System.err.println("ERROR: Failed to flush updates for " + type.getSimpleName() + ": " + e.getMessage());
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

    // --- QUERY BUILDER EXECUTION (cache-first) ---

    @Override
    protected List<T> executeQuery(QueryBuilder<T> builder) {
        if (builder.hasRawConditions()) {
            return super.executeQuery(builder);
        }

        List<T> cached = getAllFromCache();
        if (cached != null && !cached.isEmpty()) {
            Stream<T> stream = cached.stream();

            for (QueryBuilder.Condition condition : builder.getConditions()) {
                stream = stream.filter(entity -> matchesCondition(entity, condition));
            }

            stream = stream.map(this::resolveRelations).filter(Objects::nonNull);

            if (!builder.getOrderBys().isEmpty()) {
                stream = stream.sorted(buildComparator(builder.getOrderBys()));
            }

            if (builder.getOffset() > 0) {
                stream = stream.skip(builder.getOffset());
            }
            if (builder.getLimit() > 0) {
                stream = stream.limit(builder.getLimit());
            }

            return stream.collect(Collectors.toList());
        }

        List<T> dbResults = super.executeQuery(builder);
        if (dbResults != null) {
            for (T entity : dbResults) {
                if (entity instanceof IdentifiableEntity ie && ie.getId() != null) {
                    RedisManager.get().save(cacheKeyPrefix + ie.getId(), entity);
                }
            }
        }
        return dbResults;
    }

    @Override
    protected long executeCount(QueryBuilder<T> builder) {
        if (builder.hasRawConditions()) {
            return super.executeCount(builder);
        }

        List<T> cached = getAllFromCache();
        if (cached != null && !cached.isEmpty()) {
            return cached.stream()
                .filter(entity -> {
                    for (QueryBuilder.Condition c : builder.getConditions()) {
                        if (!matchesCondition(entity, c)) return false;
                    }
                    return true;
                })
                .count();
        }
        return super.executeCount(builder);
    }

    @Override
    protected int executeDelete(QueryBuilder<T> builder) {
        List<T> cached = getAllFromCache();
        if (cached != null) {
            for (T entity : cached) {
                boolean matches = true;
                for (QueryBuilder.Condition c : builder.getConditions()) {
                    if (!matchesCondition(entity, c)) {
                        matches = false;
                        break;
                    }
                }
                if (matches && entity.getId() != null) {
                    RedisManager.get().delete(cacheKeyPrefix + entity.getId());
                }
            }
        }
        return super.executeDelete(builder);
    }

    // --- IN-MEMORY CONDITION MATCHING ---

    private boolean matchesCondition(T entity, QueryBuilder.Condition condition) {
        Object fieldValue = getFieldValue(entity, condition.field());
        Object condValue = condition.value();

        return switch (condition.operator()) {
            case EQ -> Objects.equals(fieldValue, condValue);
            case NEQ -> !Objects.equals(fieldValue, condValue);
            case GT -> compareValues(fieldValue, condValue) > 0;
            case GTE -> compareValues(fieldValue, condValue) >= 0;
            case LT -> compareValues(fieldValue, condValue) < 0;
            case LTE -> compareValues(fieldValue, condValue) <= 0;
            case LIKE -> matchesLike(fieldValue, condValue);
            case IN -> condValue instanceof Collection<?> c && c.contains(fieldValue);
            case NOT_IN -> !(condValue instanceof Collection<?> c && c.contains(fieldValue));
            case IS_NULL -> fieldValue == null;
            case IS_NOT_NULL -> fieldValue != null;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a == null || b == null) return 0;

        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }

        if (a instanceof Comparable ca && b.getClass().isAssignableFrom(a.getClass())) {
            return ca.compareTo(b);
        }

        return a.toString().compareTo(b.toString());
    }

    private boolean matchesLike(Object fieldValue, Object pattern) {
        if (fieldValue == null || pattern == null) return false;
        String value = fieldValue.toString();
        String pat = pattern.toString();
        String regex = "^" + Pattern.quote(pat)
            .replace("%", "\\E.*\\Q")
            .replace("_", "\\E.\\Q") + "$";
        return Pattern.compile(regex).matcher(value).matches();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Comparator<T> buildComparator(List<QueryBuilder.OrderBy> orderBys) {
        Comparator<T> comparator = null;
        for (QueryBuilder.OrderBy order : orderBys) {
            Comparator<T> fieldComparator = (a, b) -> {
                Object va = getFieldValue(a, order.field());
                Object vb = getFieldValue(b, order.field());
                if (va == null && vb == null) return 0;
                if (va == null) return 1;
                if (vb == null) return -1;
                if (va instanceof Comparable ca) {
                    return ca.compareTo(vb);
                }
                return va.toString().compareTo(vb.toString());
            };
            if (order.order() == QueryBuilder.SortOrder.DESC) {
                fieldComparator = fieldComparator.reversed();
            }
            comparator = comparator == null ? fieldComparator : comparator.thenComparing(fieldComparator);
        }
        return comparator;
    }

    // --- REFLECTION UTILITIES ---

    private Object getFieldValue(Object entity, String fieldName) {
        try {
            Field field = findField(entity.getClass(), fieldName);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(entity);
        } catch (Exception e) {
            return null;
        }
    }

    private T resolveRelations(T entity) {
        try {
            HashMap<Class<?>, GenericRepository<?>> repoCache = new HashMap<>();
            for (Field field : entity.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                if (field.isAnnotationPresent(OneToMany.class)) {
                    Collection<?> ids = (Collection<?>) field.get(entity);
                    if (ids != null) {
                        Collection<Object> resolvedEntities = new ArrayList<>();
                        for (Object id : ids) {
                            String repositoryName = field.getType().getComponentType() != null
                                ? field.getType().getComponentType().getSimpleName().toLowerCase() + "s"
                                : guessRepositoryName(field);
                            GenericRepository<?> repository = repoCache.computeIfAbsent(
                                    field.getType(),
                                    k -> RepositoryRegistry.get().getRepository(repositoryName)
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
                                k -> RepositoryRegistry.get().getRepository(repositoryName)
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
            System.err.println("WARN: Failed to resolve relations for " + type.getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private String guessRepositoryName(Field field) {
        String typeName = field.getGenericType().getTypeName();
        if (typeName.contains("<")) {
            String genericName = typeName.substring(typeName.lastIndexOf(".") + 1, typeName.indexOf(">"));
            return genericName.toLowerCase() + "s";
        }
        return field.getName();
    }

    private Field findField(Class<?> clazz, String fieldName) {
        if (clazz == null || fieldName == null) return null;
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), fieldName);
            }
        }
        return null;
    }
}
