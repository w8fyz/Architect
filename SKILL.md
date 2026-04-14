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
├── persistant/
│   ├── DatabaseCredentials.java            # DB config value object
│   ├── SessionManager.java                 # Hibernate singleton
│   └── sql/
│       ├── SQLAuthProvider.java            # Abstract JDBC provider
│       └── provider/                       # H2, MariaDB, MySQL, PostgreSQL, SQLite
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

SQL providers: `PostgreSQLAuth`, `MySQLAuth`, `MariaDBAuth`, `H2Auth`, `SQLiteAuth`.

## Repository Types

| Type | When to use |
|------|-------------|
| `GenericRepository<T>` | Direct DB access (default) |
| `GenericCachedRepository<T>` | Redis cache first, DB fallback |
| `GenericRelayRepository<T>` | Non-receiver instances, relay via pub/sub |

All share the same public API. Instantiate with `new XxxRepository<>(Entity.class)`.

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
.whereRaw("HQL fragment", Map.of("param", val)) // raw HQL with named params
.whereRaw("active = true")                      // raw HQL without params
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
```

`RedisManager` keys are prefixed `architect:`. On receiver startup, all `architect:*` keys are cleared.

## Key Rules

- Field names in `where()` and `orderBy()` are validated against the entity class; invalid names throw `IllegalArgumentException`.
- `query().delete()` without any condition throws `IllegalStateException`.
- `limit(-1)` or `offset(-1)` throw `IllegalArgumentException`.
- Always call `architect.stop()` on shutdown.
- `GenericCachedRepository.save()` on a new entity (id=null) is only allowed on receiver instances.

## Testing

Tests use JUnit 5 with a PostgreSQL service. CI workflow at `.github/workflows/tests.yml` launches Postgres and runs `./gradlew test`.

For full API details, see [README.md](../../../README.md).
