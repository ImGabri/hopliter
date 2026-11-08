package com.example.helditemfixture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the SNBT that /data get renders, including typed numbers and arrays. */
final class Snbt {
    enum Type {
        STRING, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, INT_ARRAY,
        LONG_ARRAY, LIST, COMPOUND
    }

    sealed interface Tag permits Atom, Sequence, Compound {
        Type type();
    }

    record Atom(Type type, String value) implements Tag {}

    record Sequence(Type type, List<Tag> values) implements Tag {}

    record Compound(Map<String, Tag> values) implements Tag {
        @Override
        public Type type() {
            return Type.COMPOUND;
        }
    }

    private static final Pattern INTEGER = Pattern.compile("([+-]?[0-9]+)([bBsSiIlL]?)");
    private static final Pattern DECIMAL = Pattern.compile(
            "([+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|NaN|Infinity))([fFdD]?)");

    private final String input;
    private int position;

    private Snbt(String input) {
        this.input = input;
    }

    static Tag read(String input) {
        Snbt parser = new Snbt(input);
        Tag tag = parser.value(0);
        if (parser.peek() != '\0') {
            throw parser.error("Unexpected text after tag");
        }
        return tag;
    }

    static List<String> keyPath(String input) {
        Snbt parser = new Snbt(input);
        List<String> keys = new ArrayList<>();
        do {
            char first = parser.peek();
            if (first == '\'' || first == '"') {
                keys.add(parser.quoted());
            } else {
                int start = parser.position;
                while (parser.position < input.length()
                        && input.charAt(parser.position) != '.'
                        && !Character.isWhitespace(input.charAt(parser.position))) {
                    parser.position++;
                }
                if (parser.position == start) {
                    throw parser.error("Expected a UID path key");
                }
                keys.add(input.substring(start, parser.position));
            }
        } while (parser.take('.'));
        if (parser.peek() != '\0') {
            throw parser.error("Expected a dot between UID path keys");
        }
        return List.copyOf(keys);
    }

