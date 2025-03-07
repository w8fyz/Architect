package sh.fyz.architect.anchor;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import sh.fyz.architect.repositories.GenericRepository;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.List;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Collectors;

public class DataPathResolver {
    // Modifié pour mieux correspondre à la structure users/id/property
    private static final Pattern PATH_PATTERN = Pattern.compile(
        "^([a-zA-Z]+)/([^/]+|\\*)(?:/([a-zA-Z.]+))?" + // Modifié pour accepter * comme ID
        "(?:/where/([^/]+))?" +                     // Conditions optionnelles
        "(?:/order/([^/]+))?" +                     // Tri optionnel
        "(?:/limit/(\\d+))?" +                      // Limite optionnelle
        "$"
    );

    public CompletableFuture<Object> resolve(String dataPath) {
        System.out.println("\n=== Starting resolution for path: " + dataPath + " ===");
        
        if (!isValidPath(dataPath)) {
            System.out.println("❌ Invalid path format");
            return CompletableFuture.completedFuture(null);
        }

        String[] parts = dataPath.split("/");
        String entityType = parts[0];
        System.out.println("📦 Entity type: " + entityType);

        GenericRepository<?> repository = Anchor.get().getRepository(entityType);
        if (repository == null) {
            System.out.println("❌ Repository not found for: " + entityType);
            return CompletableFuture.completedFuture(null);
        }
        System.out.println("✅ Found repository: " + repository.getClass().getSimpleName());

        return CompletableFuture.supplyAsync(() -> {
            try {
                QueryParams params = parseQueryParams(dataPath);
                System.out.println("\n🔍 Parsed parameters:");
                System.out.println("   ID: " + params.id);
                System.out.println("   Property path: " + params.propertyPath);
                System.out.println("   Conditions: " + params.conditions);
                System.out.println("   Order by: " + params.orderBy);
                System.out.println("   Limit: " + params.limit);

                Object result;

                // Vérifier si c'est une requête wildcard
                if (params.id.equals("*")) {
                    System.out.println("\n🌟 Wildcard query - fetching all entities");
                    result = repository.all();
                } else {
                    System.out.println("\n🎯 Fetching entity with ID: " + params.id);
                    result = repository.findById(params.id);
                    if (result == null) {
                        System.out.println("❌ No entity found with ID: " + params.id);
                        return null;
                    }
                    System.out.println("✅ Entity found");

                    if (params.propertyPath != null) {
                        System.out.println("📝 Wrapping single entity in list for property extraction");
                        List<Object> list = new ArrayList<>();
                        list.add(result);
                        result = list;
                    }
                }

                // Appliquer les conditions
                if (params.conditions != null && result instanceof Collection) {
                    System.out.println("\n🔍 Applying conditions: " + params.conditions);
                    int beforeSize = ((Collection<?>) result).size();
                    result = applyConditions((Collection<?>) result, params.conditions);
                    int afterSize = ((Collection<?>) result).size();
                    System.out.println("✅ Filtered " + beforeSize + " -> " + afterSize + " items");
                }

                // Extraire la propriété
                if (params.propertyPath != null) {
                    System.out.println("\n🔑 Extracting property: " + params.propertyPath);
                    if (result instanceof Collection) {
                        List<Object> values = new ArrayList<>();
                        for (Object item : (Collection<?>) result) {
                            Object value = getPropertyValue(item, params.propertyPath);
                            if (value != null) {
                                values.add(value);
                                System.out.println("   Found value: " + value);
                            }
                        }
                        result = values;
                        System.out.println("✅ Extracted " + values.size() + " values");
                    } else {
                        result = getPropertyValue(result, params.propertyPath);
                        System.out.println("✅ Extracted value: " + result);
                    }
                }

                // Appliquer le tri
                if (params.orderBy != null && result instanceof Collection) {
                    System.out.println("\n📊 Applying sort: " + params.orderBy);
                    result = applySorting((Collection<?>) result, params.orderBy);
                    System.out.println("✅ Sorting complete");
                }

                // Appliquer la limite
                if (params.limit > 0 && result instanceof Collection) {
                    System.out.println("\n🔢 Applying limit: " + params.limit);
                    int beforeSize = ((Collection<?>) result).size();
                    result = ((List<?>) result).stream()
                        .limit(params.limit)
                        .collect(Collectors.toList());
                    System.out.println("✅ Limited " + beforeSize + " -> " + ((Collection<?>) result).size() + " items");
                }

                System.out.println("\n=== Resolution complete ===");
                return result;
            } catch (Exception e) {
                System.out.println("\n❌ Error during resolution:");
                e.printStackTrace();
                return null;
            }
        });
    }

    private static class QueryParams {
        String id;
        String propertyPath;
        String conditions;
        String orderBy;
        int limit;
    }

