package sh.fyz.architect.migration;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.tool.schema.spi.DelayedDropRegistry;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import sh.fyz.architect.persistent.EnumCheckConstraintSynchronizer;
import sh.fyz.architect.persistent.SessionManager;
import sh.fyz.architect.persistent.sql.SQLAuthProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;

public class SchemaGenerator {

    private final SQLAuthProvider authProvider;
    private final String user;
    private final String password;

    public SchemaGenerator(SQLAuthProvider authProvider, String user, String password) {
        this.authProvider = authProvider;
        this.user = user;
        this.password = password;
    }

    public String generateDDL() {
        Collection<Class<?>> entityClasses = SessionManager.get().getRegisteredEntityClasses();
        if (entityClasses.isEmpty()) {
            return "-- No entity classes registered\n";
        }

        Path tempFile = null;
        StandardServiceRegistry serviceRegistry = null;
        try {
            tempFile = Files.createTempFile("architect-migration-", ".sql");

            Map<String, Object> settings = new HashMap<>();
            settings.put(AvailableSettings.DRIVER, authProvider.getDriver());
            settings.put(AvailableSettings.URL, authProvider.getUrl());
            settings.put(AvailableSettings.USER, user);
            settings.put(AvailableSettings.PASS, password);
            settings.put(AvailableSettings.DIALECT, authProvider.getDialect());
            settings.put(AvailableSettings.GLOBALLY_QUOTED_IDENTIFIERS, "true");
            settings.put(AvailableSettings.HBM2DDL_AUTO, "none");
            settings.put(AvailableSettings.JAKARTA_HBM2DDL_SCRIPTS_ACTION, "create");
            settings.put(AvailableSettings.JAKARTA_HBM2DDL_SCRIPTS_CREATE_TARGET, tempFile.toAbsolutePath().toString());

            serviceRegistry = new StandardServiceRegistryBuilder()
                    .applySettings(settings)
                    .build();

            MetadataSources metadataSources = new MetadataSources(serviceRegistry);
            for (Class<?> entityClass : entityClasses) {
                metadataSources.addAnnotatedClass(entityClass);
            }
            Metadata metadata = metadataSources.buildMetadata();

            DelayedDropRegistry noOpDropRegistry = action -> {};
            SchemaManagementToolCoordinator.process(metadata, serviceRegistry, settings, noOpDropRegistry);

            StringBuilder ddl = new StringBuilder();
            ddl.append("-- Architect Migration Schema\n");
            ddl.append("-- Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n");
            ddl.append("-- Dialect: ").append(authProvider.getDialect()).append("\n");
            ddl.append("-- Entities: ").append(entityClasses.size()).append("\n");
            ddl.append("\n");

            String generatedSql = Files.readString(tempFile).trim();
            if (!generatedSql.isEmpty()) {
                ddl.append(generatedSql);
                ddl.append("\n");
            }

            List<String> enumConstraints = EnumCheckConstraintSynchronizer.generateEnumConstraintsDDL(
                    entityClasses, authProvider.getDialect()
            );
            if (!enumConstraints.isEmpty()) {
                ddl.append("\n-- Enum CHECK constraints\n");
                for (String stmt : enumConstraints) {
                    ddl.append(stmt).append(";\n");
                }
            }

            return ddl.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate schema DDL", e);
        } finally {
            if (serviceRegistry != null) {
                StandardServiceRegistryBuilder.destroy(serviceRegistry);
            }
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
