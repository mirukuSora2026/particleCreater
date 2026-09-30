package com.mirukusora26.particleCreater.math;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, real-valued expression compiler. It never executes Java or scripts. */
public final class Expression {
    @FunctionalInterface public interface MathFunction { double apply(double[] arguments); }
    private record FunctionSpec(int min, int max, MathFunction function) {}
    @FunctionalInterface private interface Node { double eval(Map<String, Double> vars); }
    private static final Map<String, FunctionSpec> FUNCTIONS = new ConcurrentHashMap<>();
    private final String source;
    private final Node root;

    static {
        unary("sin", Math::sin); unary("cos", Math::cos); unary("tan", Math::tan);
        unary("asin", Math::asin); unary("acos", Math::acos); unary("atan", Math::atan);
        unary("sinh", Math::sinh); unary("cosh", Math::cosh); unary("tanh", Math::tanh);
        unary("sqrt", Math::sqrt); unary("cbrt", Math::cbrt); unary("abs", Math::abs);
        unary("exp", Math::exp); unary("ln", Math::log); unary("log", Math::log10);
        unary("floor", Math::floor); unary("ceil", Math::ceil); unary("round", x -> (double) Math.round(x));
        unary("sign", Math::signum); unary("radians", Math::toRadians); unary("degrees", Math::toDegrees);
        registerFunction("pow", 2, 2, a -> Math.pow(a[0], a[1]));
        registerFunction("atan2", 2, 2, a -> Math.atan2(a[0], a[1]));
        registerFunction("min", 2, 16, a -> { double v = a[0]; for (double n : a) v = Math.min(v, n); return v; });
        registerFunction("max", 2, 16, a -> { double v = a[0]; for (double n : a) v = Math.max(v, n); return v; });
        registerFunction("clamp", 3, 3, a -> Math.max(a[1], Math.min(a[2], a[0])));
        registerFunction("mod", 2, 2, a -> a[0] % a[1]);
        registerFunction("sum", 1, 16, a -> { double v = 0; for (double n : a) v += n; return v; });
        registerFunction("product", 1, 16, a -> { double v = 1; for (double n : a) v *= n; return v; });
        registerFunction("noise", 1, 4, a -> {
            double v = 0;
            for (int i = 0; i < a.length; i++) v += a[i] * (127.1 + i * 43.7);
            double s = Math.sin(v) * 43758.5453;
            return (s - Math.floor(s)) * 2 - 1;
        });
    }

    private static void unary(String name, java.util.function.DoubleUnaryOperator f) {
        registerFunction(name, 1, 1, a -> f.applyAsDouble(a[0]));
    }

    /** Trusted plugins may add deterministic numeric functions before compiling expressions. */
    public static void registerFunction(String name, int minArguments, int maxArguments, MathFunction function) {
        if (name == null || !name.matches("[a-z][a-z0-9_]*") || minArguments < 0 || maxArguments < minArguments || maxArguments > 16 || function == null)
            throw new IllegalArgumentException("Invalid math function registration");
        FUNCTIONS.put(name, new FunctionSpec(minArguments, maxArguments, function));
    }

    public static Set<String> functionNames() { return Set.copyOf(FUNCTIONS.keySet()); }

    public static Expression compile(String source, Set<String> variables) {
        if (source == null || source.isBlank() || source.length() > 4096)
            throw new IllegalArgumentException("Expression must contain 1 to 4096 characters");
        Parser parser = new Parser(source, variables);
        Node root = parser.parse();
        return new Expression(source, root);
    }

    private Expression(String source, Node root) { this.source = source; this.root = root; }
    public String source() { return source; }

    public double evaluate(Map<String, Double> variables) {
        double result = root.eval(variables);
        if (!Double.isFinite(result)) throw new ArithmeticException("Non-finite expression result: " + source);
        return result;
    }

    private static final class Parser {
        private final String input;
        private final Set<String> variables;
        private int pos;
        private int nodes;
        private int depth;

