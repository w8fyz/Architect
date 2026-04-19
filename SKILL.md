---
name: architect-framework
description: >-
  Work with the Architect Java ORM framework: create entities, repositories, queries,
  configure database/Redis connections. Use when the user works with Architect, GenericRepository,
  QueryBuilder, IdentifiableEntity, or any sh.fyz.architect class.
---

# Architect Framework

Architect is a Java 21+ ORM built on Hibernate with optional Redis caching and pub/sub relay.

## Project Structure

```
src/main/java/sh/fyz/architect/
├── Architect.java                          # Entry point, configuration
├── entities/
│   ├── IdentifiableEntity.java             # Interface: Object getId()
│   └── DatabaseAction.java                 # Wrapper: entity + Type enum (SAVE, DELETE, NONE)
├── persistent/
│   ├── DatabaseCredentials.java            # DB config value object
│   ├── SessionManager.java                 # Hibernate singleton
│   ├── EnumCheckConstraintSynchronizer.java # Syncs enum CHECK constraints on PostgreSQL
│   └── sql/
│       ├── SQLAuthProvider.java            # Abstract JDBC provider (validates host/database)
│       ├── TlsMode.java                    # DISABLE / PREFER / REQUIRE / VERIFY_CA / VERIFY_FULL
│       └── provider/                       # H2, MariaDB, MySQL, PostgreSQL, SQLite
├── migration/
│   ├── MigrationManager.java              # Public API: create, execute, clear, list, inspect
│   ├── SchemaGenerator.java               # DDL generation via Hibernate metadata
│   ├── DatabaseInspector.java             # Table/column/data inspection via JDBC
│   └── MigrationToolGUI.java             # Swing GUI (dev tool, not for programmatic use)
├── cache/
│   ├── RedisCredentials.java               # Redis config value object
│   ├── RedisManager.java                   # Jedis singleton, keys prefixed architect:
│   ├── RedisQueueActionPool.java           # Async flush queue for cached repos
│   └── EntityChannelPubSub.java            # Pub/sub per entity type
└── repositories/
    ├── GenericRepository.java              # CRUD + query() entry point
    ├── GenericCachedRepository.java         # Redis-first, DB fallback
    ├── GenericRelayRepository.java          # Pub/sub relay for distributed setups
    ├── QueryBuilder.java                   # Fluent query API
    └── RepositoryRegistry.java             # Singleton name→repository map
```

## Creating an Entity

Must implement `IdentifiableEntity`, use `@Entity` + `@Table`, and have a no-arg constructor.

```java
@Entity
@Table(name = "users")
public class User implements IdentifiableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;

    public User() {}
    @Override public Long getId() { return id; }
    // getters/setters
}
```

## Bootstrap

```java
Architect architect = new Architect()
    .setReceiver(true)
    .setDatabaseCredentials(new DatabaseCredentials(
        new PostgreSQLAuth("localhost", 5432, "mydb"),
        "user", "password", 10, 10, "update"
    ));
architect.addEntityClass(User.class);
architect.start();
// ... use repositories ...
architect.stop();
```

`DatabaseCredentials` overloads:
- `(provider, user, pass, poolSize)` — defaults: threadPool=10, hbm2ddl="update"
- `(provider, user, pass, poolSize, threadPoolSize, hbm2ddlAuto)`

SQL providers: `PostgreSQLAuth`, `MySQLAuth`, `MariaDBAuth`, `H2Auth`, `SQLiteAuth`. Hostnames/databases are validated against `[A-Za-z0-9._-]`; SQLite paths are normalized.

Optional TLS via `withTls(TlsMode)` (network providers only — SQLite throws `UnsupportedOperationException`):

```java
new PostgreSQLAuth("db", 5432, "app").withTls(TlsMode.REQUIRE)
new MySQLAuth("db", 3306, "app").withTls(TlsMode.VERIFY_FULL)
```

## Repository Types

| Type | When to use |
|------|-------------|
| `GenericRepository<T>` | Direct DB access (default) |
| `GenericCachedRepository<T>` | Redis cache first, DB fallback |
| `GenericRelayRepository<T>` | Non-receiver instances, relay via pub/sub |

All share the same public API. Instantiate with `new XxxRepository<>(Entity.class)`.

## Custom Repositories (recommended pattern)

Create a dedicated class per entity to centralize query logic:

```java
public class UserRepository extends GenericRepository<User> {
    public UserRepository() { super(User.class); }

    public User findByEmail(String email) {
        return query().where("email", email).findFirst();
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
}
```

Usage: `UserRepository users = new UserRepository();`

Same pattern works with `GenericCachedRepository` and `GenericRelayRepository`.

## CRUD

```java
var repo = new GenericRepository<>(User.class);
User saved = repo.save(user);
User found = repo.findById(1L);
List<User> all = repo.all();
repo.delete(user);
```

Async: `saveAsync`, `findByIdAsync`, `allAsync`, `deleteAsync` (callbacks).

## QueryBuilder

Entry point: `repo.query()`. Conditions are ANDed.

