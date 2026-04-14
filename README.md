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
    implementation 'sh.fyz:architect:2.0.0'
}
```

### Maven

```xml
<dependency>
    <groupId>sh.fyz</groupId>
    <artifactId>architect</artifactId>
    <version>2.0.0</version>
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

### DatabaseCredentials

```java
// Minimal (defaults: threadPoolSize=10, hbm2ddl="update")
new DatabaseCredentials(provider, user, password, poolSize)

// Full control
new DatabaseCredentials(provider, user, password, poolSize, threadPoolSize, hbm2ddlAuto)
```

`hbm2ddlAuto` values: `"update"` (default), `"create"`, `"create-drop"`, `"validate"`, `"none"`.

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

Extend any repository type to add domain-specific methods:

```java
public class UserRepository extends GenericRepository<User> {
    public UserRepository() {
        super(User.class);
    }

    public User findByEmail(String email) {
        return query().where("email", email).findFirst();
    }

    public List<User> findAdults() {
        return query().where("age", Operator.GTE, 18).findAll();
    }
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

// Without parameters
query().whereRaw("active = true")

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

## Lifecycle

Always shut down Architect when your application stops:

```java
architect.stop();
```

This closes Hibernate sessions, shuts down thread pools, and disconnects from Redis.

## License

MIT