        Parser(String input, Set<String> variables) { this.input = input; this.variables = variables; }
        Node parse() {
            Node value = expression();
            skip();
            if (pos != input.length()) fail("Unexpected token");
            return value;
        }
        private Node expression() { return or(); }
        private Node or() {
            Node left = and();
            while (take("||")) { Node a = left, b = and(); left = node(v -> a.eval(v) != 0 || b.eval(v) != 0 ? 1 : 0); }
            return left;
        }
        private Node and() {
            Node left = compare();
            while (take("&&")) { Node a = left, b = compare(); left = node(v -> a.eval(v) != 0 && b.eval(v) != 0 ? 1 : 0); }
            return left;
        }
        private Node compare() {
            Node left = add();
            while (true) {
                String op = null;
                for (String candidate : List.of("<=", ">=", "==", "!=", "<", ">")) if (take(candidate)) { op = candidate; break; }
                if (op == null) return left;
                Node a = left, b = add(); String selected = op;
                left = node(v -> {
                    double x = a.eval(v), y = b.eval(v);
                    return switch (selected) {
                        case "<" -> x < y ? 1 : 0; case ">" -> x > y ? 1 : 0;
                        case "<=" -> x <= y ? 1 : 0; case ">=" -> x >= y ? 1 : 0;
                        case "==" -> x == y ? 1 : 0; default -> x != y ? 1 : 0;
                    };
                });
            }
        }
        private Node add() {
            Node left = multiply();
            while (true) {
                if (take("+")) { Node a = left, b = multiply(); left = node(v -> a.eval(v) + b.eval(v)); }
                else if (take("-")) { Node a = left, b = multiply(); left = node(v -> a.eval(v) - b.eval(v)); }
                else return left;
            }
        }
        private Node multiply() {
            Node left = unary();
            while (true) {
                if (take("*")) { Node a = left, b = unary(); left = node(v -> a.eval(v) * b.eval(v)); }
                else if (take("/")) { Node a = left, b = unary(); left = node(v -> a.eval(v) / b.eval(v)); }
                else if (take("%")) { Node a = left, b = unary(); left = node(v -> a.eval(v) % b.eval(v)); }
                else return left;
            }
        }
        private Node unary() {
            if (take("+")) return unary();
            if (take("-")) { Node n = unary(); return node(v -> -n.eval(v)); }
            if (take("!")) { Node n = unary(); return node(v -> n.eval(v) == 0 ? 1 : 0); }
            return power();
        }
        private Node power() {
            Node base = primary();
            if (take("^")) { Node exponent = unary(); return node(v -> Math.pow(base.eval(v), exponent.eval(v))); }
            return base;
        }
        private Node primary() {
            if (++depth > 64) fail("Expression nesting exceeds 64");
            try {
                if (take("(")) { Node n = expression(); require(")"); return n; }
                skip();
                if (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) return number();
                String name = identifier();
                if (name.isEmpty()) fail("Expected a number, variable, or function");
                name = name.toLowerCase(Locale.ROOT);
                if (take("(")) {
                    List<Node> args = new ArrayList<>();
                    if (!take(")")) {
                        do { args.add(expression()); } while (take(","));
                        require(")");
                    }
                    if (name.equals("if")) {
                        if (args.size() != 3) fail("if needs 3 arguments");
                        return node(v -> args.get(0).eval(v) != 0 ? args.get(1).eval(v) : args.get(2).eval(v));
                    }
                    FunctionSpec spec = FUNCTIONS.get(name);
                    if (spec == null) fail("Unknown function '" + name + "'");
                    if (args.size() < spec.min || args.size() > spec.max) fail("Wrong argument count for '" + name + "'");
                    return node(v -> {
                        double[] values = new double[args.size()];
                        for (int i = 0; i < values.length; i++) values[i] = args.get(i).eval(v);
                        return spec.function.apply(values);
                    });
                }
                if (name.equals("pi")) return node(v -> Math.PI);
                if (name.equals("e")) return node(v -> Math.E);
                if (name.equals("tau")) return node(v -> Math.PI * 2);
                if (!variables.contains(name)) fail("Unknown variable '" + name + "'");
                String variable = name;
                return node(v -> {
                    Double value = v.get(variable);
                    if (value == null) throw new IllegalArgumentException("Missing variable '" + variable + "'");
                    return value;
                });
            } finally { depth--; }
        }
        private Node number() {
            int begin = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) pos++;
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            try { double value = Double.parseDouble(input.substring(begin, pos)); return node(v -> value); }
            catch (NumberFormatException e) { fail("Invalid number"); return null; }
        }
        private String identifier() {
            skip(); int begin = pos;
            if (pos < input.length() && (Character.isLetter(input.charAt(pos)) || input.charAt(pos) == '_')) {
                pos++;
                while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) pos++;
            }
            return input.substring(begin, pos);
        }
        private boolean take(String token) { skip(); if (input.startsWith(token, pos)) { pos += token.length(); return true; } return false; }
        private void require(String token) { if (!take(token)) fail("Expected '" + token + "'"); }
        private void skip() { while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++; }
        private Node node(Node n) { if (++nodes > 256) fail("Expression exceeds 256 operations"); return n; }
        private void fail(String message) { throw new IllegalArgumentException(message + " at character " + (pos + 1) + " in '" + input + "'"); }
    }
}
