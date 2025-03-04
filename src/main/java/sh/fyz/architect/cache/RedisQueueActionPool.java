package sh.fyz.architect.cache;

import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.repositories.GenericCachedRepository;
import sh.fyz.architect.repositories.GenericRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static sh.fyz.architect.entities.DatabaseAction.Type.DELETE;
import static sh.fyz.architect.entities.DatabaseAction.Type.SAVE;

public class RedisQueueActionPool {

    private final ArrayList<GenericCachedRepository> queue = new ArrayList<>();
    private final HashMap<DatabaseAction, GenericRepository> pubSubQueue = new HashMap<>();

    public void add(GenericCachedRepository repository) {
        System.out.println("ADDING REPOSITORY TO RedisQueueActionPool : "+repository.getClass().getSimpleName());
        queue.add(repository);
        repository.all(); // Load all entities from the database
    }

    public void add(DatabaseAction action, GenericRepository repository) {
        pubSubQueue.put(action, repository);
    }

    public RedisQueueActionPool(boolean isReceiver) {
        if (!isReceiver) return;
        System.out.println("CREATING NEW RedisQueueActionPool");
        ExecutorService threadPool = Executors.newFixedThreadPool(2);

        /*
        *   FUTURE ME :
        *   the updates are not being flushed, the sysout in flushUpdates() don't appear
        *   Could be Redis not detected as alive?
        *   MAYBE threadpool is never submited actually... damn
        * */

        threadPool.submit(() -> {
            while (RedisManager.get().isAlive()) {
                System.out.println("sry4spam : "+queue.size());
                Iterator<GenericCachedRepository> iterator = queue.iterator();
                while (iterator.hasNext()) {
                    GenericCachedRepository repository = iterator.next();
                    repository.flushUpdates();
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        threadPool.submit(() -> {
            while (RedisManager.get().isAlive()) {
                synchronized (pubSubQueue) {
                    Iterator<Map.Entry<DatabaseAction, GenericRepository>> iterator = pubSubQueue.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<DatabaseAction, GenericRepository> entry = iterator.next();
                        System.out.println("Processing action: " + entry.getKey().getType() + " for entity: " + entry.getKey().getEntity());
                        switch (entry.getKey().getType()) {
                            case SAVE:
                                entry.getValue().save(entry.getKey().getEntity());
                                break;
                            case DELETE:
                                entry.getValue().delete(entry.getKey().getEntity());
                                break;

                        }
                        iterator.remove();
                    }
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
            }
        });
    }
}
