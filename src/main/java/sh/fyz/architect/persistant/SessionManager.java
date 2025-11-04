package sh.fyz.architect.persistant;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.Annotation;
import javassist.bytecode.annotation.EnumMemberValue;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;

import jakarta.persistence.Entity;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.persistant.sql.SQLAuthProvider;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SessionManager {
    private static SessionManager instance;
    private SessionFactory sessionFactory;

    private final HashMap<String, Class<?>> registeredEntityClasses = new HashMap<>();
    private final ExecutorService threadPool;

    private SessionManager(HashMap<ClassLoader, Class<? extends IdentifiableEntity>> manualEntities, SQLAuthProvider authProvider, String user, String password, int poolSize) {
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
                settings.put(Environment.GLOBALLY_QUOTED_IDENTIFIERS, "true");

                Logger.getLogger("org.hibernate").setLevel(Level.WARNING);

                int maxPool = Math.max(1, poolSize);
                int minIdle = Math.max(1, maxPool / 2);
                settings.put("hibernate.hikari.minimumIdle", String.valueOf(minIdle));
                settings.put("hibernate.hikari.maximumPoolSize", String.valueOf(maxPool));
                settings.put("hibernate.hikari.idleTimeout", "30000");
                Configuration configuration = new Configuration();
                configuration.setProperties(settings);
                manualEntities.forEach(this::registerEntityClass);
                Set<Class<?>> entityClasses = scanEntities();
                for (Class<?> entityClass : entityClasses) {
                    System.out.println("Registering entity class: " + entityClass.getName());
                    registeredEntityClasses.put(entityClass.getSimpleName(), entityClass);
                }


                this.sessionFactory = addEntitiesToConfiguration(configuration).buildSessionFactory();
            }
            this.threadPool = Executors.newFixedThreadPool(poolSize);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Hibernate", e);
        }
    }



    public Class<?> getEntityClass(String name) {
        return registeredEntityClasses.get(name);
    }

    public static void initialize(HashMap<ClassLoader, Class<? extends IdentifiableEntity>> entityClasses, SQLAuthProvider authProvider, String user, String password, int poolSize) {
        if (instance == null) {
            instance = new SessionManager(entityClasses, authProvider, user, password, poolSize);
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

    public void registerEntityClass(ClassLoader classLoader, Class<?> entityClass) {
        System.out.println("Registering entity class: " + entityClass.getName()+" manually");
        try {
            classLoader.loadClass(entityClass.getPackageName());
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        registeredEntityClasses.put(entityClass.getSimpleName(), entityClass);
    }

    private Configuration addEntitiesToConfiguration(Configuration configuration) {
        for (Class<?> entityClass : registeredEntityClasses.values()) {
            System.out.println("Configuring entity class: " + entityClass.getName());
            configuration.addAnnotatedClass(entityClass);
        }
        return configuration;
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
