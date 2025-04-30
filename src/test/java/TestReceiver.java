import sh.fyz.architect.Architect;
import sh.fyz.utils.RedisCredentials;
import sh.fyz.architect.persistant.DatabaseCredentials;
import sh.fyz.architect.persistant.sql.provider.PostgreSQLAuth;

import java.util.UUID;

public class TestReceiver {
    public static void main(String[] args) {
        Architect architect = new Architect().setReceiver(true)
                .setDatabaseCredentials(new DatabaseCredentials(
                        new PostgreSQLAuth("localhost", 5432, "architect"), "postgres", "[REDACTED]", 6))
                .setRedisCredentials(new RedisCredentials("localhost", "1234", 6379,100, 6));
        architect.start();
        architect.addRepositories(new UserRepository(), new RankRepository(), new FriendRepository());
        long start = System.currentTimeMillis();
        System.out.println("Started");
        UUID randomUUID = UUID.fromString("67047805-2dac-42d5-b4a1-18dfcc9759d9");
        UUID randomUUID2 = UUID.fromString("11111111-2dac-42d5-b4a1-222222222222");
        UUID randomUUID3 = UUID.fromString("22222222-2dac-42d5-b4a1-111111111111");

        FriendRepository friendRepository = new FriendRepository();
        Friend friend1 = new Friend();
        friend1.setId(randomUUID2);
        friend1.setName("Bob");
        friend1.setLevel(1);

        Friend friend2 = new Friend();
        friend2.setId(randomUUID3);
        friend2.setName("Steve");
        friend2.setLevel(2);

        friendRepository.save(friend1);

        RankRepository rankRepository = new RankRepository();

        Rank rank = new Rank();
        rank.setName("Apagnan");
        rankRepository.save(rank);

        UserRepository userRepository = new UserRepository();

        User fetch = new User();
        fetch.setUuid(randomUUID2);
        fetch.setUsername("Sarah");
        fetch.setPassword("1234");
        fetch.setRank(rankRepository.findById(1));
        fetch.addFriend(friend1);

        userRepository.saveAsync(fetch, (user) -> {
            System.out.println("Saved : "+user);
        }, (error) -> {
            System.out.println("Error : "+error);
        });
        System.out.println("Time taken : "+(System.currentTimeMillis()-start)+"ms");

        //User firstFetch = userRepository.findById(randomUUID);

        //System.out.println("Found : "+firstFetch);
    }

}
