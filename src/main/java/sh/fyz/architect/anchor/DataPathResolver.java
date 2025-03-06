package sh.fyz.architect.anchor;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import sh.fyz.architect.repositories.GenericRepository;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.List;
import java.lang.reflect.Field;

public class DataPathResolver {
    private static final Pattern PATH_PATTERN = Pattern.compile("^([a-zA-Z]+)(?:/(?:([a-zA-Z]+):([^/]+)|all))?(?:/[a-zA-Z]+)*$");

    public CompletableFuture<Object> resolve(String dataPath) {
        if (!isValidPath(dataPath)) {
            return CompletableFuture.completedFuture(null);
        }

        String[] parts = dataPath.split("/");
        String entityType = parts[0];

        GenericRepository<?> repository = Anchor.get().getRepository(entityType);
        if (repository == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Object result;
                
                // Si le chemin est de type "entity/all"
                if (parts.length > 1 && parts[1].equals("all")) {
                    result = repository.all();
                }
                // Pour les requêtes spécifiques (entity/field:value)
                else if (parts.length > 1) {
                    String[] queryParts = parts[1].split(":");
                    String queryField = queryParts[0];
                    String queryValue = queryParts[1];

                    if (queryField.equals("id")) {
                        result = repository.findById(Long.parseLong(queryValue));
                    } else {
                        String methodName = "findBy" + queryField.substring(0, 1).toUpperCase() + queryField.substring(1);
                        result = repository.getClass().getMethod(methodName, String.class)
                            .invoke(repository, queryValue);
                    }
                }
                // Si seulement le type d'entité est spécifié
                else {
                    result = repository.all();
                }

                // Résoudre les relations
                if (result != null) {
                    if (result instanceof List) {
                        ((List<?>) result).forEach(this::resolveRelations);
                    } else {
                        resolveRelations(result);
                    }
                }

                return result;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    private void resolveRelations(Object entity) {
        if (entity == null) return;
        
        Class<?> entityClass = entity.getClass();
        
        for (Field field : entityClass.getDeclaredFields()) {
            try {
                field.setAccessible(true);
                
                // Vérifier si le champ est une relation
                if (field.isAnnotationPresent(ManyToOne.class) ||
                    field.isAnnotationPresent(OneToOne.class)) {
                    
                    Object relatedEntity = field.get(entity);
                    if (relatedEntity != null) {
                        String repositoryName = relatedEntity.getClass().getSimpleName().toLowerCase() + "s";
                        GenericRepository<?> relatedRepository = Anchor.get().getRepository(repositoryName);
                        
                        if (relatedRepository != null) {
                            Field idField = relatedEntity.getClass().getDeclaredField("id");
                            idField.setAccessible(true);
                            Object id = idField.get(relatedEntity);
                            
                            Object loadedEntity = relatedRepository.findById(id);
                            if (loadedEntity != null) {
                                field.set(entity, loadedEntity);
                            }
                        }
                    }
                }
                // Gérer les collections (OneToMany) si nécessaire
                else if (field.isAnnotationPresent(OneToMany.class)) {
                    // Implémenter la logique pour les collections si nécessaire
                }
            } catch (Exception e) {
                System.err.println("Error resolving relation for field " + field.getName() + ": " + e.getMessage());
            }
        }
    }

    public boolean isValidPath(String dataPath) {
        if (dataPath == null || dataPath.isEmpty()) {
            return false;
        }
        return PATH_PATTERN.matcher(dataPath).matches();
    }
} 