# Architect

A lightweight Java ORM framework built on top of Hibernate, with optional Redis caching and pub/sub relay for distributed architectures.

## Requirements

- **Java 21+**
- A SQL database (PostgreSQL, MySQL, MariaDB, H2, SQLite)
- Redis (optional, for caching and relay)

## Installation

### Gradle

```groovy
dependencies {
    implementation 'sh.fyz:Architect:2.2.0'
}
```

### Maven

```xml
<dependency>
    <groupId>sh.fyz</groupId>
    <artifactId>Architect</artifactId>
    <version>2.2.0</version>
</dependency>
```

## Quick Start

```java
Architect architect = new Architect()
    .setReceiver(true)
    .setDatabaseCredentials(new DatabaseCredentials(
        new PostgreSQLAuth("localhost", 5432, "mydb"),
        "user", "password", 10
    ));

architect.addEntityClass(User.class);
architect.start();

GenericRepository<User> users = new GenericRepository<>(User.class);

User user = new User("John", "john@example.com", 25);
users.save(user);

User found = users.query()
    .where("email", "john@example.com")
    .findFirst();

architect.stop();
```

## Entities

Entities must implement `IdentifiableEntity` and use JPA annotations. A no-arg constructor is required.

```java
@Entity
@Table(name = "users")
public class User implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String email;
    private int age;

    public User() {}

    public User(String name, String email, int age) {
        this.name = name;
        this.email = email;
        this.age = age;
    }

    @Override
    public Long getId() { return id; }

    // getters and setters...
}
```

## Configuration

### Architect Instance

```java
Architect architect = new Architect();

// Required: this instance writes to the database
architect.setReceiver(true);

// Required: database connection
architect.setDatabaseCredentials(new DatabaseCredentials(
    new PostgreSQLAuth("localhost", 5432, "mydb"),
    "user", "password",
    10,   // connection pool size
    10,   // thread pool size
    "update" // hbm2ddl.auto strategy
));

// Optional: Redis for caching / relay
architect.setRedisCredentials(new RedisCredentials(
    "localhost", "password", 6379, 2000, 10
));

// Register entity classes
architect.addEntityClass(User.class);
architect.addEntityClass(Product.class);

architect.start();
```

### SQL Providers

| Provider | Constructor |
|---|---|
| `PostgreSQLAuth` | `(host, port, database)` |
| `MySQLAuth` | `(host, port, database)` |
| `MariaDBAuth` | `(host, port, database)` |
| `H2Auth` | `(host, port, database)` |
| `SQLiteAuth` | `(databasePath)` |

Hostname and database identifiers are validated against `[A-Za-z0-9._-]` to prevent JDBC URL injection. SQLite paths are normalized and reject URL schemes / illegal characters.

#### TLS

All network providers (PostgreSQL, MySQL, MariaDB, H2) accept a fluent `withTls(TlsMode)` call. SQLite is local-only and rejects TLS configuration.

```java
new PostgreSQLAuth("db.example.com", 5432, "app").withTls(TlsMode.REQUIRE)
new MySQLAuth("db.example.com", 3306, "app").withTls(TlsMode.VERIFY_FULL)
```

`TlsMode` values: `DISABLE` (default, backwards-compatible), `PREFER`, `REQUIRE`, `VERIFY_CA`, `VERIFY_FULL`. Each provider translates the mode to the dialect-specific URL parameters.

### DatabaseCredentials

```java
// Minimal (defaults: threadPoolSize=10, hbm2ddl="update")
new DatabaseCredentials(provider, user, password, poolSize)

// Full control
new DatabaseCredentials(provider, user, password, poolSize, threadPoolSize, hbm2ddlAuto)
```

`hbm2ddlAuto` values: `"update"` (default), `"create"`, `"create-drop"`, `"validate"`, `"none"`.

> **Production**: always use `"none"` and manage schema changes through the migration system below. `"update"` is convenient for development but is not safe to run against a live database.

### Redis TTL

`RedisCredentials` accepts an optional default TTL (seconds) applied to every cached entity. `0` (default) means no expiry. Set a positive value in production to cap memory usage:

```java
new RedisCredentials("localhost", "password", 6379, 2000, 10, /* defaultTtlSeconds */ 3600)
```

Per-key TTL overrides are still possible via `RedisManager.get().setTTL(key, seconds)`.

## Repositories

### GenericRepository

Standard database access with Hibernate sessions.

```java
GenericRepository<User> users = new GenericRepository<>(User.class);

// CRUD
User saved = users.save(user);
User found = users.findById(1L);
List<User> all = users.all();
users.delete(user);

// Async variants
users.saveAsync(user, onSuccess, onError);
users.findByIdAsync(1L, onSuccess, onError);
users.allAsync(onSuccess, onError);
users.deleteAsync(user, onSuccess, onError);
```

### GenericCachedRepository

Redis-first reads. Falls back to database on cache miss, then populates the cache. Writes are queued and flushed to the database periodically.

```java
GenericCachedRepository<User> users = new GenericCachedRepository<>(User.class);
```

