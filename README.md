# **HOW TO USE ARCHITECT?**

## **Prerequisites**

A **SQL** database is required. Additionally, for caching (optional but recommended), **Redis** is needed.

You will need to use a **SQLAuthProvider** in the database credentials.
Choose it accordingly of what your database need, currenctly these drivers are supported :

- **H2Auth** (H2 Database)
- **MariaDBAuth** (MariaDB Database)
- **MySQLAuth** (MySQL Database)
- **PostgreSQLAuth** (PostgreSQL Database)
- **SQLiteAuth** (SQLite Database)

## **Instance**

The first step is to create an instance of Architect. The syntax is simple, as a builder is integrated into the class:

```java
Architect architect = new Architect();

architect.setReceiver(false); // See "Repositories - GenericRelayRepository" section

architect.setDatabaseCredentials(new DatabaseCredentials(
        new MySQLAuth("hostname",3306,"database") ,"username","password", 12));

architect.setRedisCredentials(new RedisCredentials("localhost",
        "password", 6379, 2000, 10));

architect.start();
```

Once this is done, the Architect instance is up and running! It is thread-safe and essentially sets up connections to the various databases.

# **Entity**

To use Architect, you need to create entities. These are Java objects with just two annotations and one additional implementation.
For example, let’s create a "User" entity:


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

    public User(String name, String email, int age) {
        this.name = name;
        this.email = email;
        this.age = age;
    }

    public User() {
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", email='" + email + '\'' +
                ", age=" + age +
                '}';
    }
}
```

Here, the key part is the **@Entity** annotation, which marks this Java object as an entity usable with the database.
The **@Table** annotation specifies the table name in the database, and the IdentifiableEntity interface serves as a reference for the ID.
You also **need** an empty constructor for your entity, like "User();"

# **Repository**
A Repository is essentially the manager of operations you can perform on an entity type. Each entity has its own dedicated Repository.

All repositories work in fundamentally the same way and can be swapped by changing their type.
The following types of repositories are available:

- GenericRepository - A standard repository that works directly with the database.
- GenericCachedRepository - A repository that prioritizes Redis for data, falling back to the database if the data isn’t in Redis (and then caching it).
- GenericRelayRepository - A repository to use only with an Architect instance that doesn’t directly access the database.

For example, in a Minecraft server setup, if we have an Architect instance with a database and Redis running on the BungeeCord server, we don’t want all servers connecting to the database simultaneously to avoid connection overload. Instead, on the Spigot server plugin, we use a GenericRelayRepository, which sends database queries via Redis pub/sub.
This allows database interaction without direct connections!

Here’s an example Repository for the User system:

```java
public class UserRepository extends GenericRepository<User> {
    public UserRepository(Class<User> type) {
        super(type);
    }

    public User findByName(String name) {
        return where("name", name);
    }

    public void findByNameAsync(String name, Consumer<User> action) {
        whereAsync("name", name, action, (user) -> {});
    }
}
```

Here, we use a standard GenericRepository with the type **User**.
The parent constructor (super) requires the same type class, i.e., **User.class**.

Now, we can interact with the database by creating useful methods, such as:

```java
    public User findByName(String name) {
        return where("name", name);
    }
```
This uses the parent where function with the name field, enabling calls like:

**userRepository.findByName(userName);**

You can also save data using:

**userRepository.save(user);**

And that’s it! You now have access to your database system without having to create tables or specialized SQL utilities. Everything is managed by the parent functions.

Complete example:

```java
        Architect architect = new Architect().setReceiver(true)
                .setDatabaseCredentials(new DatabaseCredentials(
                        new MySQLAuth("hostname",3306,"database") ,"username","password", 12));
                .setRedisCredentials(new RedisCredentials("host", "motdepasse", 6379, 1000, 10));
        architect.start();

        UserRepository userRepository = new UserRepository(User.class);

        User user = new User("John Doe", "john.doe@email.fr", 17);

        userRepository.save(user);

        User fetchedUser = userRepository.findByName("John Doe");
        
        System.out.println("Fetched user: " + fetchedUser);

        architect.stop();
```

# **Closing Notes and Important Reminder**

Updates are on the way. If you encounter bugs or have questions, don’t hesitate to reach out.
**IMPORTANT: Always close your sessions at the end of the program by calling architect.stop() on your Architect instance.**
