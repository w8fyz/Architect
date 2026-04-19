package sh.fyz.architect.examples;

import sh.fyz.architect.Architect;
import sh.fyz.architect.migration.MigrationToolGUI;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;
import sh.fyz.architect.repositories.GenericRepository;

import java.nio.file.Path;

/**
 * Run this main() to start the Migration Tool GUI manually.
 * Requires a PostgreSQL instance running on the configured host/port.
 *
 * <p>This is a demo, not a unit test. It lives in {@code src/examples/} so it is
 * never bundled in the published jar.</p>
 */
public class MigrationToolDemo {

    private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final int DB_PORT = Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5440"));
    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "architect_test");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "architect");
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "architect");

    public static void main(String[] args) {
        Architect architect = new Architect()
                .setReceiver(true)
                .setDatabaseCredentials(new DatabaseCredentials(
                        new PostgreSQLAuth(DB_HOST, DB_PORT, DB_NAME),
                        DB_USER, DB_PASS, 2, 2, "update"
                ));

        architect.addEntityClass(Product.class);
        architect.start();
        System.out.println("Architect started.");

        GenericRepository<Product> productRepo = new GenericRepository<>(Product.class);
        architect.addRepositories(productRepo);

        seedSampleData(productRepo);

        Path migrationsDir = Path.of("./test-migrations");
        System.out.println("Opening Migration Tool GUI (migrations dir: " + migrationsDir.toAbsolutePath() + ")");

        MigrationToolGUI.open(architect, migrationsDir);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down Architect...");
            architect.stop();
        }));
    }

    private static void seedSampleData(GenericRepository<Product> repo) {
        if (!repo.all().isEmpty()) {
            System.out.println("Sample data already present, skipping seed.");
            return;
        }

        repo.save(new Product("MacBook Pro 16\"", "Laptops", 2499.99, 42, true));
        repo.save(new Product("iPhone 15 Pro", "Phones", 1199.00, 128, true));
        repo.save(new Product("AirPods Max", "Audio", 549.00, 67, true));
        repo.save(new Product("iPad Air", "Tablets", 799.00, 95, true));
        repo.save(new Product("Apple Watch Ultra", "Wearables", 899.00, 33, true));
        repo.save(new Product("Magic Keyboard", "Accessories", 299.00, 210, true));
        repo.save(new Product("Studio Display", "Monitors", 1599.00, 18, false));
        repo.save(new Product("Mac Mini M2", "Desktops", 599.00, 74, true));

        System.out.println("Seeded 8 sample products.");
    }
}
