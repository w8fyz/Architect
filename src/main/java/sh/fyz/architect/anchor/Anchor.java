package sh.fyz.architect.anchor;

import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.repositories.GenericRepository;
import sh.fyz.architect.persistant.SessionManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.HashSet;
import java.util.Set;

public class Anchor {
    private static Anchor instance;
    private final DataPathResolver pathResolver;
    private final Map<String, GenericRepository<? extends IdentifiableEntity>> repositories;

    private Anchor() {
        this.pathResolver = new DataPathResolver();
        this.repositories = new HashMap<>();
    }

    public static Anchor get() {
        if (instance == null) {
            instance = new Anchor();
        }
        return instance;
    }

    /**
     * Récupère des données via un chemin d'accès
     * Exemple: "users/uuid:123e4567-e89b-12d3-a456-426614174000"
     *          "users/username:Fyz"
     *          "users/all"
     *          "guilds/name:MyGuild/members"
     */
    public CompletableFuture<Optional<Object>> fetch(String dataPath) {
        System.out.println("Fetching data from path: " + dataPath);
        return pathResolver.resolve(dataPath)
            .thenApply(result -> {
                if (result == null) {
                    return Optional.empty();
                }
                return Optional.of(result);
            });
    }

    /**
     * Enregistre automatiquement un repository pour une entité
     */
    public void registerRepository(String path, GenericRepository<? extends IdentifiableEntity> repository) {
        repositories.put(path.toLowerCase(), repository);
    }

    /**
     * Récupère un repository enregistré
     */
    public GenericRepository<? extends IdentifiableEntity> getRepository(String path) {
        return repositories.get(path.toLowerCase());
    }

    /**
     * Vérifie si un chemin est valide
     */
    public boolean isValidPath(String dataPath) {
        return pathResolver.isValidPath(dataPath);
    }

    /**
     * Récupère les champs disponibles pour un type d'entité
     * @param entityType le type d'entité (ex: "users")
     * @return Map des noms de champs et leurs types
     */
    public Map<String, Class<?>> getAvailableFields(String entityType) {
        GenericRepository<?> repository = repositories.get(entityType.toLowerCase());
        if (repository == null) {
            return new HashMap<>();
        }

        Class<?> entityClass = repository.getEntityClass();
        Map<String, Class<?>> fields = new HashMap<>();

        // Récupérer tous les champs de la classe
        for (java.lang.reflect.Field field : entityClass.getDeclaredFields()) {
            fields.put(field.getName(), field.getType());
        }

        return fields;
    }

    /**
     * Récupère les types d'entités disponibles
     */
    public Set<String> getAvailableTypes() {
        return new HashSet<>(repositories.keySet());
    }
} 