package fr.freshperf.architect;

import fr.freshperf.architect.cache.RedisCredentials;
import fr.freshperf.architect.persistant.DatabaseCredentials;
import fr.freshperf.architect.persistant.SessionManager;
import fr.freshperf.architect.repositories.GenericRelayRepository;
import fr.freshperf.models.User;

public class Main {

    public static void main(String[] args) {
        Architect architect = new Architect().setReceiver(true)
                .setDatabaseCredentials(new DatabaseCredentials("147.79.21.64", 5432, "w8world", "api", "r65]3E&a>p3_", 10, "fr.freshperf.models"))
                .setRedisCredentials(new RedisCredentials("147.79.21.64", "efkj4334kfoe5434L%V34", 6379, 1000, 10));
        architect.start();

        GenericRelayRepository<User> userRepository = new UserRepository(User.class);

        User user = new User("John Doe", "john.doe@email.fr", 17);

        userRepository.save(user);

        architect.stop();

    }

}
