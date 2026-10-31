package dev.mapbundlecollector.mapart.cache;

// Minimal hand-rolled JSON writing/parsing shared by the cache package. No external deps.
final class Json {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Json() {}

    // Appends s as a quoted JSON string, escaping backslash, quote and control chars.
    static void escape(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u00");
                        sb.append(HEX[(c >> 4) & 0xF]);
                        sb.append(HEX[c & 0xF]);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    static final class JsonException extends RuntimeException {
        JsonException(String message) {
            super(message);
        }
    }

    // Low-level pull parser: caller drives structure, unknown values via skipValue().
    static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        boolean hasMore() {
            ws();
            return pos < s.length();
        }

        char peek() {
            if (pos >= s.length()) throw new JsonException("unexpected end of input");
            return s.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char expected) {
            ws();
            char c = next();
            if (c != expected) throw new JsonException("expected '" + expected + "' but got '" + c + "'");
        }

        // Consumes a null literal if present.
        boolean isNull() {
            ws();
            if (s.startsWith("null", pos)) {
                pos += 4;
                return true;
            }
            return false;
        }

        String string() {
            ws();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw new JsonException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) throw new JsonException("unterminated escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw new JsonException("truncated unicode escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException error) {
                            throw new JsonException("bad unicode escape");
                        }
                        pos += 4;
                    }
                    default -> throw new JsonException("bad escape '\\" + e + "'");
                }
            }
        }

        long number() {
            ws();
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean fractional = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    fractional = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) throw new JsonException("expected number");
            String token = s.substring(start, pos);
            return fractional ? (long) Double.parseDouble(token) : Long.parseLong(token);
        }

        // Skips any value (object, array, string, number, literal). Used for unknown fields.
        void skipValue() {
            ws();
            char c = peek();
            if (c == '"') {
                string();
                return;
            }
            if (c == '{' || c == '[') {
                skipBracketed();
                return;
            }
            if (isNull()) return;
            if (s.startsWith("true", pos)) {
                pos += 4;
                return;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return;
            }
            number();
        }

        private void skipBracketed() {
            int depth = 0;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '"') {
                    string();
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                    pos++;
                    if (depth == 0) return;
                    continue;
                }
                pos++;
            }
            throw new JsonException("unterminated value");
        }
    }
}
