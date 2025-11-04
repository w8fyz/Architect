package sh.fyz.architect.repositories;

import sh.fyz.architect.persistant.SessionManager;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.graph.GraphSemantic;

import jakarta.persistence.OneToMany;
import jakarta.persistence.EntityGraph;

import java.lang.reflect.Field;
import java.util.List;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.HashMap;
import java.util.Map;

public class GenericRepository<T> {
    protected final Class<T> type;
    protected final ExecutorService threadPool;

    public GenericRepository(Class<T> type) {
        this.type = type;
        this.threadPool = SessionManager.get().getThreadPool();
    }

    public Class<T> getEntityClass() {
        return type;
    }

    public Object prepareEntityId(String value) {
        try {
            Field field = type.getDeclaredField("id");
            Class<?> fieldType = field.getType();
            if (fieldType == Long.class || fieldType == long.class) {
                return Long.parseLong(value);
            } else if (fieldType == UUID.class) {
                return UUID.fromString(value);
            } else if (fieldType == Integer.class || fieldType == int.class) {
                return Integer.parseInt(value);
            } else if (fieldType == String.class) {
                return value;
            } else if (fieldType == Double.class || fieldType == double.class) {
                return Double.parseDouble(value);
            } else if (fieldType == Float.class || fieldType == float.class) {
                return Float.parseFloat(value);
            } else if (fieldType == Boolean.class || fieldType == boolean.class) {
                return Boolean.parseBoolean(value);
            } else {
                throw new IllegalArgumentException("Unsupported ID type: " + fieldType.getName());
            }
        } catch (NoSuchFieldException e) {
            return value;
        }
    }

    public T save(T entity) {
        try (Session session = SessionManager.get().getSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                T savedEntity = (T) session.merge(entity);
                transaction.commit();
                return savedEntity;
            } catch (Exception e) {
                transaction.rollback();
                e.printStackTrace();
                return entity;
            }
        }
    }

    public void saveAsync(T entity, Consumer<T> callback, Consumer<Exception> errorCallback) {
        threadPool.submit(() -> {
            try {
                T savedEntity = save(entity);
                callback.accept(savedEntity);
            } catch (Exception e) {
                e.printStackTrace();
                errorCallback.accept(e);
            }
        });
    }

    public T findById(Object id) {
        try (Session session = SessionManager.get().getSession()) {
            return session.get(type, id);
        }
    }

    public void findByIdAsync(Long id, Consumer<T> callback, Consumer<Exception> errorCallback) {
        threadPool.submit(() -> {
            try {
                T entity = findById(id);
                callback.accept(entity);
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    public List<T> all() {
        try (Session session = SessionManager.get().getSession()) {
            return session.createQuery("from " + type.getName(), type).list();
        }
    }

    public void allAsync(Consumer<List<T>> callback, Consumer<Exception> errorCallback) {
        threadPool.submit(() -> {
            try {
                List<T> entities = all();
                callback.accept(entities);
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    public T where(String where, Object param) {
        try (Session session = SessionManager.get().getSession()) {
            String hql = "FROM " + type.getName() + " WHERE " + where + " = :param";
            List<T> entities = session.createQuery(hql, type)
                    .setParameter("param", param)
                    .setMaxResults(1)
                    .list();
            return entities.stream().findFirst().orElse(null);
        }
    }

    public void whereAsync(String where, String param, Consumer<T> callback, Consumer<Exception> errorCallback) {
        threadPool.submit(() -> {
            try {
                T entity = where(where, param);
                callback.accept(entity);
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    public List<T> whereList(String where, String param) {
        try (Session session = SessionManager.get().getSession()) {
            String hql = "FROM " + type.getName() + " WHERE " + where + " = :param";
            return session.createQuery(hql, type)
                    .setParameter("param", param)
                    .list();
        }
    }

    public void whereListAsync(String where, String param, Consumer<List<T>> callback, Consumer<Exception> errorCallback) {
        threadPool.submit(() -> {
            try {
                List<T> entities = whereList(where, param);
                callback.accept(entities);
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    public void delete(T entity) {
        try (Session session = SessionManager.get().getSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                session.remove(entity);
                transaction.commit();
            } catch (Exception e) {
                transaction.rollback();
                e.printStackTrace();
            }
        }
    }

    // Asynchronous delete by entity
    public void deleteAsync(T entity, Runnable callback, Consumer<Exception> errorCallback) {
        threadPool.submit(() -> {
            try {
                delete(entity);
                callback.run();
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }

    // Synchronous delete with WHERE clause
    public void deleteWhere(String where, String param) {
        try (Session session = SessionManager.get().getSession()) {
            Transaction transaction = session.beginTransaction();
            String hql = "DELETE FROM " + type.getName() + " WHERE " + where + " = :param";
            session.createQuery(hql, type)
                    .setParameter("param", param)
                    .executeUpdate();
            transaction.commit();
        }
    }

    // Asynchronous delete with WHERE clause
    public void deleteWhereAsync(String where, String param, Runnable callback, Consumer<Exception> errorCallback) {
        threadPool.submit(() -> {
            try {
                deleteWhere(where, param);
                callback.run();
            } catch (Exception e) {
                errorCallback.accept(e);
            }
        });
    }
}

