package sh.fyz.architect.persistent;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;

import jakarta.persistence.Entity;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.persistent.sql.SQLAuthProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SessionManager {

    private static final Logger LOG = Logger.getLogger(SessionManager.class.getName());
    private static volatile SessionManager instance;
    private static final Object LOCK = new Object();

    private SessionFactory sessionFactory;
    private final ConcurrentHashMap<String, Class<?>> registeredEntityClasses = new ConcurrentHashMap<>();
    private final ExecutorService threadPool;
    private SQLAuthProvider authProvider;

    private SessionManager(
            List<Class<? extends IdentifiableEntity>> manualEntities,
            SQLAuthProvider authProvider,
            String user,
            String password,
            int poolSize,
            int threadPoolSize,
            String hbm2ddlAuto
    ) {
        String jdbcUrl = authProvider != null ? authProvider.getUrl() : null;
        try {
            this.authProvider = authProvider;
            if (authProvider != null) {
                Properties settings = new Properties();
                settings.put(Environment.DRIVER, authProvider.getDriver());
                settings.put(Environment.URL, jdbcUrl);
                settings.put(Environment.USER, user);
                settings.put(Environment.PASS, password);
                settings.put(Environment.DIALECT, authProvider.getDialect());
                settings.put(Environment.HBM2DDL_AUTO, hbm2ddlAuto != null ? hbm2ddlAuto : "update");
                settings.put(Environment.SHOW_SQL, "false");
                settings.put(Environment.GLOBALLY_QUOTED_IDENTIFIERS, "true");

                Logger.getLogger("org.hibernate").setLevel(Level.WARNING);

                int maxPool = Math.max(1, poolSize);
                int minIdle = Math.max(1, maxPool / 4);
                settings.put("hibernate.hikari.minimumIdle", String.valueOf(minIdle));
                settings.put("hibernate.hikari.maximumPoolSize", String.valueOf(maxPool));
                settings.put("hibernate.hikari.idleTimeout", "600000");
                settings.put("hibernate.hikari.maxLifetime", "1800000");
                settings.put("hibernate.hikari.connectionTimeout", "30000");
                settings.put("hibernate.hikari.keepaliveTime", "300000");
                settings.put("hibernate.hikari.leakDetectionThreshold", "60000");

                settings.put("hibernate.jdbc.batch_size", "20");
                settings.put("hibernate.order_inserts", "true");
                settings.put("hibernate.order_updates", "true");

                settings.put("hibernate.jdbc.fetch_size", "50");
                settings.put("hibernate.default_batch_fetch_size", "16");

                settings.put("hibernate.generate_statistics", "false");

                Configuration configuration = new Configuration();
                configuration.setProperties(settings);

                if (manualEntities != null) {
                    for (Class<? extends IdentifiableEntity> entityClass : manualEntities) {
                        registerEntityClass(entityClass);
                    }
                }

                Set<String> registeredPackages = new HashSet<>();
                if (manualEntities != null) {
                    for (Class<? extends IdentifiableEntity> entityClass : manualEntities) {
                        registeredPackages.add(entityClass.getPackageName());
                    }
                }

                Set<Class<?>> entityClasses = scanEntities(registeredPackages);
                for (Class<?> entityClass : entityClasses) {
                    registeredEntityClasses.put(entityClass.getSimpleName(), entityClass);
                }

                this.sessionFactory = addEntitiesToConfiguration(configuration).buildSessionFactory();

                String effectiveHbm2ddl = hbm2ddlAuto != null ? hbm2ddlAuto : "update";
                if ("update".equals(effectiveHbm2ddl)) {
                    EnumCheckConstraintSynchronizer.synchronize(
                            this.sessionFactory,
                            registeredEntityClasses.values(),
                            authProvider.getDialect()
                    );
                }
            }
            this.threadPool = Executors.newVirtualThreadPerTaskExecutor();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Hibernate initialization failed", e);
            throw new RuntimeException("Failed to initialize Hibernate: " + redact(e.getMessage(), jdbcUrl));
        }
    }

    private static String redact(String message, String jdbcUrl) {
        if (message == null) return "<no message>";
        if (jdbcUrl == null || jdbcUrl.isEmpty()) return message;
        return message.replace(jdbcUrl, "[redacted-jdbc-url]");
    }

    public Class<?> getEntityClass(String name) {
        return registeredEntityClasses.get(name);
    }

    public boolean isRegisteredEntity(String name) {
        return registeredEntityClasses.containsKey(name);
    }

    public Collection<Class<?>> getRegisteredEntityClasses() {
        return Collections.unmodifiableCollection(registeredEntityClasses.values());
    }

    public SQLAuthProvider getSQLAuthProvider() {
        return authProvider;
    }

    public static void initialize(
            List<Class<? extends IdentifiableEntity>> entityClasses,
            SQLAuthProvider authProvider,
            String user,
            String password,
            int poolSize,
            int threadPoolSize,
            String hbm2ddlAuto
    ) {
        synchronized (LOCK) {
            if (instance == null) {
                instance = new SessionManager(entityClasses, authProvider, user, password, poolSize, threadPoolSize, hbm2ddlAuto);
            } else {
                throw new IllegalStateException("SessionManager is already initialized!");
            }
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            if (instance != null) {
                instance.close();
                instance = null;
            }
        }
    }

    public ExecutorService getThreadPool() {
        return threadPool;
    }

    public static SessionManager get() {
        SessionManager local = instance;
        if (local == null) {
            throw new IllegalStateException("SessionManager is not initialized! Call initialize() first.");
        }
        return local;
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    public Session getSession() {
        if (sessionFactory == null) {
            throw new IllegalStateException("SessionFactory is not available. No database credentials were provided.");
        }
        if (sessionFactory.isClosed()) {
            throw new IllegalStateException("SessionFactory is closed. Architect has been stopped.");
        }
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
            Thread.currentThread().interrupt();
        }
    }

    private void registerEntityClass(Class<?> entityClass) {
        registeredEntityClasses.put(entityClass.getSimpleName(), entityClass);
    }

    private Configuration addEntitiesToConfiguration(Configuration configuration) {
        for (Class<?> entityClass : registeredEntityClasses.values()) {
            configuration.addAnnotatedClass(entityClass);
        }
        return configuration;
    }

    private Set<Class<?>> scanEntities(Set<String> allowedPackages) {
        Set<Class<?>> entityClasses = new HashSet<>();

        ClassGraph classGraph = new ClassGraph().enableAnnotationInfo();
        if (!allowedPackages.isEmpty()) {
            classGraph.acceptPackages(allowedPackages.toArray(new String[0]));
        }

        try (ScanResult scanResult = classGraph.scan()) {
            scanResult.getClassesWithAnnotation(Entity.class.getName()).forEach(classInfo -> {
                try {
                    entityClasses.add(Class.forName(classInfo.getName()));
                } catch (ClassNotFoundException e) {
                    LOG.warning("Failed to load entity class: " + classInfo.getName());
                }
            });
        }

        return entityClasses;
    }
}