    private QueryParams parseQueryParams(String dataPath) {
        QueryParams params = new QueryParams();
        String[] parts = dataPath.split("/");
        
        if (parts.length < 2) return params;
        
        // Le premier est toujours l'entité, le deuxième est toujours l'ID
        params.id = parts[1];
        
        // Parcourir le reste des parties
        for (int i = 2; i < parts.length; i++) {
            if (parts[i].contains(".")) {
                // C'est un chemin de propriété (ex: friends.name)
                params.propertyPath = parts[i];
            } else if (parts[i].equals("where") && i + 1 < parts.length) {
                params.conditions = parts[i + 1];
                i++;
            } else if (parts[i].equals("order") && i + 1 < parts.length) {
                params.orderBy = parts[i + 1];
                i++;
            } else if (parts[i].equals("limit") && i + 1 < parts.length) {
                params.limit = Integer.parseInt(parts[i + 1]);
                i++;
            } else if (!parts[i].equals("where") && !parts[i].equals("order") && !parts[i].equals("limit")) {
                // C'est une propriété simple
                params.propertyPath = parts[i];
            }
        }
        return params;
    }

    private Collection<?> applyConditions(Collection<?> items, String conditions) {
        String[] condParts = conditions.split(",");
        return items.stream().filter(item -> {
            for (String condition : condParts) {
                String[] parts = condition.split(":");
                if (parts.length != 3) continue;
                
                String field = parts[0];
                String operator = parts[1];
                String value = parts[2];
                
                Object fieldValue = getPropertyValue(item, field);
                if (fieldValue == null) continue;
                
                switch (operator) {
                    case "eq":
                        if (!value.equals(fieldValue.toString())) return false;
                        break;
                    case "gt":
                        if (!(fieldValue instanceof Comparable) || 
                            ((Comparable) fieldValue).compareTo(value) <= 0) return false;
                        break;
                    case "lt":
                        if (!(fieldValue instanceof Comparable) || 
                            ((Comparable) fieldValue).compareTo(value) >= 0) return false;
                        break;
                    // Ajoutez d'autres opérateurs selon vos besoins
                }
            }
            return true;
        }).collect(Collectors.toList());
    }

    private Collection<?> applySorting(Collection<?> items, String orderBy) {
        String[] orderParts = orderBy.split(",");
        List<?> list = new ArrayList<>(items);
        
        return list.stream().sorted((a, b) -> {
            for (String order : orderParts) {
                String[] parts = order.split(":");
                String field = parts[0];
                boolean ascending = parts.length == 1 || parts[1].equals("asc");
                
                Object valueA = getPropertyValue(a, field);
                Object valueB = getPropertyValue(b, field);
                
                if (valueA instanceof Comparable && valueB instanceof Comparable) {
                    int comp = ((Comparable) valueA).compareTo(valueB);
                    if (comp != 0) return ascending ? comp : -comp;
                }
            }
            return 0;
        }).collect(Collectors.toList());
    }

    private Object getPropertyValue(Object entity, String propertyPath) {
        if (entity == null) {
            System.out.println("   ⚠️ Entity is null");
            return null;
        }
        
        System.out.println("   🔍 Getting property '" + propertyPath + "' from " + entity.getClass().getSimpleName());
        
        try {
            // Pour les propriétés imbriquées (ex: rank.name)
            if (propertyPath.contains(".")) {
                System.out.println("   📎 Navigating nested property: " + propertyPath);
                String[] parts = propertyPath.split("\\.");
                Object current = entity;
                
                for (String part : parts) {
                    if (current == null) {
                        System.out.println("   ⚠️ Navigation stopped: current object is null");
                        return null;
                    }
                    
                    System.out.println("   👉 Accessing field: " + part + " in " + current.getClass().getSimpleName());
                    Field field = findField(current.getClass(), part);
                    
                    if (field == null) {
                        System.out.println("   ❌ Field not found: " + part);
                        return null;
                    }
                    
                    field.setAccessible(true);
                    current = field.get(current);
                    System.out.println("   ✅ Got value: " + (current != null ? current : "null"));
                }
                return current;
            }
            // Pour les propriétés simples
            else {
                System.out.println("   🔍 Looking for simple property: " + propertyPath);
                Field field = findField(entity.getClass(), propertyPath);
                
                if (field == null) {
                    System.out.println("   ❌ Field not found: " + propertyPath);
                    return null;
                }
                
                field.setAccessible(true);
                Object value = field.get(entity);
                System.out.println("   ✅ Got value: " + (value != null ? value : "null"));
                return value;
            }
        } catch (Exception e) {
            System.out.println("   ❌ Error accessing property: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private Field findField(Class<?> clazz, String fieldName) {
        if (clazz == null || fieldName == null) return null;
        
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), fieldName);
            }
        }
        return null;
    }

    public boolean isValidPath(String dataPath) {
        if (dataPath == null || dataPath.isEmpty()) {
            return false;
        }
        return PATH_PATTERN.matcher(dataPath).matches();
    }
} 