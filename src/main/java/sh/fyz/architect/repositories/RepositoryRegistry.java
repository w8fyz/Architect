package sh.fyz.architect.repositories;

import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RepositoryRegistry {

    private static final RepositoryRegistry INSTANCE = new RepositoryRegistry();

    private final Map<String, GenericRepository<? extends IdentifiableEntity>> repositories = new ConcurrentHashMap<>();

    private RepositoryRegistry() {}

    public static RepositoryRegistry get() {
        return INSTANCE;
    }

    public void register(String name, GenericRepository<? extends IdentifiableEntity> repository) {
        repositories.put(name.toLowerCase(), repository);
    }

    public GenericRepository<? extends IdentifiableEntity> getRepository(String name) {
        return repositories.get(name.toLowerCase());
    }

    public Set<String> getRegisteredNames() {
        return new HashSet<>(repositories.keySet());
    }

    public void clear() {
        repositories.clear();
    }
}
