package sh.fyz.architect.repositories;

import sh.fyz.architect.persistant.SessionManager;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.graph.GraphSemantic;

import jakarta.persistence.OneToMany;
import jakarta.persistence.EntityGraph;

import java.lang.reflect.Field;
import java.util.List;

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


    public T save(T entity) {
        Session session = SessionManager.get().getSession();
        Transaction transaction = session.beginTransaction();
        T savedEntity;

        try {
            savedEntity = (T) session.merge(entity);
            transaction.commit();
        } catch (Exception e) {
            transaction.rollback();
            e.printStackTrace();
            return entity;
        } finally {
            session.close();
        }

        return savedEntity;
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
        Session session = SessionManager.get().getSession();
        T entity = session.get(type, id);
        session.close();
        return entity;
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
        Session session = SessionManager.get().getSession();
        List<T> entities = session.createQuery("from " + type.getName(), type).list();
        session.close();
        return entities;
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
        Session session = SessionManager.get().getSession();
        Transaction transaction = session.beginTransaction();

        try {
            session.remove(entity);
            transaction.commit();
        } catch (Exception e) {
            transaction.rollback();
            e.printStackTrace();
        } finally {
            session.close();
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

