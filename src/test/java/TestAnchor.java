import sh.fyz.architect.Architect;
import sh.fyz.architect.anchor.lang.AnchorScript;
import sh.fyz.utils.RedisCredentials;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestAnchor {

    private static List<String> getMappedResults(Map<String, Object> results, String prefix) {
        List<String> mappedResults = new ArrayList<>();
        int index = 0;
        while (true) {
            String key = prefix + "_" + index;
            Object value = results.get(key);
            if (value == null) break;
            mappedResults.add(value.toString());
            index++;
        }
        return mappedResults;
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        Architect architect = new Architect().setReceiver(false)
                .setRedisCredentials(new RedisCredentials("localhost", "1234", 6379,100, 6));
        architect.start();
        architect.addRepositories(new UserRepository(), new RankRepository());
        UUID randomUUID = UUID.fromString("67047805-2dac-42d5-b4a1-18dfcc9759d9");

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        AnchorScript script = new AnchorScript();
        String scriptText = """
                  users = fetch("users/11111111-2dac-42d5-b4a1-222222222222");
                  userList = map("users #{index}. {current.username} if(current.friends.size > 0, '(famous)', '(random)')");
                """;
        System.out.println("Executing script...");
        AnchorScript.ScriptResult result = script.execute(scriptText).join();
        System.out.println("Script execution completed");
        for (Map.Entry<String, Object> user : result.getMap().entrySet()) {
            System.out.println(user.getKey() + " -> " + user.getValue());
        }
    }

}
