import sh.fyz.architect.Architect;
import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.persistant.DatabaseCredentials;
import sh.fyz.architect.persistant.sql.provider.PostgreSQLAuth;

import java.util.UUID;

public class TestServer {

    public static void main(String[] args) {
        Architect architect = new Architect().setReceiver(false)
                .setRedisCredentials(new RedisCredentials("localhost", "1234", 6379,100, 6));
        architect.start();

        UUID randomUUID = UUID.fromString("67047805-2dac-42d5-b4a1-18dfcc9759d9");
        UserRepository userRepository = new UserRepository();
        User firstFetch = userRepository.findByUuid(randomUUID);

        System.out.println("Found first : "+firstFetch.getUsername());
        firstFetch.setUsername("Fyzil");
        firstFetch.setPassword("1234");


        userRepository.save(firstFetch);

        User fetch = userRepository.findByUuid(randomUUID);

        System.out.println("Found : "+fetch.getUsername());

    }

}
