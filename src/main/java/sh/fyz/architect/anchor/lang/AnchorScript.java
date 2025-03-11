package sh.fyz.architect.anchor.lang;

import sh.fyz.architect.anchor.Anchor;
import sh.fyz.architect.anchor.DataPathResolver;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.repositories.GenericRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.Collections;

public class AnchorScript {
    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
        "([a-zA-Z][a-zA-Z0-9_]*)\\s*\\(((?:[^()]*|\\([^()]*\\))*)\\)"  // matches function calls with nested parentheses
    );

    private static final Pattern LIST_INDEX_PATTERN = Pattern.compile(
        "([a-zA-Z][a-zA-Z0-9_]*?)\\[(\\d+)\\]"  // matches list[index] syntax
    );

    private static final Pattern MAP_IF_PATTERN = Pattern.compile(
        "if\\((.*?),\\s*(.*?),\\s*(.*?)\\)"  // matches if(condition, true_value, false_value) in map strings
    );

    private final Map<String, Object> variables;
    private final DataPathResolver pathResolver;
    private final List<String> debugMessages;

    public AnchorScript() {
        this.variables = new HashMap<>();
        this.pathResolver = Anchor.get().getPathResolver();
        this.debugMessages = new ArrayList<>();
        debug("Creating new AnchorScript instance");
    }

    private void debug(String message) {
        debugMessages.add(message);
        variables.put("debug", debugMessages);
    }

    public static class ScriptResult {
        private final Map<String, Object> variables;

        private ScriptResult(Map<String, Object> variables) {
            this.variables = variables;
        }

        public Object get(String key) {
            return variables.get(key);
        }

        public Map<String, Object> getMap() {
            return new HashMap<>(variables);
        }

        public List<String> getMapped(String prefix) {
            List<String> mappedResults = new ArrayList<>();
            int index = 0;
            while (true) {
                String key = prefix + "_" + index;
                Object value = variables.get(key);
                if (value == null) break;
                mappedResults.add(value.toString());
                index++;
            }
            return mappedResults;
        }

        @SuppressWarnings("unchecked")
        public <T> T get(String key, Class<T> type) {
            Object value = variables.get(key);
            if (value == null || !type.isInstance(value)) {
                return null;
            }
            return (T) value;
        }

        public boolean has(String key) {
            return variables.containsKey(key);
        }
    }

    public CompletableFuture<ScriptResult> execute(String script) {
        debug("Executing script:");
        debug(script);
        debug("-------------------");

        // Split script into lines and execute each line
        String[] lines = script.split("\\r?\\n|;");
        CompletableFuture<Map<String, Object>> lastResult = CompletableFuture.completedFuture(new HashMap<>());

        for (final String line : lines) {
            final String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) continue;

            debug("Processing line: " + trimmedLine);

            // Chain the next line's execution to the previous one
            lastResult = lastResult.thenCompose(previousVariables -> {
                // Update variables with previous results
                variables.putAll(previousVariables);

                CompletableFuture<Map<String, Object>> lineResult;
                if (trimmedLine.contains("=")) {
                    // Assignment
                    String[] parts = trimmedLine.split("=", 2);
                    String varName = parts[0].trim();
                    String expression = parts[1].trim();
                    debug("Assignment: " + varName + " = " + expression);
                    
                    // Store the current assignment variable name
                    variables.put("__current_assignment__", varName);
                    
                    lineResult = evaluateExpression(expression)
                        .thenApply(result -> {
                            debug("Setting variable " + varName + " = " + result);
                            if (result != null) {
                                variables.put(varName, result);
                                debug("Variable " + varName + " set to: " + (result instanceof Collection ? "Collection with size " + ((Collection<?>) result).size() : result));
                            } else {
                                debug("Variable " + varName + " not set because result was null");
                            }
                            return new HashMap<>(variables);
                        });
                } else {
                    // Expression evaluation
                    debug("Evaluating expression: " + trimmedLine);
                    lineResult = evaluateExpression(trimmedLine)
                        .thenApply(result -> {
                            debug("Setting result = " + result);
                            if (result != null) {
                                variables.put("result", result);
                            }
                            return new HashMap<>(variables);
                        });
                }
                return lineResult;
            });
        }

        return lastResult.thenApply(results -> {
            debug("\nFinal variables:");
            results.forEach((key, value) -> debug("  " + key + " = " + value));
            debug("-------------------\n");
            return new ScriptResult(results);
        });
    }

    private CompletableFuture<Object> evaluateExpression(String expr) {
        debug("Evaluating expression: " + expr);
        
        // Handle NOT operator
        if (expr.trim().startsWith("!")) {
            String subExpr = expr.trim().substring(1);
            return evaluateExpression(subExpr).thenApply(result -> {
                if (result instanceof Boolean) {
                    return !(Boolean) result;
                } else if (result instanceof Number) {
                    return ((Number) result).doubleValue() == 0;
                } else if (result instanceof Collection) {
                    return ((Collection<?>) result).isEmpty();
                } else if (result instanceof String) {
                    String str = ((String) result).trim().toLowerCase();
                    if (str.equals("true") || str.equals("false")) {
                        return !Boolean.parseBoolean(str);
                    }
                    return str.isEmpty();
                }
                return result == null;
            });
        }

        // Handle list indexing
        Matcher indexMatcher = LIST_INDEX_PATTERN.matcher(expr.trim());
        if (indexMatcher.matches()) {
            String listName = indexMatcher.group(1);
            int index = Integer.parseInt(indexMatcher.group(2));
            Object value = variables.get(listName);
            
            if (value instanceof List) {
                List<?> list = (List<?>) value;
                if (index >= 0 && index < list.size()) {
                    return CompletableFuture.completedFuture(list.get(index));
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        // Check for function calls first - use find() instead of matches() to handle nested calls
        Matcher matcher = FUNCTION_PATTERN.matcher(expr.trim());
        debug("Checking for function call in: " + expr.trim());
        if (matcher.find()) {
            String functionName = matcher.group(1);
            String args = matcher.group(2);
            debug("Found function: " + functionName);
            debug("With arguments: " + args);
            debug("Function call: " + functionName + "(" + args + ")");
            return executeFunction(functionName, args);
        }

        // Handle comparison operations first
        // Order matters: check longer operators first
        String[] operators = {">=", "<=", "!=", ">", "<", "=="};
        for (String operator : operators) {
            if (expr.contains(operator)) {
                String[] parts = expr.split(Pattern.quote(operator));
                String leftExpr = parts[0].trim();
                String rightExpr = parts[1].trim();
                
                debug("Comparing: " + leftExpr + " " + operator + " " + rightExpr);
                
                // Evaluate left side
                Object leftValue;
                if (leftExpr.contains(".")) {
                    String[] leftParts = leftExpr.split("\\.");
                    leftValue = variables.get(leftParts[0]);
                    for (int i = 1; i < leftParts.length && leftValue != null; i++) {
                        if (leftParts[i].equals("size") && leftValue instanceof Collection) {
                            leftValue = ((Collection<?>) leftValue).size();
                            debug("Left side is collection size: " + leftValue);
                        } else {
                            leftValue = getFieldValue(leftValue, leftParts[i]);
                        }
                    }
                } else {
                    try {
                        leftValue = Double.parseDouble(leftExpr);
                    } catch (NumberFormatException e) {
                        leftValue = variables.get(leftExpr);
                    }
                }
                
                // Evaluate right side
                Object rightValue;
                try {
                    rightValue = Double.parseDouble(rightExpr);
                } catch (NumberFormatException e) {
                    if (rightExpr.contains(".")) {
                        String[] rightParts = rightExpr.split("\\.");
                        rightValue = variables.get(rightParts[0]);
                        for (int i = 1; i < rightParts.length && rightValue != null; i++) {
                            if (rightParts[i].equals("size") && rightValue instanceof Collection) {
                                rightValue = ((Collection<?>) rightValue).size();
                            } else {
                                rightValue = getFieldValue(rightValue, rightParts[i]);
                            }
                        }
                    } else {
                        rightValue = variables.get(rightExpr);
                    }
                }
                
                debug("Comparing values: " + leftValue + " " + operator + " " + rightValue);
                
                if (leftValue instanceof Number && rightValue instanceof Number) {
                    double left = ((Number) leftValue).doubleValue();
                    double right = ((Number) rightValue).doubleValue();
                    boolean result = switch (operator) {
                        case ">=" -> left >= right;
                        case "<=" -> left <= right;
                        case "!=" -> left != right;
                        case ">" -> left > right;
                        case "<" -> left < right;
                        case "==" -> left == right;
                        default -> false;
                    };
                    debug("Numeric comparison result: " + result);
                    return CompletableFuture.completedFuture(result);
                }
                
                debug("Could not compare values");
                return CompletableFuture.completedFuture(false);
            }
        }

        // Handle property access (e.g., user.friends.size)
        if (expr.contains(".")) {
            String[] parts = expr.split("\\.");
            Object value = variables.get(parts[0]);
            debug("Property access: starting with " + parts[0] + " = " + value);
            
            // Navigate through the properties
            for (int i = 1; i < parts.length && value != null; i++) {
                debug("  Accessing property: " + parts[i] + " of " + value.getClass().getSimpleName());
                
                // Special handling for size property
                if (parts[i].equals("size") && value instanceof Collection) {
                    value = ((Collection<?>) value).size();
                    debug("  Got collection size: " + value);
                } else {
                    value = getFieldValue(value, parts[i]);
                    debug("  Got field value: " + value);
                }
            }
            
            debug("Final property value: " + value);
            return CompletableFuture.completedFuture(value);
        }

        // If not a function call or property access, evaluate as a regular expression
        debug("Evaluating as regular expression");
        
        // Handle comparison operations
        if (expr.contains("<")) {
            String[] parts = expr.split("<");
            return evaluateExpression(parts[0].trim())
                .thenCombine(evaluateExpression(parts[1].trim()), (left, right) -> {
                    if (left instanceof Number && right instanceof Number) {
                        return ((Number) left).doubleValue() < ((Number) right).doubleValue();
                    }
                    return false;
                });
        } else if (expr.contains("==")) {
            String[] parts = expr.split("==");
            return evaluateExpression(parts[0].trim())
                .thenCombine(evaluateExpression(parts[1].trim()), (left, right) -> {
                    if (left == null || right == null) return false;
                    return left.equals(right);
                });
        }
        
        // Handle arithmetic operations
        if (expr.contains("+")) {
            String[] parts = expr.split("\\+");
            return evaluateExpression(parts[0].trim())
                .thenCombine(evaluateExpression(parts[1].trim()), (left, right) -> {
                    if (left instanceof Number && right instanceof Number) {
                        return ((Number) left).doubleValue() + ((Number) right).doubleValue();
                    }
                    return String.valueOf(left) + String.valueOf(right);
                });
        } else if (expr.contains("-")) {
            String[] parts = expr.split("-");
            return evaluateExpression(parts[0].trim())
                .thenCombine(evaluateExpression(parts[1].trim()), (left, right) -> {
                    if (left instanceof Number && right instanceof Number) {
                        return ((Number) left).doubleValue() - ((Number) right).doubleValue();
                    }
                    return null;
                });
        }

        // Try to parse as a number
        try {
            return CompletableFuture.completedFuture(Double.parseDouble(expr));
        } catch (NumberFormatException ignored) {}

        // Check if it's a variable reference
        if (variables.containsKey(expr)) {
            return CompletableFuture.completedFuture(variables.get(expr));
        }

        // Check if it's a string containing a function call
        String trimmedExpr = expr.trim();
        if (trimmedExpr.contains("(") && trimmedExpr.contains(")")) {
            matcher = FUNCTION_PATTERN.matcher(trimmedExpr);
            if (matcher.find()) {
                String functionName = matcher.group(1);
                String args = matcher.group(2);
                debug("Found embedded function call: " + functionName);
                debug("With arguments: " + args);
                debug("Embedded function call: " + functionName + "(" + args + ")");
                return executeFunction(functionName, args);
            }
        }

        // Return as is for string literals
        return CompletableFuture.completedFuture(expr);
    }

    private CompletableFuture<Object> executeFunction(String name, String args) {
        debug("Executing function: " + name + " with args: " + args);
        
        // Remove quotes from string arguments
        args = args.replaceAll("\"([^\"]*)\"", "$1");
        
        return switch (name) {
            case "fetch" -> {
                if (args == null || args.isEmpty()) {
                    debug("Fetch: Empty arguments");
                    yield CompletableFuture.completedFuture(null);
                }
                String[] parts = args.split("/");
                if (parts.length < 1) {
                    debug("Fetch: Invalid path format");
                    yield CompletableFuture.completedFuture(null);
                }
                String entityType = parts[0].toLowerCase();
                debug("Fetch: Attempting to fetch from repository: " + entityType);
                GenericRepository<?> repository = Anchor.get().getRepository(entityType);
                if (repository == null) {
                    debug("Fetch: No repository registered for type: " + entityType);
                    yield CompletableFuture.completedFuture(null);
                }
                debug("Fetch: Found repository for " + entityType + ", executing fetch with path: " + args);
                try {
                    Object result = pathResolver.resolve(args).get(); // Wait for the result
                    debug("Fetch: Result received: " + (result != null ? 
                        (result instanceof Collection ? 
                            "Collection with " + ((Collection<?>) result).size() + " items" : 
                            result.getClass().getSimpleName() + " with value: " + result) 
                        : "null"));
                    if (result instanceof Collection) {
                        debug("Fetch: Collection contents: " + result);
                    }
                    yield CompletableFuture.completedFuture(result);
                } catch (Exception e) {
                    debug("Fetch: Error getting result: " + e.getMessage());
                    e.printStackTrace();
                    yield CompletableFuture.completedFuture(null);
                }
            }
            case "concat" -> {
                debug("Concatenating: " + args);
                StringBuilder result = new StringBuilder();
                int start = 0;
                int end;
                
                while ((start = args.indexOf("{", start)) != -1) {
                    // Add any text before the variable
                    if (start > 0) {
                        result.append(args.substring(0, start));
                    }
                    
                    // Find the end of the variable reference
                    end = args.indexOf("}", start);
                    if (end == -1) {
                        // If no closing brace, treat the rest as literal text
                        result.append(args.substring(start));
                        break;
                    }
                    
                    // Extract and process the variable reference
                    String varRef = args.substring(start + 1, end).trim();
                    debug("  Processing variable reference: " + varRef);
                    
                    // Handle object property access (e.g., user.username)
                    String[] parts = varRef.split("\\.");
                    Object value = variables.get(parts[0]);
                    debug("  Initial value for " + parts[0] + ": " + value);
                    
                    // If we have property access (e.g., user.username)
                    for (int i = 1; i < parts.length && value != null; i++) {
                        debug("  Accessing property: " + parts[i] + " of " + value.getClass().getSimpleName());
                        
                        // Special handling for size property
                        if (parts[i].equals("size") && value instanceof Collection) {
                            value = ((Collection<?>) value).size();
                            debug("  Got collection size: " + value);
                        } else if (value instanceof Collection && i < parts.length - 1) {
                            // Handle collections by mapping over their elements
                            Collection<?> collection = (Collection<?>) value;
                            debug("  Found collection with " + collection.size() + " elements");
                            int finalI = i;
                            value = collection.stream()
                                .map(item -> getFieldValue(item, parts[finalI]))
                                .filter(item -> item != null)
                                .map(Object::toString)
                                .collect(Collectors.joining(", "));
                            debug("  Mapped collection result: " + value);
                        } else {
                            value = getFieldValue(value, parts[i]);
                            debug("  Got field value: " + value);
                        }
                    }
                    
                    debug("  Final value for {" + varRef + "} = " + value);
                    if (value != null) {
                        result.append(value);
                    }
                    
                    // Move past the processed variable
                    args = args.substring(end + 1);
                    start = 0;
                }
                
                // Add any remaining text
                if (!args.isEmpty()) {
                    result.append(args);
                }
                
                String finalResult = result.toString();
                debug("Concatenation result: " + finalResult);
                yield CompletableFuture.completedFuture(finalResult);
            }
            case "length" -> {
                // Handle object property access (e.g., user.username)
                String[] parts = args.trim().split("\\.");
                Object value = variables.get(parts[0]);
                
                // If we have property access (e.g., user.username)
                for (int i = 1; i < parts.length && value != null; i++) {
                    debug("  Accessing property: " + parts[i] + " of " + value.getClass().getSimpleName());
                    value = getFieldValue(value, parts[i]);
                }

                debug("Getting length of: " + value);
                int length = 0;
                if (value instanceof String) {
                    length = ((String) value).length();
                } else if (value instanceof Collection) {
                    length = ((Collection<?>) value).size();
                }
                debug("Length result: " + length);
                yield CompletableFuture.completedFuture(length);
            }
            case "contains" -> {
                String[] parts = args.split(",");
                if (parts.length != 2) {
                    debug("Contains: Invalid arguments");
                    yield CompletableFuture.completedFuture(false);
                }
                Object container = variables.get(parts[0].trim());
                String search = parts[1].trim();
                debug("Checking if " + container + " contains " + search);
                boolean result = false;
                if (container instanceof String) {
                    result = ((String) container).contains(search);
                } else if (container instanceof Collection) {
                    result = ((Collection<?>) container).contains(search);
                }
                debug("Contains result: " + result);
                yield CompletableFuture.completedFuture(result);
            }
            case "where" -> {
                String[] parts = args.split(",", 2);
                if (parts.length != 2) {
                    yield CompletableFuture.completedFuture(null);
                }
                String collection = parts[0].trim();
                String condition = parts[1].trim();
                Object value = variables.get(collection);
                if (value instanceof java.util.Collection) {
                    yield CompletableFuture.completedFuture(
                        ((java.util.Collection<?>) value).stream()
                            .filter(item -> evaluateCondition(item, condition))
                            .collect(java.util.stream.Collectors.toList())
                    );
                }
                yield CompletableFuture.completedFuture(null);
            }
            case "order" -> {
                String[] parts = args.split(",", 2);
                if (parts.length != 2) {
                    yield CompletableFuture.completedFuture(null);
                }
                String collection = parts[0].trim();
                String orderBy = parts[1].trim();
                Object value = variables.get(collection);
                if (value instanceof java.util.Collection) {
                    yield CompletableFuture.completedFuture(
                        applySorting((java.util.Collection<?>) value, orderBy)
                    );
                }
                yield CompletableFuture.completedFuture(null);
            }
            case "map" -> {
                String[] parts = args.split("\\s+", 2);
                if (parts.length != 2) {
                    debug("Map: Invalid format - missing collection path or format string");
                    yield CompletableFuture.completedFuture(null);
                }
                
                String collectionPath = parts[0].trim();
                String formatString = parts[1].trim();
                debug("Map: Processing collection path: " + collectionPath);
                debug("Map: Format string: " + formatString);
                
                // Get the collection or single item to map over
                Object value;
                if (collectionPath.contains(".")) {
                    String[] pathParts = collectionPath.split("\\.");
                    value = variables.get(pathParts[0]);
                    for (int i = 1; i < pathParts.length && value != null; i++) {
                        value = getPropertyValue(value, pathParts[i]);
                    }
                } else {
                    value = variables.get(collectionPath);
                }
                
                debug("Map: Value to map over: " + value);
                
                if (value == null) {
                    debug("Map: Value is null");
                    yield CompletableFuture.completedFuture(null);
                }
                
                Collection<?> items;
                if (value instanceof Collection) {
                    items = (Collection<?>) value;
                } else {
                    items = List.of(value);
                }
                
                debug("Map: Processing " + items.size() + " items");
                
                // Create a list to store the mapped values
                List<String> mappedValues = new ArrayList<>();
                int index = 1; // Start index at 1 for user-friendly numbering
                
                // Process each item
                for (Object item : items) {
                    debug("Map: Processing item " + index + ": " + item);
                    String mappedValue = processMapString(formatString, item, index);
                    debug("Map: Mapped value: " + mappedValue);
                    mappedValues.add(mappedValue);
                    index++;
                }
                
                // Store results in variables with indexed keys
                String variableName = variables.get("__current_assignment__").toString();
                for (int i = 0; i < mappedValues.size(); i++) {
                    variables.put(variableName + "_" + i, mappedValues.get(i));
                }
                
                debug("Map: Final mapped values: " + mappedValues);
                yield CompletableFuture.completedFuture(mappedValues);
            }
            case "if" -> {
                debug("Evaluating if condition: " + args);
                debug("=== IF Function Evaluation ===");
                
                // Split into condition and results, handling nested commas and functions
                List<String> parts = new ArrayList<>();
                StringBuilder current = new StringBuilder();
                int parentheses = 0;
                int braces = 0;
                boolean inQuotes = false;
                
                for (char c : args.toCharArray()) {
                    if (c == '"') inQuotes = !inQuotes;
                    if (!inQuotes) {
                        if (c == '(') parentheses++;
                        else if (c == ')') parentheses--;
                        else if (c == '{') braces++;
                        else if (c == '}') braces--;
                    }
                    
                    if (c == ',' && parentheses == 0 && braces == 0 && !inQuotes) {
                        parts.add(current.toString().trim());
                        current = new StringBuilder();
                    } else {
                        current.append(c);
                    }
                }
                parts.add(current.toString().trim());
                
                // Handle both if-else and if-then-else syntax
                String condition;
                String trueResult;
                String falseResult;
                
                if (parts.size() == 2) {
                    condition = parts.get(0);
                    trueResult = parts.get(1);
                    falseResult = "null";
                } else if (parts.size() == 3) {
                    condition = parts.get(0);
                    trueResult = parts.get(1);
                    falseResult = parts.get(2);
                } else {
                    debug("If: Invalid number of arguments: " + parts.size());
                    yield CompletableFuture.completedFuture(null);
                }
                
                debug("Parsed if statement:");
                debug("  Condition: " + condition);
                debug("  True result: " + trueResult);
                debug("  False result: " + falseResult);

                // Check if condition starts with ! and handle inversion
                final boolean shouldInvertResult = condition.trim().startsWith("!");
                final String finalCondition = shouldInvertResult ? condition.substring(1).trim() : condition;
                
                if (shouldInvertResult) {
                    debug("  Inverting condition. New condition: " + finalCondition);
                }

                // First evaluate the condition
                CompletableFuture<Object> result = evaluateExpression(finalCondition)
                    .thenCompose(conditionResult -> {
                        debug("Condition result: " + conditionResult + " (" + (conditionResult != null ? conditionResult.getClass().getSimpleName() : "null") + ")");
                        debug("Raw condition result: " + conditionResult);
                        
                        boolean isTrue;
                        
                        if (conditionResult instanceof Boolean) {
                            isTrue = (Boolean) conditionResult;
                            debug("Boolean evaluation: " + isTrue);
                        } else if (conditionResult instanceof Number) {
                            isTrue = ((Number) conditionResult).doubleValue() != 0;
                            debug("Number evaluation: " + conditionResult + " -> " + isTrue);
                        } else if (conditionResult instanceof Collection) {
                            isTrue = !((Collection<?>) conditionResult).isEmpty();
                            debug("Collection evaluation: size=" + ((Collection<?>) conditionResult).size() + " -> " + isTrue);
                        } else if (conditionResult instanceof String) {
                            String strValue = ((String) conditionResult).trim().toLowerCase();
                            if (strValue.equals("true") || strValue.equals("false")) {
                                isTrue = Boolean.parseBoolean(strValue);
                                debug("String boolean evaluation: " + strValue + " -> " + isTrue);
                            } else {
                                isTrue = !strValue.isEmpty();
                                debug("String non-empty evaluation: '" + strValue + "' -> " + isTrue);
                            }
                        } else {
                            isTrue = conditionResult != null;
                            debug("Null check evaluation: " + conditionResult + " -> " + isTrue);
                        }
                        
                        // Apply inversion if needed
                        if (shouldInvertResult) {
                            isTrue = !isTrue;
                            debug("Inverted condition result to: " + isTrue);
                        }
                        
                        debug("Final condition result: " + isTrue);
                        
                        // Now evaluate the appropriate result expression
                        String resultExpr = isTrue ? trueResult : falseResult;
                        debug("Evaluating " + (isTrue ? "true" : "false") + " branch: " + resultExpr);
                        
                        // Check if the result contains a function call
                        Matcher funcMatcher = FUNCTION_PATTERN.matcher(resultExpr.trim());
                        if (funcMatcher.find()) {
                            String funcName = funcMatcher.group(1);
                            String funcArgs = funcMatcher.group(2);
                            debug("Found nested function in result: " + funcName);
                            debug("With arguments: " + funcArgs);
                            debug("Nested function call: " + funcName + "(" + funcArgs + ")");
                            return executeFunction(funcName, funcArgs);
                        }
                        
                        // If no function call, evaluate as regular expression
                        return evaluateExpression(resultExpr);
                    });
                yield result;
            }
            default -> {
                debug("Unknown function: " + name);
                yield CompletableFuture.completedFuture(null);
            }
        };
    }

    private boolean evaluateCondition(Object item, String condition) {
        try {
            String[] parts = condition.split(":");
            if (parts.length != 3) return false;
            
            String field = parts[0];
            String operator = parts[1];
            String value = parts[2];
            
            Object fieldValue = getFieldValue(item, field);
            if (fieldValue == null) return false;
            
            if (fieldValue instanceof Number) {
                double fieldNum = ((Number) fieldValue).doubleValue();
                double valueNum = Double.parseDouble(value);
                return switch (operator) {
                    case "eq" -> fieldNum == valueNum;
                    case "gt" -> fieldNum > valueNum;
                    case "lt" -> fieldNum < valueNum;
                    case "gte" -> fieldNum >= valueNum;
                    case "lte" -> fieldNum <= valueNum;
                    default -> false;
                };
            } else {
                String fieldStr = fieldValue.toString();
                return switch (operator) {
                    case "eq" -> fieldStr.equals(value);
                    case "contains" -> fieldStr.contains(value);
                    case "startsWith" -> fieldStr.startsWith(value);
                    case "endsWith" -> fieldStr.endsWith(value);
                    default -> false;
                };
            }
        } catch (Exception e) {
            return false;
        }
    }

    private Collection<?> applySorting(Collection<?> items, String orderBy) {
        String[] orderParts = orderBy.split(",");
        List<?> list = new ArrayList<>(items);
        
        return list.stream().sorted((a, b) -> {
            for (String order : orderParts) {
                String[] parts = order.split(":");
                String field = parts[0];
                boolean ascending = parts.length == 1 || parts[1].equals("asc");
                
                Object valueA = getFieldValue(a, field);
                Object valueB = getFieldValue(b, field);
                
                if (valueA instanceof Comparable && valueB instanceof Comparable) {
                    int comp = ((Comparable) valueA).compareTo(valueB);
                    if (comp != 0) return ascending ? comp : -comp;
                }
            }
            return 0;
        }).collect(java.util.stream.Collectors.toList());
    }

    private Object getFieldValue(Object obj, String fieldName) {
        try {
            // Special handling for collection size
            if (fieldName.equals("size") && obj instanceof Collection) {
                return ((Collection<?>) obj).size();
            }

            java.lang.reflect.Field field = findField(obj.getClass(), fieldName);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), fieldName);
            }
        }
        return null;
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    private String processMapString(String formatString, Object item, int index) {
        StringBuilder result = new StringBuilder();
        int pos = 0;
        
        debug("ProcessMapString: Starting with format: " + formatString);
        debug("ProcessMapString: Current item: " + item);
        
        // Store the current item in variables temporarily for condition evaluation
        Object previousCurrent = variables.get("current");
        variables.put("current", item);
        
        try {
            while (pos < formatString.length()) {
                int ifStart = formatString.indexOf("if(", pos);
                int varStart = formatString.indexOf("{", pos);
                
                if (ifStart == -1 && varStart == -1) {
                    result.append(formatString.substring(pos));
                    break;
                }
                
                if (ifStart != -1 && (varStart == -1 || ifStart < varStart)) {
                    // Handle if condition
                    result.append(formatString.substring(pos, ifStart));
                    Matcher ifMatcher = MAP_IF_PATTERN.matcher(formatString.substring(ifStart));
                    if (ifMatcher.find()) {
                        String condition = ifMatcher.group(1).trim();
                        String trueValue = ifMatcher.group(2).trim().replace("'", "");
                        String falseValue = ifMatcher.group(3).trim().replace("'", "");
                        
                        debug("ProcessMapString: Found if condition: " + condition);
                        debug("ProcessMapString: True value: " + trueValue);
                        debug("ProcessMapString: False value: " + falseValue);
                        
                        try {
                            boolean isTrue = false;
                            
                            if (condition.startsWith("current.")) {
                                String propertyPath = condition.substring(8);
                                String[] parts = propertyPath.split("\\s+");
                                
                                if (parts.length == 3 && parts[1].equals(">")) {
                                    // Handle comparison case: current.property > value
                                    Object value = getPropertyValue(item, parts[0]);
                                    debug("ProcessMapString: Got property value: " + value);
                                    
                                    if (value instanceof Number) {
                                        double numValue = ((Number) value).doubleValue();
                                        double compareValue = Double.parseDouble(parts[2]);
                                        isTrue = numValue > compareValue;
                                        debug("ProcessMapString: Comparing " + numValue + " > " + compareValue + " = " + isTrue);
                                    } else if (value instanceof Collection) {
                                        int size = ((Collection<?>) value).size();
                                        double compareValue = Double.parseDouble(parts[2]);
                                        isTrue = size > compareValue;
                                        debug("ProcessMapString: Comparing collection size " + size + " > " + compareValue + " = " + isTrue);
                                    }
                                } else {
                                    // Handle direct property access
                                    Object value = getPropertyValue(item, propertyPath);
                                    debug("ProcessMapString: Direct property value: " + value);
                                    
                                    if (value instanceof Number) {
                                        isTrue = ((Number) value).doubleValue() > 0;
                                    } else if (value instanceof Collection) {
                                        isTrue = !((Collection<?>) value).isEmpty();
                                    } else if (value instanceof Boolean) {
                                        isTrue = (Boolean) value;
                                    } else {
                                        isTrue = value != null;
                                    }
                                }
                            }
                            
                            debug("ProcessMapString: Condition evaluated to: " + isTrue);
                            result.append(isTrue ? trueValue : falseValue);
                        } catch (Exception e) {
                            debug("ProcessMapString: Error evaluating condition: " + e.getMessage());
                            e.printStackTrace();
                            result.append("ERROR");
                        }
                        pos = ifStart + ifMatcher.end();
                    } else {
                        result.append("if(");
                        pos = ifStart + 3;
                    }
                } else {
                    // Handle variable replacement
                    result.append(formatString.substring(pos, varStart));
                    int end = formatString.indexOf("}", varStart);
                    if (end == -1) {
                        result.append(formatString.substring(varStart));
                        break;
                    }
                    
                    String var = formatString.substring(varStart + 1, end).trim();
                    debug("ProcessMapString: Processing variable: " + var);
                    
                    if (var.equals("index")) {
                        result.append(index);
                    } else if (var.equals("current")) {
                        result.append(item);
                    } else if (var.startsWith("current.")) {
                        String property = var.substring(8);
                        Object value = getPropertyValue(item, property);
                        debug("ProcessMapString: Got property value: " + value);
                        result.append(value != null ? value : "null");
                    }
                    pos = end + 1;
                }
            }
            
            String finalResult = result.toString();
            debug("ProcessMapString: Final result: " + finalResult);
            return finalResult;
        } finally {
            // Restore the previous current value
            if (previousCurrent != null) {
                variables.put("current", previousCurrent);
            } else {
                variables.remove("current");
            }
        }
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

            // For nested properties (e.g. rank.name)
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
                    
                    // Get field value using reflection
                    try {
                        java.lang.reflect.Field field = current.getClass().getDeclaredField(part);
                        field.setAccessible(true);
                        current = field.get(current);
                    } catch (NoSuchFieldException e) {
                        // Try superclass if field not found
                        Class<?> superClass = current.getClass().getSuperclass();
                        while (superClass != null) {
                            try {
                                java.lang.reflect.Field field = superClass.getDeclaredField(part);
                                field.setAccessible(true);
                                current = field.get(current);
                                break;
                            } catch (NoSuchFieldException ex) {
                                superClass = superClass.getSuperclass();
                            }
                        }
                        if (superClass == null) {
                            return null;
                        }
                    }
                }
                return current;
            }
            // For simple properties
            else {
                try {
                    java.lang.reflect.Field field = entity.getClass().getDeclaredField(propertyPath);
                    field.setAccessible(true);
                    return field.get(entity);
                } catch (NoSuchFieldException e) {
                    // Try superclass if field not found
                    Class<?> superClass = entity.getClass().getSuperclass();
                    while (superClass != null) {
                        try {
                            java.lang.reflect.Field field = superClass.getDeclaredField(propertyPath);
                            field.setAccessible(true);
                            return field.get(entity);
                        } catch (NoSuchFieldException ex) {
                            superClass = superClass.getSuperclass();
                        }
                    }
                    return null;
                }
            }
        } catch (Exception e) {
            debug("Error accessing property: " + e.getMessage());
            return null;
        }
    }
} 