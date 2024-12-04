package fr.freshperf.architect.persistant;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;

import jakarta.persistence.Entity;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

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
    private final SessionFactory sessionFactory;
    private final ExecutorService threadPool;

    // Private constructor for singleton pattern
    private SessionManager(String hostname, int port, String database, String user, String password, int poolSize) {
        try {
            // Set Hibernate properties programmatically
            Properties settings = new Properties();
            settings.put(Environment.DRIVER, "org.postgresql.Driver");
            settings.put(Environment.URL, "jdbc:postgresql://"+hostname+":"+port+"/"+database);
            settings.put(Environment.USER, user);
            settings.put(Environment.PASS, password);
            settings.put(Environment.DIALECT, "org.hibernate.dialect.PostgreSQLDialect");
            settings.put(Environment.HBM2DDL_AUTO, "update");
            settings.put(Environment.SHOW_SQL, "false");

            Logger.getLogger("org.hibernate").setLevel(Level.WARNING);

            // HikariCP settings
            settings.put("hibernate.hikari.minimumIdle", "5");
            settings.put("hibernate.hikari.maximumPoolSize", "10");
            settings.put("hibernate.hikari.idleTimeout", "30000");

            Configuration configuration = new Configuration();
            configuration.setProperties(settings);

            // Scan and register entity classes dynamically
            Set<Class<?>> entityClasses = scanEntities();
            for (Class<?> entityClass : entityClasses) {
                System.out.println("Registering entity class: " + entityClass.getName());
                configuration.addAnnotatedClass(entityClass);
            }

            this.sessionFactory = configuration.buildSessionFactory();

            this.threadPool = Executors.newFixedThreadPool(poolSize);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Hibernate", e);
        }
    }

    // Static method to initialize the instance
    public static void initialize(String hostname, int port, String database, String user, String password, int poolSize) {
        if (instance == null) {
            instance = new SessionManager(hostname, port, database, user, password, poolSize);
        } else {
            throw new IllegalStateException("SessionManager is already initialized!");
        }
    }

    public ExecutorService getThreadPool() {
        return threadPool;
    }

    // Static method to access the instance
    public static SessionManager get() {
        if (instance == null) {
            throw new IllegalStateException("SessionManager is not initialized! Call initialize() first.");
        }
        return instance;
    }

    // Method to get a session
    public Session getSession() {
        return sessionFactory.openSession();
    }

    // Method to close the SessionFactory
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
