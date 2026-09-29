package de.kronwerke.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for the link protocol: objects become LinkedHashMap, arrays ArrayList,
 * numbers Long or Double. The launcher has no dependencies, so this lives here.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("json: not an object");
        return (Map<String, Object>) v;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("json: " + what + " at " + i);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return obj();
            case '[':
                return arr();
            case '"':
                return str();
            case 't':
                return lit("true", Boolean.TRUE);
            case 'f':
                return lit("false", Boolean.FALSE);
            case 'n':
                return lit("null", null);
            default:
                return num();
        }
    }

    private Object lit(String word, Object v) {
        if (!s.startsWith(word, i)) throw error("bad literal");
        i += word.length();
        return v;
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw error("expected a key");
            String k = str();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw error("expected ':'");
            i++;
            ws();
            m.put(k, value());
            ws();
            if (i >= s.length()) throw error("unexpected end");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (i >= s.length()) throw error("unexpected end");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String str() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw error("bad escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("bad unicode escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw error("bad escape");
            }
        }
    }

    private Object num() {
        int start = i;
        if (i < s.length() && s.charAt(i) == '-') i++;
        boolean frac = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                frac = true;
                i++;
            } else {
                break;
            }
        }
        String t = s.substring(start, i);
        if (t.isEmpty() || t.equals("-")) throw error("unexpected character");
        try {
            return frac ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t);
        } catch (NumberFormatException ex) {
            throw error("bad number");
        }
    }

    // ---- writing ----

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String str) {
            quote(b, str);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            b.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            b.append(Double.isFinite(d) ? String.valueOf(d) : "null");
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof Iterable<?> it) {
            b.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) b.append(',');
                first = false;
                write(b, o);
            }
            b.append(']');
        } else {
            quote(b, v.toString());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    /** Small helpers for reading request arguments. */
    public static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m == null ? null : m.get(key);
        return v == null ? fallback : v.toString();
    }

    public static long num(Map<String, Object> m, String key, long fallback) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? n.longValue() : fallback;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }
}