    static boolean isFolded(String input) {
        char quote = '\0';
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quote != '\0') {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = '\0';
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '.' && input.startsWith("...", i)) {
                return true;
            }
        }
        return false;
    }

    static String write(Tag tag) {
        StringBuilder out = new StringBuilder();
        writeTag(tag, out);
        return out.toString();
    }

    private static void writeTag(Tag tag, StringBuilder out) {
        if (tag instanceof Atom atom) {
            writeAtom(atom, out);
        } else if (tag instanceof Sequence sequence) {
            switch (sequence.type()) {
                case BYTE_ARRAY -> {
                    out.append("[B;");
                    boolean first = true;
                    for (Tag v : sequence.values()) {
                        if (!first) out.append(',');
                        out.append(atomValue((Atom) v));
                        first = false;
                    }
                    out.append(']');
                }
                case INT_ARRAY -> {
                    out.append("[I;");
                    boolean first = true;
                    for (Tag v : sequence.values()) {
                        if (!first) out.append(',');
                        out.append(atomValue((Atom) v));
                        first = false;
                    }
                    out.append(']');
                }
                case LONG_ARRAY -> {
                    out.append("[L;");
                    boolean first = true;
                    for (Tag v : sequence.values()) {
                        if (!first) out.append(',');
                        out.append(atomValue((Atom) v));
                        first = false;
                    }
                    out.append(']');
                }
                default -> {
                    out.append('[');
                    boolean first = true;
                    for (Tag v : sequence.values()) {
                        if (!first) out.append(',');
                        writeTag(v, out);
                        first = false;
                    }
                    out.append(']');
                }
            }
        } else if (tag instanceof Compound compound) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Tag> e : compound.values().entrySet()) {
                if (!first) out.append(',');
                out.append('"');
                out.append(escapeString(e.getKey()));
                out.append('"');
                out.append(':');
                writeTag(e.getValue(), out);
                first = false;
            }
            out.append('}');
        }
    }

    private static void writeAtom(Atom atom, StringBuilder out) {
        switch (atom.type()) {
            case STRING -> {
                out.append('"');
                out.append(escapeString(atom.value()));
                out.append('"');
            }
            case BYTE -> out.append(atomValue(atom)).append('b');
            case SHORT -> out.append(atomValue(atom)).append('s');
            case INT -> out.append(atomValue(atom));
            case LONG -> out.append(atomValue(atom)).append('l');
            case FLOAT -> out.append(atomValue(atom)).append('f');
            case DOUBLE -> out.append(atomValue(atom)).append('d');
            default -> {
                out.append('"');
                out.append(escapeString(atom.value()));
                out.append('"');
            }
        }
    }

    private static String atomValue(Atom atom) {
        return atom.value();
    }

    private static String escapeString(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static Tag atPath(Tag tag, List<String> keys) {
        for (String key : keys) {
            if (!(tag instanceof Compound compound)) {
                return null;
            }
            tag = compound.values().get(key);
        }
        return tag;
    }

    static String display(Tag tag) {
        if (tag instanceof Sequence sequence) {
            return sequence.values().stream().map(Snbt::display).toList().toString();
        }
        if (!(tag instanceof Atom atom)) {
            return tag.type().name().toLowerCase(Locale.ROOT);
        }
        StringBuilder out = new StringBuilder();
        for (char c : atom.value().toCharArray()) {
            if (Character.isISOControl(c)) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private Tag value(int depth) {
        if (depth > 512) {
            throw error("SNBT nesting limit exceeded");
        }
        return switch (peek()) {
            case '{' -> compound(depth);
            case '[' -> sequence(depth);
            case '\'', '"' -> new Atom(Type.STRING, quoted());
            default -> atom(token());
        };
    }

    private Compound compound(int depth) {
        expect('{');
        Map<String, Tag> fields = new LinkedHashMap<>();
        while (peek() != '}') {
            char c = peek();
            String key = c == '\'' || c == '"' ? quoted() : token();
            expect(':');
            if (fields.putIfAbsent(key, value(depth + 1)) != null) {
                throw error("Duplicate compound key");
            }
            if (!take(',')) {
                break;
            }
        }
        expect('}');
        return new Compound(Map.copyOf(fields));
    }

    private Sequence sequence(int depth) {
        expect('[');
        char c = peek();
        Type type = Type.LIST;
        if (position + 1 < input.length() && input.charAt(position + 1) == ';') {
            type = switch (c) {
                case 'B' -> Type.BYTE_ARRAY;
                case 'I' -> Type.INT_ARRAY;
                case 'L' -> Type.LONG_ARRAY;
                default -> throw error("Unknown array type");
            };
            position += 2;
        }
        List<Tag> values = new ArrayList<>();
        while (peek() != ']') {
            Tag tag = value(depth + 1);
            if (type != Type.LIST) {
                if (!(tag instanceof Atom atom)
                        || !INTEGER.matcher(atom.value()).matches()
                        || atom.type() == Type.STRING) {
                    throw error("Expected an integer array element");
                }
                Type elementType = switch (type) {
                    case BYTE_ARRAY -> Type.BYTE;
                    case INT_ARRAY -> Type.INT;
                    case LONG_ARRAY -> Type.LONG;
                    default -> throw error("Unknown array type");
                };
                tag = new Atom(elementType, atom.value());
            }
            values.add(tag);
            if (!take(',')) {
                break;
            }
        }
        expect(']');
        return new Sequence(type, List.copyOf(values));
    }

    private static Atom atom(String token) {
        Matcher integer = INTEGER.matcher(token);
        if (integer.matches()) {
            Type type = switch (integer.group(2).toLowerCase(Locale.ROOT)) {
                case "b" -> Type.BYTE;
                case "s" -> Type.SHORT;
                case "l" -> Type.LONG;
                default -> Type.INT;
            };
            return new Atom(type, Long.toString(Long.parseLong(integer.group(1))));
        }
        Matcher decimal = DECIMAL.matcher(token);
        if (decimal.matches()) {
            String number = decimal.group(1);
            if (decimal.group(2).equalsIgnoreCase("f")) {
                return new Atom(Type.FLOAT, Float.toString(Float.parseFloat(number)));
            }
            return new Atom(Type.DOUBLE, Double.toString(Double.parseDouble(number)));
        }
        return switch (token) {
            case "true" -> new Atom(Type.BYTE, "1");
            case "false" -> new Atom(Type.BYTE, "0");
            default -> new Atom(Type.STRING, token);
        };
    }

    private String quoted() {
        char quote = input.charAt(position++);
        StringBuilder value = new StringBuilder();
        while (position < input.length()) {
            char c = input.charAt(position++);
            if (c == quote) {
                return value.toString();
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            if (position == input.length()) {
                break;
            }
            char escape = input.charAt(position++);
            switch (escape) {
                case '\'', '"', '\\' -> value.append(escape);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 's' -> value.append(' ');
                case 't' -> value.append('\t');
                case 'x' -> value.appendCodePoint(hex(2));
                case 'u' -> value.appendCodePoint(hex(4));
                case 'U' -> value.appendCodePoint(hex(8));
                case 'N' -> {
                    expect('{');
                    int end = input.indexOf('}', position);
                    if (end == -1) {
                        throw error("Unclosed Unicode name");
                    }
                    value.appendCodePoint(Character.codePointOf(input.substring(position, end)));
                    position = end + 1;
                }
                default -> throw error("Unknown string escape");
            }
        }
        throw error("Unclosed string");
    }

    private int hex(int length) {
        if (position + length > input.length()) {
            throw error("Incomplete hexadecimal escape");
        }
        int result = Integer.parseUnsignedInt(input.substring(position, position + length), 16);
        position += length;
        return result;
    }

    private String token() {
        int start = position;
        while (position < input.length()) {
            char c = input.charAt(position);
            if (Character.isWhitespace(c) || "{}[],:;".indexOf(c) >= 0) {
                break;
            }
            position++;
        }
        if (start == position) {
            throw error("Expected a key or value");
        }
        return input.substring(start, position);
    }

    private char peek() {
        while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
            position++;
        }
        return position < input.length() ? input.charAt(position) : '\0';
    }

    private boolean take(char c) {
        if (peek() != c) {
            return false;
        }
        position++;
        return true;
    }

    private void expect(char c) {
        if (!take(c)) {
            throw error("Expected '" + c + "'");
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + position);
    }
}