Same API as `GenericRepository`. Automatically resolves `@ManyToOne`, `@OneToMany`, and `@OneToOne` relations from cache.

### GenericRelayRepository

For distributed setups. Publishes save/delete operations via Redis pub/sub to a receiver instance, instead of writing to the database directly.

```java
// On a non-receiver instance (e.g. game server)
Architect architect = new Architect()
    .setReceiver(false)
    .setRedisCredentials(new RedisCredentials(...));
architect.start();

GenericRelayRepository<User> users = new GenericRelayRepository<>(User.class);
users.save(user); // sent via Redis pub/sub to the receiver
```

### Custom Repositories

The recommended way to use Architect is to create a dedicated repository class per entity. This keeps all query logic centralized and reusable:

```java
public class UserRepository extends GenericRepository<User> {

    public UserRepository() {
        super(User.class);
    }

    public User findByEmail(String email) {
        return query().where("email", email).findFirst();
    }

    public List<User> findAdults() {
        return query()
            .where("age", Operator.GTE, 18)
            .orderBy("name")
            .findAll();
    }

    public List<User> findActiveByCountry(String country) {
        return query()
            .where("country", country)
            .where("active", true)
            .orderBy("createdAt", SortOrder.DESC)
            .findAll();
    }

    public List<User> search(String keyword, int page, int pageSize) {
        return query()
            .whereLike("name", "%" + keyword + "%")
            .orderBy("name")
            .limit(pageSize)
            .offset(page * pageSize)
            .findAll();
    }

    public long countByRole(String role) {
        return query().where("role", role).count();
    }

    public int deactivateUnverified() {
        return query()
            .where("verified", false)
            .whereRaw("createdAt < :cutoff", Map.of("cutoff", someDateValue))
            .delete();
    }
}
```

Then use it:

```java
UserRepository users = new UserRepository();

User john = users.findByEmail("john@example.com");
List<User> adults = users.findAdults();
List<User> page2 = users.search("doe", 1, 25);
```

You can also extend `GenericCachedRepository` or `GenericRelayRepository` the same way:

```java
public class UserCachedRepository extends GenericCachedRepository<User> {
    public UserCachedRepository() {
        super(User.class);
    }
    // same custom methods, backed by Redis cache
}
```

### RepositoryRegistry

Register repositories for cross-repository lookups (used by cached/relay repos for relation resolution):

```java
architect.addRepositories(
    new GenericRepository<>(User.class),
    new GenericRepository<>(Product.class)
);

// Manual access
RepositoryRegistry.get().getRepository("users");
```

## QueryBuilder

Entry point: `repository.query()`. All conditions are combined with `AND`.

### Filtering

```java
// Equality
query().where("name", "John")

// Comparison operators: EQ, NEQ, GT, GTE, LT, LTE
query().where("age", Operator.GTE, 18)

// Pattern matching
query().whereLike("name", "John%")

// Set membership
query().whereIn("category", List.of("A", "B", "C"))
query().whereNotIn("status", List.of("banned", "suspended"))

// Null checks
query().whereNull("deletedAt")
query().whereNotNull("email")

// Multiple conditions (AND)
query()
    .where("category", "Electronics")
    .where("price", Operator.LT, 100.0)
    .where("active", true)
```

### Raw HQL

For complex expressions not covered by the builder:

```java
// With named parameters
query().whereRaw("LOWER(name) = :val", Map.of("val", "john"))

// BETWEEN
query().whereRaw("price BETWEEN :min AND :max", Map.of("min", 10.0, "max", 100.0))

// Combined with builder conditions
query()
    .where("category", "Electronics")
    .whereRaw("LENGTH(name) > :len", Map.of("len", 5))
```

### Sorting

```java
query().orderBy("name")                        // ASC (default)
query().orderBy("price", SortOrder.DESC)       // DESC
query().orderBy("category").orderBy("name")    // multi-column
```

### Pagination

```java
query().limit(10)                   // first 10 results
query().limit(10).offset(20)       // page 3 (10 per page)

query()
    .where("active", true)
    .orderBy("createdAt", SortOrder.DESC)
    .limit(25)
    .offset(0)
    .findAll();
```

### Terminal Operations

```java
List<User> users = query().where("active", true).findAll();
User user         = query().where("email", "john@example.com").findFirst();
long count        = query().where("category", "Books").count();
int deleted       = query().where("active", false).delete();
```

### Async Terminal Operations

All terminal operations have async variants returning `CompletableFuture`:

```java
CompletableFuture<List<User>> users = query().where("active", true).findAllAsync();
CompletableFuture<User> user        = query().where("email", "x").findFirstAsync();
CompletableFuture<Long> count       = query().where("category", "A").countAsync();
CompletableFuture<Integer> deleted  = query().where("active", false).deleteAsync();
```

## Operators Reference

