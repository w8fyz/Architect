package sh.fyz.architect.persistant;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;

import jakarta.persistence.Entity;
import sh.fyz.architect.persistant.sql.SQLAuthProvider;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SessionManager {
    private static SessionManager instance;
    private SessionFactory sessionFactory;

    private HashMap<String, Class<?>> registeredEntityClasses = new HashMap<>();
    private final ExecutorService threadPool;

    private SessionManager(SQLAuthProvider authProvider, String user, String password, int poolSize) {
        try {
            if(authProvider != null) {
                Properties settings = new Properties();
                settings.put(Environment.DRIVER, authProvider.getDriver());
                settings.put(Environment.URL, authProvider.getUrl());
                settings.put(Environment.USER, user);
                settings.put(Environment.PASS, password);
                settings.put(Environment.DIALECT, authProvider.getDialect());
                settings.put(Environment.HBM2DDL_AUTO, "update");
                settings.put(Environment.SHOW_SQL, "false");

                Logger.getLogger("org.hibernate").setLevel(Level.WARNING);

                settings.put("hibernate.hikari.minimumIdle", "5");
                settings.put("hibernate.hikari.maximumPoolSize", "10");
                settings.put("hibernate.hikari.idleTimeout", "30000");

                Configuration configuration = new Configuration();
                configuration.setProperties(settings);

                Set<Class<?>> entityClasses = scanEntities();
                for (Class<?> entityClass : entityClasses) {
                    System.out.println("Registering entity class: " + entityClass.getName());
                    configuration.addAnnotatedClass(entityClass);
                    registeredEntityClasses.put(entityClass.getSimpleName(), entityClass);
                }

                this.sessionFactory = configuration.buildSessionFactory();
            }
            this.threadPool = Executors.newFixedThreadPool(poolSize);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Hibernate", e);
        }
    }

    public Class<?> getEntityClass(String name) {
        return registeredEntityClasses.get(name);
    }

    public static void initialize(SQLAuthProvider authProvider, String user, String password, int poolSize) {
        if (instance == null) {
            instance = new SessionManager(authProvider, user, password, poolSize);
        } else {
            throw new IllegalStateException("SessionManager is already initialized!");
        }
    }

    public ExecutorService getThreadPool() {
        return threadPool;
    }

    public static SessionManager get() {
        if (instance == null) {
            throw new IllegalStateException("SessionManager is not initialized! Call initialize() first.");
        }
        return instance;
    }

    public Session getSession() {
        return sessionFactory.openSession();
    }

    public void close() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
        }
    }

    private Set<Class<?>> scanEntities() {
        Set<Class<?>> entityClasses = new HashSet<>();

        try (ScanResult scanResult = new ClassGraph()
                .enableAnnotationInfo()
                .scan()) {

            scanResult.getClassesWithAnnotation(Entity.class.getName()).forEach(classInfo -> {
                try {
                    entityClasses.add(Class.forName(classInfo.getName()));
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            });
        }

        return entityClasses;
    }
}
