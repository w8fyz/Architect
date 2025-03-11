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
import java.util.UUID;

public class DataPathResolver {
    // Modifié pour mieux correspondre à la structure users/id/property
    private static final Pattern PATH_PATTERN = Pattern.compile(
        "^([a-zA-Z]+)/([^/]+|\\*)(?:/([a-zA-Z.]+))?" + // Modifié pour accepter * comme ID
        "(?:/order/([^/]+))?" +                     // Tri optionnel
        "(?:/limit/(\\d+))?" +                      // Limite optionnelle
        "$"
    );

    public CompletableFuture<Object> resolve(String dataPath) {
        // First validate the path format
        if (!isValidPath(dataPath)) {
            System.out.println("❌ Invalid path format: " + dataPath);
            return CompletableFuture.completedFuture(null);
        }

        // Split and validate path components
        String[] parts = dataPath.split("/");
        if (parts.length < 2) {
            System.out.println("❌ Path must have at least entity type and ID: " + dataPath);
            return CompletableFuture.completedFuture(null);
        }

        String entityType = parts[0].toLowerCase();
        GenericRepository<?> repository = Anchor.get().getRepository(entityType);
        
        // Check if repository exists
        if (repository == null) {
            System.out.println("❌ Repository not found for: " + entityType);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                QueryParams params = parseQueryParams(dataPath);
                Object result;

                // Handle wildcard query
                if (params.id.equals("*")) {
                    System.out.println("✅ Executing repository.all() for " + entityType);
                    result = repository.all();
                    if (result == null) {
                        System.out.println("❌ Repository.all() returned null");
                        return null;
                    }
                    if (!(result instanceof Collection)) {
                        System.out.println("❌ Repository.all() returned non-collection type: " + result.getClass().getSimpleName());
                        return null;
                    }
                    Collection<?> collection = (Collection<?>) result;
                    System.out.println("✅ Found " + collection.size() + " entities in repository");
                } else {
                    // Validate ID format if not a wildcard
                    try {
                        // Attempt to parse as UUID if it looks like one
                        if (params.id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                            UUID.fromString(params.id);
                        }
                    } catch (IllegalArgumentException e) {
                        System.out.println("❌ Invalid ID format: " + params.id);
                        return null;
                    }

                    result = repository.findById(params.id);
                    if (result == null) {
                        System.out.println("❌ No entity found with ID: " + params.id);
                        return null;
                    }

                    if (params.propertyPath != null) {
                        List<Object> list = new ArrayList<>();
                        list.add(result);
                        result = list;
                    }
                }

                // Extraire la propriété
                if (params.propertyPath != null) {
                    System.out.println("Extracting property: " + params.propertyPath);
                    if (result instanceof Collection) {
                        List<Object> values = new ArrayList<>();
                        for (Object item : (Collection<?>) result) {
                            Object value = getPropertyValue(item, params.propertyPath);
                            if (value != null) {
                                values.add(value);
                            }
                        }
                        result = values;
                        System.out.println("✅ Extracted " + values.size() + " property values");
                    } else {
                        result = getPropertyValue(result, params.propertyPath);
                        System.out.println("✅ Extracted property value: " + result);
                    }
                }

                // Appliquer le tri
                if (params.orderBy != null && result instanceof Collection) {
                    System.out.println("Applying sorting: " + params.orderBy);
                    result = applySorting((Collection<?>) result, params.orderBy);
                }

                // Appliquer la limite
                if (params.limit > 0 && result instanceof Collection) {
                    System.out.println("Applying limit: " + params.limit);
                    result = ((List<?>) result).stream()
                        .limit(params.limit)
                        .collect(Collectors.toList());
                }

                System.out.println("\n=== Resolution complete ===");
                if (result instanceof Collection) {
                    Collection<?> collection = (Collection<?>) result;
                    System.out.println("✅ Final result: Collection with " + collection.size() + " items");
                    System.out.println("✅ Collection contents: " + collection);
                } else {
                    System.out.println("✅ Final result: " + (result != null ? result.getClass().getSimpleName() + " = " + result : "null"));
                }
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
            } else if (parts[i].equals("order") && i + 1 < parts.length) {
                params.orderBy = parts[i + 1];
                i++;
            } else if (parts[i].equals("limit") && i + 1 < parts.length) {
                params.limit = Integer.parseInt(parts[i + 1]);
                i++;
            } else if (!parts[i].equals("order") && !parts[i].equals("limit")) {
                // C'est une propriété simple
                params.propertyPath = parts[i];
            }
        }
        return params;
    }

    private Object convertValue(Object fieldValue, String stringValue) {
        if (fieldValue instanceof Integer) {
            return Integer.parseInt(stringValue);
        } else if (fieldValue instanceof Long) {
            return Long.parseLong(stringValue);
        } else if (fieldValue instanceof Double) {
            return Double.parseDouble(stringValue);
        } else if (fieldValue instanceof Float) {
            return Float.parseFloat(stringValue);
        } else if (fieldValue instanceof Boolean) {
            return Boolean.parseBoolean(stringValue);
        }
        return stringValue;
    }

    private Collection<?> applyConditions(Collection<?> items, String conditions) {
        String[] condParts = conditions.split(",");
        return items.stream().filter(item -> {
            for (String condition : condParts) {
                String[] parts = condition.split(":");
                if (parts.length != 3) continue;
                
                String field = parts[0];
                String operator = parts[1];
                String stringValue = parts[2];
                
                Object fieldValue = getPropertyValue(item, field);
                if (fieldValue == null) continue;
                
                try {
                    // Convertir la valeur en fonction du type du champ
                    Object value = convertValue(fieldValue, stringValue);
                    
                    switch (operator) {
                        case "eq":
                            if (!fieldValue.equals(value)) return false;
                            break;
                        case "gt":
                            if (fieldValue instanceof Number && value instanceof Number) {
                                double fieldDouble = ((Number) fieldValue).doubleValue();
                                double valueDouble = ((Number) value).doubleValue();
                                if (fieldDouble <= valueDouble) return false;
                            } else if (fieldValue instanceof Comparable && value.getClass().equals(fieldValue.getClass())) {
                                if (((Comparable) fieldValue).compareTo(value) <= 0) return false;
                            } else {
                                System.out.println("❌ Incompatible types for comparison: " + fieldValue.getClass() + " and " + value.getClass());
                                return false;
                            }
                            break;
                        case "lt":
                            if (fieldValue instanceof Number && value instanceof Number) {
                                double fieldDouble = ((Number) fieldValue).doubleValue();
                                double valueDouble = ((Number) value).doubleValue();
                                if (fieldDouble >= valueDouble) return false;
                            } else if (fieldValue instanceof Comparable && value.getClass().equals(fieldValue.getClass())) {
                                if (((Comparable) fieldValue).compareTo(value) >= 0) return false;
                            } else {
                                System.out.println("❌ Incompatible types for comparison: " + fieldValue.getClass() + " and " + value.getClass());
                                return false;
                            }
                            break;
                    }
                } catch (Exception e) {
                    System.out.println("❌ Error during comparison: " + e.getMessage());
                    return false;
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
            return null;
        }

        try {
            // Special handling for size property on collections
            if (propertyPath.equals("size") && entity instanceof Collection) {
                return ((Collection<?>) entity).size();
            }

            // Pour les propriétés imbriquées (ex: rank.name)
            if (propertyPath.contains(".")) {
                String[] parts = propertyPath.split("\\.");
                Object current = entity;
                
                for (String part : parts) {
                    if (current == null) {
                        return null;
                    }
                    
                    // Special handling for size property on collections
                    if (part.equals("size") && current instanceof Collection) {
                        current = ((Collection<?>) current).size();
                        continue;
                    }
                    
                    Field field = findField(current.getClass(), part);
                    
                    if (field == null) {
                        return null;
                    }
                    
                    field.setAccessible(true);
                    current = field.get(current);
                }
                return current;
            }
            // Pour les propriétés simples
            else {
                Field field = findField(entity.getClass(), propertyPath);
                
                if (field == null) {
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