| Operator | Description | Example |
|---|---|---|
| `EQ` | Equal (default) | `.where("name", "John")` |
| `NEQ` | Not equal | `.where("status", Operator.NEQ, "banned")` |
| `GT` | Greater than | `.where("age", Operator.GT, 18)` |
| `GTE` | Greater than or equal | `.where("price", Operator.GTE, 9.99)` |
| `LT` | Less than | `.where("stock", Operator.LT, 5)` |
| `LTE` | Less than or equal | `.where("rating", Operator.LTE, 3)` |
| `LIKE` | SQL LIKE pattern | `.whereLike("name", "%john%")` |
| `IN` | In collection | `.whereIn("id", List.of(1, 2, 3))` |
| `NOT_IN` | Not in collection | `.whereNotIn("status", List.of("x"))` |
| `IS_NULL` | Is null | `.whereNull("deletedAt")` |
| `IS_NOT_NULL` | Is not null | `.whereNotNull("email")` |

## Migration System

Architect includes a built-in migration tool to capture your schema as SQL files, execute them, clear the database, or inspect its contents. This is useful for production deployments where `hbm2ddl.auto` should be set to `"none"`.

### Setup

```java
MigrationManager manager = new MigrationManager(architect, Path.of("./migrations"));
```

### Create a migration

Generates a full SQL schema snapshot from your entity definitions:

```java
String ddl = manager.generateSchema();           // preview the DDL
Path file  = manager.createMigration("v1_init"); // saves to ./migrations/v1_init.sql
```

### Execute a migration

```java
manager.executeMigration("v1_init");
```

Reads the `.sql` file and executes all statements in a single transaction with automatic rollback on error.

### Clear the database

```java
manager.clearDatabase(MigrationManager.CLEAR_CONFIRMATION); // "CONFIRM_DROP_ALL"
```

Drops all tables. Supports PostgreSQL, MySQL, MariaDB, H2, and SQLite with dialect-specific strategies. The confirmation token is required to prevent accidental destructive calls. The no-argument overload is `@Deprecated` and logs a warning; it will be removed in a future major release.

### Inspect the database

```java
List<TableInfo> tables = manager.listTables();
TableSchema schema     = manager.getTableSchema("users");
TableData data         = manager.getTableData("users", 0, 50);
```

### List migrations

```java
List<String> files = manager.listMigrations();
String sql         = manager.readMigrationContent("v1_init");
```

### GUI (development)

A Swing-based graphical interface is available for interactive use during development:

```java
MigrationToolGUI.open(architect, Path.of("./migrations"));
```

This opens a desktop window with tabs for Create, Execute, Clear, and View operations. It runs independently of the application console and does not block the calling thread.

### Production workflow

1. **Development**: use `hbm2ddlAuto = "update"` as usual
2. **Pre-deploy**: call `manager.createMigration("v1_release")` to snapshot the schema
3. **Production**: set `hbm2ddlAuto = "none"` and run `manager.executeMigration("v1_release")`

Migrations are full schema snapshots (not incremental). Each `.sql` file creates the complete schema from scratch.

## Lifecycle

Always shut down Architect when your application stops:

```java
architect.stop();
```

This closes Hibernate sessions, shuts down thread pools, and disconnects from Redis.

## What's new in 2.2.0

Non-breaking security and integrity hardening:

- **Security**: JDBC URL injection closed (`SQLAuthProvider` whitelists hostnames/databases, `SQLiteAuth` normalizes paths and rejects URL schemes), new `withTls(TlsMode)` on every network provider, path-traversal guard on `MigrationManager.executeMigration` / `readMigrationContent`, `clearDatabase(String confirmation)` requires a `"CONFIRM_DROP_ALL"` token, `parseSqlStatements` now correctly handles PostgreSQL dollar-quoted strings (`$$` / `$tag$`).
- **Cache integrity**: `executeDelete` now hits the database before invalidating the cache (so a DB failure no longer wipes the cache), `flushUpdates` rolls back the transaction and re-enqueues the failed batch in order, `RedisManager.reconstructEntity` and `GenericCachedRepository.resolveRelations` no longer double-resolve `@ManyToOne` / `@OneToOne` relations.
- **Pub/sub**: `EntityChannelPubSub` now reconnects automatically with exponential backoff on `JedisException` instead of dying silently.
- **Concurrency**: `GenericRepository` resolves the thread pool from `SessionManager` on every call (no stale reference after `stop()` / `start()`), `QueryBuilder.findFirst()` no longer mutates the builder's `limit` (thread-safe), `delete(entity)` uses `session.merge()` so detached entities can be removed.
- **Lifecycle**: `Architect.start()` is now idempotent and rolls back Redis init if the database init throws; `SessionManager.getSession()` rejects calls on a closed factory.
- **Quality**: `RedisCredentials.defaultTtlSeconds`, cached `LIKE` `Pattern`s, fixed direction of `compareValues`'s `isAssignableFrom`, redacted JDBC URL in init exceptions, `System.err.println` replaced with `java.util.logging` everywhere, deprecated `whereRaw(String)` removed (use the `Map<String,Object>` overload), Swing `MigrationToolDemo` moved to a dedicated `examples` source set, tests modernized with Awaitility.

## License

MIT
