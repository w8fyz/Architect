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
        architect.addRepositories(new UserRepository(), new RankRepository());

        UUID randomUUID = UUID.fromString("67047805-2dac-42d5-b4a1-18dfcc9759d9");
        UserRepository userRepository = new UserRepository();

        User firstFetch = userRepository.findById(randomUUID);
        System.out.println("Found first : "+firstFetch.getUsername());

        RankRepository rankRepository = new RankRepository();
        Rank rank = rankRepository.findById(3);
        rank.setName("Modo");
        rankRepository.save(rank);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Rank : "+firstFetch.getRank());

        User fetch = userRepository.findById(randomUUID);

        System.out.println("Found : "+fetch.getUsername());

    }

}