### Operators

`EQ` (default), `NEQ`, `GT`, `GTE`, `LT`, `LTE`, `LIKE`, `IN`, `NOT_IN`, `IS_NULL`, `IS_NOT_NULL`.

### Methods

```java
.where("field", value)                          // EQ
.where("field", Operator.GT, value)             // comparison
.whereLike("field", "pattern%")
.whereIn("field", collection)
.whereNotIn("field", collection)
.whereNull("field")
.whereNotNull("field")
.whereRaw("HQL fragment", Map.of("param", val)) // raw HQL with named params (only overload as of 2.2.0)
.orderBy("field")                               // ASC
.orderBy("field", SortOrder.DESC)
.limit(n)
.offset(n)
```

### Terminal Operations

```java
List<T> results = query().where(...).findAll();
T first          = query().where(...).findFirst();
long count       = query().where(...).count();
int deleted      = query().where(...).delete();    // requires at least one condition
```

Async: `.findAllAsync()`, `.findFirstAsync()`, `.countAsync()`, `.deleteAsync()` return `CompletableFuture`.

## Redis (Optional)

```java
architect.setRedisCredentials(new RedisCredentials(host, password, port, timeout, maxConnections));

// With a default TTL (seconds) applied to every cached key. 0 = no expiry (default).
architect.setRedisCredentials(new RedisCredentials(host, password, port, timeout, maxConnections, 3600));
```

`RedisManager` keys are prefixed `architect:`. On receiver startup, all `architect:*` keys are cleared.

## Migration System

Package: `sh.fyz.architect.migration`. Generates schema snapshots as SQL files from entity definitions, executes them, clears the database, and inspects tables/data.

### MigrationManager

Requires `Architect` to be started first.

```java
MigrationManager manager = new MigrationManager(architect, Path.of("./migrations"));
```

#### Generate & create migrations

```java
String ddl = manager.generateSchema();            // DDL as string (preview)
Path file  = manager.createMigration("v1_init");  // writes ./migrations/v1_init.sql
```

The generated SQL is a full schema snapshot (all CREATE TABLE + enum CHECK constraints for PostgreSQL). It reflects the current entity definitions registered in Architect.

#### Execute a migration

```java
manager.executeMigration("v1_init");  // reads & executes ./migrations/v1_init.sql
manager.executeSql("ALTER TABLE ...");  // execute arbitrary SQL
```

Statements are executed in a single transaction with automatic rollback on error.

#### List available migrations

```java
List<String> files = manager.listMigrations();  // ["v1_init.sql", "v2_add_orders.sql"]
String content = manager.readMigrationContent("v1_init");  // raw SQL content
```

#### Clear the database

```java
manager.clearDatabase(MigrationManager.CLEAR_CONFIRMATION); // "CONFIRM_DROP_ALL"
```

The confirmation token is required (raises `IllegalArgumentException` otherwise). The no-arg overload is `@Deprecated` and logs a warning. Drops all tables. Strategy varies by dialect:
- PostgreSQL: `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`
- MySQL/MariaDB: disables FK checks, drops each table, re-enables FK checks
- H2: `DROP ALL OBJECTS`

#### Inspect the database

```java
List<TableInfo> tables = manager.listTables();                    // name, columnCount, rowCount
TableSchema schema     = manager.getTableSchema("users");         // columns, types, PKs, FKs
TableData data         = manager.getTableData("users", 0, 50);   // paginated rows (max 1000)
```

Table names are validated against `DatabaseMetaData` to prevent SQL injection.

### Typical production workflow

1. During development: use `hbm2ddlAuto = "update"` as usual
2. Before deploying: `manager.createMigration("v1_release")` to snapshot the schema
3. In production: set `hbm2ddlAuto = "none"` (mandatory), run `manager.executeMigration("v1_release")` on a clean database
4. Migrations are full snapshots (not incremental diffs like Flyway)

Migration filenames are validated and resolved against the migration directory; any path that escapes (`../`, absolute paths, etc.) is rejected with `IllegalArgumentException`.

### Key classes

| Class | Role |
|-------|------|
| `MigrationManager` | Public API: create, execute, clear, list, inspect |
| `SchemaGenerator` | DDL generation via Hibernate `SchemaManagementToolCoordinator` + enum constraints |
| `DatabaseInspector` | Table/column/data inspection via JDBC `DatabaseMetaData` |

## Key Rules

- Field names in `where()` and `orderBy()` are validated against the entity class; invalid names throw `IllegalArgumentException`.
- `query().delete()` without any condition throws `IllegalStateException`.
- `limit(-1)` or `offset(-1)` throw `IllegalArgumentException`.
- Always call `architect.stop()` on shutdown.
- `GenericCachedRepository.save()` on a new entity (id=null) is only allowed on receiver instances.
- `architect.start()` is idempotent — calling it twice is a no-op. If DB initialization fails after Redis was set up, Redis is rolled back automatically.
- `SessionManager.getSession()` rejects calls on a closed `SessionFactory` with a clear `IllegalStateException`.

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
