**COMMENT UTILISER ARCHITECT ?**

# **Prérequis**

Une base de donnée **PostgreSQL** est nécessaire. Et pour l'utilisation du cache bien que ce soit optionnel, il est nécessaire d'utiliser **Redis**

# **Instance**
La première étape est de créer une instance d'Architect, pour ça la syntaxe est simple vous avez un builder intégré à la classe :

```java
        Architect architect = new Architect();

        architect.setReceiver(false); // Voir partie "Repositories - GenericRelayRepository"

        architect.setDatabaseCredentials(
                new DatabaseCredentials("hostname",
                        3306, "database", "root", "password", 10));

        architect.setRedisCredentials(new RedisCredentials("localhost",
                "password", 6379, 2000, 10));

        architect.start();
```

Une fois fait, l'instance d'Architect tourne ! Elle est thread-safe et symbolise juste la mise en place des connexions aux différentes DB.

# **Entitée**

Pour utiliser Architect, vous devez créer des entités, ce sont des **objets java** avec seulement 2 annotations et une implémentation en plus.
Par exemple, on va créer une entité "User" :


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

Ici, l'important ce situe dans l'annotation **@Entity**, qui défini cet objet java comme une entité qui doit être utilisable dans la base de donnée.
**@Table** permet de définir le nom de la table qui sera utilisé dans la base de donnée et l'interface **IdentifiableEntity** est un repère pour l'Id.

# **Repository**
Une Repository est en gros le manager des opérations que vous pourrez effectuer sur un type d'entitée, chaque Entitée possède un Repository propre.

Tout les repositories fonctionne foncièrement de la même manière et sont interchangeable en changeant le type.

Plusieurs type de Repository sont disponible :

1. **GenericRepository** - Le repository classique, il utilise les données de la base de donnée pur.
2. **GenericCachedRepository** - Un repository qui va utiliser en priorité les données de Redis puis les données de la base de donnée si elles ne sont pas sur Redis (et les rajouter).
3. **GenericRelayRepository** - Un repository a utiliser uniquement lors de l'utilisation d'une instance d'Architecte qui n'a pas directement accès la base de donnée.

Par exemple pour un serveur minecraft, si nous avons une instance Architect avec db + redis qui tourne sur notre bungeecord, on ne veux pas que tout les serveurs se connecte à la base de donnée simultanément pour éviter le surplus de connexion, alors sur le plugin du serveur spigot on utilise un GenericRelayRepository, qui envoie les requêtes à la base de donnée à partir de Redis pub/sub.
On utilise donc la base de donnée sans même y être connecté !

Pour notre système de User, voici un Repository d'exemple :

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

Ici, on utilise un Repository classique, GenericRepository avec en type **User**.
Le constructeur parent (super) demande la classe du même type, donc **User.class**

Et on peux maintenant intéragir avec la base de donnée en créant des fonctions utiles, par exemple :

```java
    public User findByName(String name) {
        return where("name", name);
    }
```
qui utilise la fonction parente "where" avec le field name, et qui permet au final d'utiliser :

**userRepository.findByName(nomDeLutilisateur);**

Vous pouvez aussi sauvegarder les données avec

**userRepository.save(user);**

Et voila ! Vous avez accès à votre système de base de donnée sans avoir à créer de table, ou d'utilitaire sql particulier, ici tout est géré par les fonctions parents.

Exemple complet :

```java
        Architect architect = new Architect().setReceiver(true)
                .setDatabaseCredentials(new DatabaseCredentials("host", 5432, "db", "user", "motdepasse", 10))
                .setRedisCredentials(new RedisCredentials("host", "motdepasse", 6379, 1000, 10));
        architect.start();

        UserRepository userRepository = new UserRepository(User.class);

        User user = new User("John Doe", "john.doe@email.fr", 17);

        userRepository.save(user);

        User fetchedUser = userRepository.findByName("John Doe");
        
        System.out.println("Fetched user: " + fetchedUser);

        architect.stop();
```

# **Mot de fin et important**

Des mises à jour sont à venir, si vous avez des bugs et questions n'hésitez pas.

# **ATTENTION :** n'oublier pas de fermer vos sessions à la fin du programme en faisant architect.stop() sur votre instance d'architect.
