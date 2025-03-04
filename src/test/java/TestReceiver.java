import sh.fyz.architect.Architect;
import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.persistant.DatabaseCredentials;
import sh.fyz.architect.persistant.sql.provider.PostgreSQLAuth;

import java.util.UUID;

public class TestReceiver {

    public static void main(String[] args) {
        Architect architect = new Architect().setReceiver(true)
                .setDatabaseCredentials(new DatabaseCredentials(
                        new PostgreSQLAuth("localhost", 5432, "architect"), "test", "test", 6))
                .setRedisCredentials(new RedisCredentials("localhost", "1234", 6379,100, 6));
        architect.start();

        UUID randomUUID = UUID.fromString("67047805-2dac-42d5-b4a1-18dfcc9759d9");

        User user = new User();
        user.setUuid(randomUUID);
        user.setUsername("Fyz");
        user.setPassword("1234");

        UserRepository userRepository = new UserRepository();

        userRepository.save(user);

        User fetch = userRepository.findByUuid(randomUUID);

        System.out.println("Found : "+fetch.getUsername());

        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

}
