package lambda.tostring;

/**
 * Formats lambda labels from a pattern string.
 *
 * <p>Supported tokens:
 * <table>
 *   <tr><td>{@code %i}</td><td>functional interface simple name (e.g. {@code Runnable})</td></tr>
 *   <tr><td>{@code %c}</td><td>enclosing class fully-qualified name (e.g. {@code com.example.Foo})</td></tr>
 *   <tr><td>{@code %s}</td><td>enclosing class simple name (e.g. {@code Foo})</td></tr>
 *   <tr><td>{@code %m}</td><td>enclosing method name (e.g. {@code bar})</td></tr>
 *   <tr><td>{@code %f}</td><td>source file name (e.g. {@code Foo.java})</td></tr>
 *   <tr><td>{@code %l}</td><td>line number, or {@code ?} if unavailable</td></tr>
 *   <tr><td>{@code %%}</td><td>literal {@code %}</td></tr>
 * </table>
 *
 * <p>Default pattern: {@value #DEFAULT_PATTERN}
 *
 * <p>The pattern is read once at class initialisation from the system property
 * {@value #SYSTEM_PROPERTY}, or from the agent argument string (key {@code format}).
 * See {@link #configure(String)} for the agent-arg wire-up.
 */
public final class LabelFormat {

    public static final String DEFAULT_PATTERN = "Lambda[%i @ %c.%m(%f:%l)]";
    public static final String SYSTEM_PROPERTY = "lambda.tostring.format";

    private static volatile String pattern = System.getProperty(SYSTEM_PROPERTY, DEFAULT_PATTERN);

    private LabelFormat() {}

    /**
     * Called by {@link LambdaToStringAgent} with the raw agent argument string.
     * Recognises {@code format=<pattern>} (supports {@code \\n} / {@code \\t} escapes).
     * Other key=value pairs are silently ignored.
     */
    static void configure(String agentArgs) {
        if (agentArgs == null || agentArgs.isEmpty()) return;
        for (String part : agentArgs.split(",")) {
            if (part.startsWith("format=")) {
                String raw = part.substring("format=".length());
                pattern = unescape(raw);
                return;
            }
        }
    }

    /** Returns the currently active pattern. */
    public static String getPattern() { return pattern; }

    /**
     * Formats a label by substituting tokens in the current pattern.
     *
     * @param iface     functional interface simple name
     * @param classFqn  enclosing class fully-qualified name
     * @param method    enclosing method name
     * @param file      source file name
     * @param line      line number (&lt;= 0 means unavailable, renders as {@code ?})
     */
    public static String format(String iface, String classFqn, String method,
                                String file, int line) {
        return format(iface, classFqn, method, file, line, pattern);
    }

    /**
     * Formats a label using an explicit pattern (ignores the configured default).
     * Useful for tests and one-off formatting.
     */
    public static String format(String iface, String classFqn, String method,
                                String file, int line, String pat) {
        StringBuilder sb = new StringBuilder(pat.length() + 64);
        int i = 0;
        int len = pat.length();
        while (i < len) {
            char c = pat.charAt(i);
            if (c == '%' && i + 1 < len) {
                char token = pat.charAt(i + 1);
                switch (token) {
                    case 'i' -> { sb.append(iface);              i += 2; }
                    case 'c' -> { sb.append(classFqn);           i += 2; }
                    case 's' -> { sb.append(simpleOf(classFqn)); i += 2; }
                    case 'm' -> { sb.append(method);             i += 2; }
                    case 'f' -> { sb.append(file);               i += 2; }
                    case 'l' -> {
                        if (line > 0) sb.append(line); else sb.append('?');
                        i += 2;
                    }
                    case '%' -> { sb.append('%'); i += 2; }
                    default  -> { sb.append(c);   i++;   }   // unknown token: emit % literally
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    // --- helpers ---

    private static String simpleOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /** Unescapes {@code \\n} → newline, {@code \\t} → tab, {@code \\\\} → {@code \\}. */
    private static String unescape(String s) {
        if (!s.contains("\\")) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n'  -> { sb.append('\n'); i += 2; }
                    case 't'  -> { sb.append('\t'); i += 2; }
                    case '\\' -> { sb.append('\\'); i += 2; }
                    default   -> { sb.append(c);    i++;    }
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
