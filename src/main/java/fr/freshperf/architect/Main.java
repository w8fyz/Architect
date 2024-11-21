package fr.freshperf.architect;

import fr.freshperf.models.User;
import org.hibernate.Session;

import java.util.UUID;

public class Main {

    private static int i = 0;
    private static long last = 0L;

    public static void main(String[] args) throws InterruptedException {
        SessionManager.initialize("localhost", 5432, "architect", "postgres", "[REDACTED]", "fr.freshperf.models", 4);
        UserRepository repository = new UserRepository(User.class);
        System.out.println("-THREADED TEST-");
        System.out.println("START (5000 INSERT + OPERATION)");
        long start = System.currentTimeMillis();
        for(int i = 0; i < 5000; i++) {
            User user = new User(UUID.randomUUID().toString(), "john@doe.fr", 20);
            repository.save(user);
        }
        long end = System.currentTimeMillis();
        System.out.println("END");
        System.out.println("Time: " + (end - start) + "ms");

        System.out.println("START 2 (QUERY ALL)");
        start = System.currentTimeMillis();
        repository.all().forEach(u -> {
            i++;
        });
        end = System.currentTimeMillis();
        System.out.println("END 2 ("+i+" results)");
        System.out.println("Time: " + (end - start) + "ms");

        System.out.println("-MULTITHREADED TEST-");
        System.out.println("START (5000 INSERT + OPERATION)");
        start = System.currentTimeMillis();
        last = 0L;
        for(int i = 0; i < 5000; i++) {
            User user = new User(UUID.randomUUID().toString(), "john@doe.fr", 20);
            repository.saveAsync(user, (u) -> {
                last = System.currentTimeMillis();
            }, (e) -> {});
        }
        System.out.println("SLEEP FOR RESULT");
        Thread.sleep(10000L);
        System.out.println("END (WAITED FOR RESULT)");
        System.out.println("Finished in : "+(last - start)+"ms");
        System.out.println("START 2 (QUERY ALL)");
        start = System.currentTimeMillis();
        i = 0;
        repository.allAsync((users -> {
            last = System.currentTimeMillis();
            i++;
        }), (e) -> {});
        System.out.println("SLEEP FOR RESULT");
        Thread.sleep(3000L);
        end = System.currentTimeMillis();
        System.out.println("END 2 ("+i+" results)");
        System.out.println("Time: " + (last - start) + "ms");
    }

}
