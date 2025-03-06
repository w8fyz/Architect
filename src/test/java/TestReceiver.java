import sh.fyz.architect.Architect;
import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.persistant.DatabaseCredentials;
import sh.fyz.architect.persistant.sql.provider.PostgreSQLAuth;

import java.util.UUID;

public class TestReceiver {

    public static void main(String[] args) {
        Architect architect = new Architect().setReceiver(true)
                .setDatabaseCredentials(new DatabaseCredentials(
                        new PostgreSQLAuth("localhost", 5432, "architect"), "postgres", "", 6))
                .setRedisCredentials(new RedisCredentials("localhost", "1234", 6379,100, 6));
        architect.start();
        architect.addRepositories(new UserRepository(), new RankRepository());

        UUID randomUUID = UUID.fromString("67047805-2dac-42d5-b4a1-18dfcc9759d9");
        RankRepository rankRepository = new RankRepository();

        Rank rank = new Rank();
        rank.setName("Admin");
        rank.setPower(10);
        rankRepository.save(rank);

        UserRepository userRepository = new UserRepository();

        User fetch = userRepository.findByUuid(randomUUID);
        fetch.setUuid(randomUUID);
        fetch.setUsername("Fyz");
        fetch.setPassword("1234");
        fetch.setRank(rank);



        userRepository.save(fetch);

        User firstFetch = userRepository.findByUuid(randomUUID);

        System.out.println("Found : "+firstFetch.getUsername());

    }

}
