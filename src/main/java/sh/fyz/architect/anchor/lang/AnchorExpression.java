package sh.fyz.architect.anchor.lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnchorExpression {
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
        "\\$\\{([^}]+)\\}|" +           // Variable references ${var}
        "\"([^\"]*)\"|" +               // String literals
        "([0-9]+(?:\\.[0-9]+)?)|" +     // Numbers
        "(\\+|-|\\*|/|==|!=|>|<|>=|<=)|" + // Operators
        "(&&|\\|\\|)|" +                // Logical operators
        "([a-zA-Z][a-zA-Z0-9_]*)|" +    // Identifiers
        "(\\(|\\))|" +                  // Parentheses
        "(\\.|,)"                       // Dots and commas
    );

    private final String expression;
    private final Map<String, Object> variables;

    public AnchorExpression(String expression) {
        this.expression = expression;
        this.variables = new HashMap<>();
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    public Object evaluate() {
        List<Token> tokens = tokenize(expression);
        return evaluateTokens(tokens, 0, tokens.size());
    }

    private List<Token> tokenize(String expr) {
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(expr);

        while (matcher.find()) {
            if (matcher.group(1) != null) {
                // Variable reference
                tokens.add(new Token(TokenType.VAR_REF, matcher.group(1)));
            } else if (matcher.group(2) != null) {
                // String literal
                tokens.add(new Token(TokenType.STRING, matcher.group(2)));
            } else if (matcher.group(3) != null) {
                // Number
                tokens.add(new Token(TokenType.NUMBER, matcher.group(3)));
            } else if (matcher.group(4) != null) {
                // Operator
                tokens.add(new Token(TokenType.OPERATOR, matcher.group(4)));
            } else if (matcher.group(5) != null) {
                // Logical operator
                tokens.add(new Token(TokenType.LOGICAL, matcher.group(5)));
            } else if (matcher.group(6) != null) {
                // Identifier
                tokens.add(new Token(TokenType.IDENTIFIER, matcher.group(6)));
            } else if (matcher.group(7) != null) {
                // Parentheses
                tokens.add(new Token(TokenType.PAREN, matcher.group(7)));
            } else if (matcher.group(8) != null) {
                // Dot or comma
                tokens.add(new Token(TokenType.SEPARATOR, matcher.group(8)));
            }
        }
        return tokens;
    }

    private Object evaluateTokens(List<Token> tokens, int start, int end) {
        if (start >= end) return null;

        // Handle parentheses first
        for (int i = start; i < end; i++) {
            if (tokens.get(i).type == TokenType.PAREN && tokens.get(i).value.equals("(")) {
                int closing = findClosingParen(tokens, i);
                if (closing != -1) {
                    return evaluateTokens(tokens, i + 1, closing);
                }
            }
        }

        // Handle logical operators
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.type == TokenType.LOGICAL) {
                Object left = evaluateTokens(tokens, start, i);
                Object right = evaluateTokens(tokens, i + 1, end);
                return evaluateLogical(token.value, left, right);
            }
        }

        // Handle comparison operators
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.type == TokenType.OPERATOR && isComparisonOperator(token.value)) {
                Object left = evaluateTokens(tokens, start, i);
                Object right = evaluateTokens(tokens, i + 1, end);
                return evaluateComparison(token.value, left, right);
            }
        }

        // Handle arithmetic
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.type == TokenType.OPERATOR && isArithmeticOperator(token.value)) {
                Object left = evaluateTokens(tokens, start, i);
                Object right = evaluateTokens(tokens, i + 1, end);
                return evaluateArithmetic(token.value, left, right);
            }
        }

        // Handle variable references
        if (tokens.get(start).type == TokenType.VAR_REF) {
            String varName = tokens.get(start).value;
            return variables.get(varName);
        }

        // Handle literals
        Token token = tokens.get(start);
        if (token.type == TokenType.STRING) {
            return token.value;
        } else if (token.type == TokenType.NUMBER) {
            if (token.value.contains(".")) {
                return Double.parseDouble(token.value);
            } else {
                return Integer.parseInt(token.value);
            }
        }

        return null;
    }

    private boolean isComparisonOperator(String op) {
        return op.equals("==") || op.equals("!=") || op.equals(">") || 
               op.equals("<") || op.equals(">=") || op.equals("<=");
    }

    private boolean isArithmeticOperator(String op) {
        return op.equals("+") || op.equals("-") || op.equals("*") || op.equals("/");
    }

    private Object evaluateLogical(String op, Object left, Object right) {
        if (!(left instanceof Boolean) || !(right instanceof Boolean)) {
            throw new RuntimeException("Logical operators require boolean operands");
        }
        boolean l = (Boolean) left;
        boolean r = (Boolean) right;
        return switch (op) {
            case "&&" -> l && r;
            case "||" -> l || r;
            default -> throw new RuntimeException("Unknown logical operator: " + op);
        };
    }

    private Object evaluateComparison(String op, Object left, Object right) {
        if (left == null || right == null) return false;
        
        if (left instanceof Number && right instanceof Number) {
            double l = ((Number) left).doubleValue();
            double r = ((Number) right).doubleValue();
            return switch (op) {
                case "==" -> l == r;
                case "!=" -> l != r;
                case ">" -> l > r;
                case "<" -> l < r;
                case ">=" -> l >= r;
                case "<=" -> l <= r;
                default -> throw new RuntimeException("Unknown comparison operator: " + op);
            };
        } else {
            String l = left.toString();
            String r = right.toString();
            return switch (op) {
                case "==" -> l.equals(r);
                case "!=" -> !l.equals(r);
                case ">" -> l.compareTo(r) > 0;
                case "<" -> l.compareTo(r) < 0;
                case ">=" -> l.compareTo(r) >= 0;
                case "<=" -> l.compareTo(r) <= 0;
                default -> throw new RuntimeException("Unknown comparison operator: " + op);
            };
        }
    }

    private Object evaluateArithmetic(String op, Object left, Object right) {
        if (!(left instanceof Number) || !(right instanceof Number)) {
            if (op.equals("+") && (left instanceof String || right instanceof String)) {
                return left.toString() + right.toString();
            }
            throw new RuntimeException("Arithmetic operators require numeric operands");
        }
        
        double l = ((Number) left).doubleValue();
        double r = ((Number) right).doubleValue();
        return switch (op) {
            case "+" -> l + r;
            case "-" -> l - r;
            case "*" -> l * r;
            case "/" -> l / r;
            default -> throw new RuntimeException("Unknown arithmetic operator: " + op);
        };
    }

    private int findClosingParen(List<Token> tokens, int start) {
        int count = 1;
        for (int i = start + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type == TokenType.PAREN) {
                if (token.value.equals("(")) count++;
                else if (token.value.equals(")")) count--;
                if (count == 0) return i;
            }
        }
        return -1;
    }

    private static class Token {
        final TokenType type;
        final String value;

        Token(TokenType type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    private enum TokenType {
        VAR_REF,
        STRING,
        NUMBER,
        OPERATOR,
        LOGICAL,
        IDENTIFIER,
        PAREN,
        SEPARATOR
    }
} 