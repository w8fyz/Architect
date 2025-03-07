import sh.fyz.architect.Architect;
import sh.fyz.architect.cache.RedisCredentials;

import java.util.UUID;

public class TestAnchor {

    public static void main(String[] args) {
        Architect architect = new Architect().setReceiver(false)
                .setRedisCredentials(new RedisCredentials("localhost", "1234", 6379,100, 6));
        architect.start();
        architect.addRepositories(new UserRepository(), new RankRepository());
        UUID randomUUID = UUID.fromString("67047805-2dac-42d5-b4a1-18dfcc9759d9");

        architect.getAnchor().fetch("users/"+randomUUID+"/friends.name/where/level:gt:1/order/name:asc/limit/10")
                .thenAccept(result -> {
                    if (result.isPresent()) {
                        System.out.println("Result anchor : " + result.get());
                    } else {
                        System.out.println("anchor result not found");
                    }
                });
    }

}
