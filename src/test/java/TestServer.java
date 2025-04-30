import sh.fyz.architect.Architect;
import sh.fyz.utils.RedisCredentials;

import java.util.UUID;

public class TestServer {

    public static void main(String[] args) {
        Architect architect = new Architect().setReceiver(false)
                .setRedisCredentials(new RedisCredentials("localhost", "1234", 6379,100, 6));
        architect.start();
        architect.addRepositories(new UserRepository(), new RankRepository());

        UUID randomUUID = UUID.fromString("67047805-2dac-42d5-b4a1-18dfcc9759d9");
        UserRepository userRepository = new UserRepository();
        User fetch = userRepository.findById(randomUUID);

        System.out.println("ABN : "+fetch);

    }

}
