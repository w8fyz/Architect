import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import sh.fyz.architect.Architect;
import sh.fyz.architect.anchor.lang.AnchorScript;
import sh.fyz.architect.cache.RedisCredentials;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AnchorScript Tests")
public class AnchorScriptTest {
    private static Architect architect;
    private static AnchorScript script;

    @BeforeAll
    static void initAll() {
        architect = new Architect()
            .setReceiver(false)
            .setRedisCredentials(new RedisCredentials("localhost", "1234", 6379, 100, 6));
        architect.start();
        architect.addRepositories(new UserRepository(), new RankRepository());
        // Initialize script
        script = new AnchorScript();
        
        // Wait for connections to be established
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        assertNotNull(architect, "Architect should be initialized");
        assertNotNull(script, "Script should be initialized");
    }

    @Test
    @DisplayName("Test basic fetch operation")
    void testBasicFetch() throws ExecutionException, InterruptedException {
        String scriptText = "result = fetch(\"users/*\");";
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        
        assertNotNull(result);
        assertNotNull(result.get("result"));
        assertTrue(result.get("result") instanceof List);
    }

    @Test
    @DisplayName("Test fetch with specific user")
    void testFetchSpecificUser() throws ExecutionException, InterruptedException {
        String scriptText = "user = fetch(\"users/67047805-2dac-42d5-b4a1-18dfcc9759d9\");";
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        
        assertNotNull(result);
        Object user = result.get("user");
        assertNotNull(user);
        assertTrue(user instanceof User);
    }

    @Test
    @DisplayName("Test mapping operation")
    void testMapping() throws ExecutionException, InterruptedException {
        String scriptText = """
            users = fetch("users/*/order/friends.size:desc/limit/5");
            userList = map("users[start:1] #{index}. {current.username} ({current.friends.size} friends)");
            """;
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        
        assertNotNull(result);
        List<String> mappedResults = result.getMapped("userList");
        assertNotNull(mappedResults);
        assertFalse(mappedResults.isEmpty());
        assertTrue(mappedResults.get(0).matches("^#1\\. .+ \\(\\d+ friends\\)$"));
    }

    @Test
    @DisplayName("Test reverse mapping")
    void testReverseMapping() throws ExecutionException, InterruptedException {
        String scriptText = """
            users = fetch("users/*/order/friends.size:asc/limit/5");
            userList = map("users[reverse:true] #{index}. {current.username}");
            """;
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        
        assertNotNull(result);
        List<String> mappedResults = result.getMapped("userList");
        assertNotNull(mappedResults);
        assertFalse(mappedResults.isEmpty());
    }

    @Test
    @DisplayName("Test property access")
    void testPropertyAccess() throws ExecutionException, InterruptedException {
        String scriptText = """
            user = fetch("users/11111111-2dac-42d5-b4a1-222222222222");
            username = user.username;
            friendCount = user.friends.size;
            """;
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        
        assertNotNull(result);
        assertNotNull(result.get("username"));
        assertNotNull(result.get("friendCount"));
        assertTrue(result.get("friendCount") instanceof Integer);
    }

    @Test
    @DisplayName("Test conditional execution")
    void testConditionalExecution() throws ExecutionException, InterruptedException {
        String scriptText = """
            user = fetch("users/67047805-2dac-42d5-b4a1-18dfcc9759d9);
            status = if(user.friends.size > 5, "Popular", "New user");
            """;
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        
        assertNotNull(result);
        assertNotNull(result.get("status"));
        assertTrue(result.get("status") instanceof String);
    }

    @Test
    @DisplayName("Test error handling - invalid repository")
    void testErrorHandlingInvalidRepository() throws ExecutionException, InterruptedException {
        script = new AnchorScript();
        String scriptText = "result = fetch(\"nonexistent/123\")";
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        assertNotNull(result);
        assertNull(result.get("result"), "Fetching from non-existent repository should return null");
    }

    @Test
    @DisplayName("Test error handling - invalid ID format")
    void testErrorHandlingInvalidId() throws ExecutionException, InterruptedException {
        script = new AnchorScript();
        String scriptText = "result = fetch(\"users/not-a-valid-id\")";
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        assertNotNull(result);
        assertNull(result.get("result"), "Fetching with invalid ID format should return null");
    }

    @Test
    @DisplayName("Test error handling - non-existent entity")
    void testErrorHandlingNonExistentEntity() throws ExecutionException, InterruptedException {
        script = new AnchorScript();
        String scriptText = "result = fetch(\"users/00000000-0000-0000-0000-000000000000\")";
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        assertNotNull(result);
        assertNull(result.get("result"), "Fetching non-existent entity should return null");
    }

    @Test
    @DisplayName("Test error handling - invalid path format")
    void testErrorHandlingInvalidPath() throws ExecutionException, InterruptedException {
        script = new AnchorScript();
        String scriptText = "result = fetch(\"invalid/path/with/too/many/segments\")";
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        assertNotNull(result);
        assertNull(result.get("result"), "Fetching with invalid path format should return null");
    }

    @Test
    @DisplayName("Test complex query with ordering and limiting")
    void testComplexQuery() throws ExecutionException, InterruptedException {
        String scriptText = """
            users = fetch("users/*/order/friends.size:desc/limit/3");
            topUsers = map("users #{index}. {current.username} - Rank: {current.rank.name}");
            """;
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        
        assertNotNull(result);
        List<String> mappedResults = result.getMapped("topUsers");
        assertNotNull(mappedResults);
        assertEquals(2, mappedResults.size());
    }

    @Test
    @DisplayName("Test variable persistence across operations")
    void testVariablePersistence() throws ExecutionException, InterruptedException {
        String scriptText = """
            users = fetch("users/*");
            firstUser = users[0];
            userName = firstUser.username;
            formatted = concat("User: {userName}");
            """;
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        
        assertNotNull(result);
        assertNotNull(result.get("formatted"));
        assertTrue(result.get("formatted").toString().startsWith("User: "));
    }

    @Test
    @DisplayName("Test collection operations")
    void testCollectionOperations() throws ExecutionException, InterruptedException {
        String scriptText = """
            users = fetch("users/*");
            count = users.size;
            hasUsers = count > 0;
            """;
        AnchorScript.ScriptResult result = script.execute(scriptText).get();
        
        assertNotNull(result);
        assertTrue(result.get("count") instanceof Integer);
        assertTrue(result.get("hasUsers") instanceof Boolean);
    }
} 