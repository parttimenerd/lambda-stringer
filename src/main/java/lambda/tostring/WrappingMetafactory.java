package lambda.tostring;

import java.lang.invoke.*;
import java.lang.reflect.*;

/**
 * Bootstrap method replacement for LambdaMetafactory.
 *
 * <p>Delegates to the real LambdaMetafactory to create the lambda, then wraps the
 * returned CallSite so every invocation produces a Proxy that overrides toString()
 * with a human-readable location string.
 *
 * <p>The label format is controlled by {@link LabelFormat}; the default is:
 * {@code Lambda[Runnable @ com.example.Foo.bar(Foo.java:42)]}
 *
 * <p>Wiring: LambdaToStringTransformer rewrites invokedynamic bootstrap references
 * in caller classes from LambdaMetafactory → this class at load time.
 */
public class WrappingMetafactory {

    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    // MethodHandle for wrapInstance — looked up once and reused across all bootstraps.
    private static final MethodHandle WRAP_INSTANCE_MH;
    static {
        try {
            WRAP_INSTANCE_MH = MethodHandles.lookup().findStatic(
                    WrappingMetafactory.class, "wrapInstance",
                    MethodType.methodType(Object.class, MethodHandles.Lookup.class,
                            Class.class, String.class, String.class,
                            String.class, String.class, String.class, int.class,
                            Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // --- Bootstrap entry points (called by the JVM via rewritten invokedynamic) ---

    public static CallSite metafactory(
            MethodHandles.Lookup caller,
            String interfaceMethodName,
            MethodType factoryType,
            MethodType samMethodType,
            MethodHandle implMethod,
            MethodType instantiatedMethodType) throws Throwable {

        CallSite original = LambdaMetafactory.metafactory(
                caller, interfaceMethodName, factoryType,
                samMethodType, implMethod, instantiatedMethodType);

        LabelInfo info = buildLabelInfo(caller, factoryType, implMethod);
        return wrapCallSite(original, caller, factoryType, info);
    }

    public static CallSite altMetafactory(
            MethodHandles.Lookup caller,
            String interfaceMethodName,
            MethodType factoryType,
            Object... args) throws Throwable {

        CallSite original = LambdaMetafactory.altMetafactory(
                caller, interfaceMethodName, factoryType, args);

        LabelInfo info = buildLabelInfo(caller, factoryType, null);
        return wrapCallSite(original, caller, factoryType, info);
    }

    // --- Call site wrapping ---

    /**
     * Returns a new ConstantCallSite whose target wraps every produced lambda in a Proxy.
     *
     * <p>For non-capturing lambdas (factoryType has no parameters) the original call site
     * returns the same singleton on every call. We wrap that singleton once at bootstrap
     * time and install a constant handle — preserving the JVM's identity guarantee and
     * avoiding per-call Proxy allocation.
     *
     * <p>For capturing lambdas (factoryType has parameters) we chain: original → wrapInstance,
     * widening the return type to Object for filterReturnValue then narrowing back.
     */
    private static CallSite wrapCallSite(CallSite original, MethodHandles.Lookup caller,
                                         MethodType factoryType, LabelInfo info) throws Throwable {
        Class<?> ifaceType = factoryType.returnType();

        if (factoryType.parameterCount() == 0) {
            // Non-capturing lambda: wrap the singleton once and return a constant handle.
            Object singleton = original.dynamicInvoker().invoke();
            Object wrapped   = wrapInstance(caller, ifaceType,
                    info.label, info.iface, info.encClass, info.encMethod, info.file, info.line, singleton);
            return new ConstantCallSite(MethodHandles.constant(ifaceType, wrapped));
        }

        // Capturing lambda: chain original → wrapInstance on every call.
        MethodHandle filter = MethodHandles.insertArguments(
                WRAP_INSTANCE_MH, 0, caller, ifaceType,
                info.label, info.iface, info.encClass, info.encMethod, info.file, info.line);
        MethodHandle target = original.dynamicInvoker()
                .asType(original.dynamicInvoker().type().changeReturnType(Object.class));
        MethodHandle combined = MethodHandles.filterReturnValue(target, filter)
                .asType(factoryType);
        return new ConstantCallSite(combined);
    }

    /**
     * Wraps a lambda instance in a Proxy that delegates everything except toString(),
     * which returns the pre-built label.
     *
     * <p>Uses the caller's Lookup (rather than our own) for method access, so that
     * package-private and module-private interfaces are accessible.
     */
    static Object wrapInstance(MethodHandles.Lookup callerLookup, Class<?> ifaceType,
                                String label, String ifaceName,
                                String creationClass, String creationMethod,
                                String creationFile, int creationLine,
                                Object delegate) {
        if (delegate == null) return null;

        // Don't wrap Serializable lambdas: the Proxy's InvocationHandler is not Serializable,
        // which would break ObjectOutputStream. Correctness > toString label.
        if (delegate instanceof java.io.Serializable) return delegate;

        return Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                new Class<?>[]{ ifaceType },
                new LambdaHandler(callerLookup, label, ifaceName,
                        creationClass, creationMethod, creationFile, creationLine,
                        delegate));
    }

    /**
     * InvocationHandler that delegates all calls to the real lambda except toString().
     *
     * <p>Named class (not a lambda) to avoid inserting a {@code WrappingMetafactory$$Lambda}
     * frame into stack traces every time a proxy method is invoked.
     */
    static final class LambdaHandler implements InvocationHandler {

        private final MethodHandles.Lookup callerLookup;
        private final String               label;
        private final String               ifaceName;
        private final String               creationClass;
        private final String               creationMethod;
        private final String               creationFile;
        private final int                  creationLine;
        private final Object               delegate;
        // Each proxy wraps one functional interface — one SAM method.
        // Cache the bound MethodHandle after first resolution to avoid unreflect+bindTo per call.
        // Stored in a plain volatile; racing threads may resolve twice on the first call but
        // will always store the same logically-equivalent handle.
        private volatile MethodHandle cachedHandle;

        LambdaHandler(MethodHandles.Lookup callerLookup, String label, String ifaceName,
                      String creationClass, String creationMethod,
                      String creationFile, int creationLine,
                      Object delegate) {
            this.callerLookup   = callerLookup;
            this.label          = label;
            this.ifaceName      = ifaceName;
            this.creationClass  = creationClass;
            this.creationMethod = creationMethod;
            this.creationFile   = creationFile;
            this.creationLine   = creationLine;
            this.delegate       = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // toString: return the pre-built label.
            if (method.getName().equals("toString") && (args == null || args.length == 0))
                return label;

            // Route Object-declared methods through proxy identity so that
            // proxy.equals(proxy) == true and collections work correctly.
            // Also covers interfaces that re-declare Object methods (e.g. Comparator.equals):
            // those have declaring class = interface, but we still want identity semantics.
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class
                    || ((name.equals("equals") || name.equals("hashCode"))
                        && args != null && args.length <= 1
                        && method.getDeclaringClass().isInterface())) {
                return switch (name) {
                    case "toString" -> label;
                    case "equals"   -> proxy == (args != null ? args[0] : null);
                    case "hashCode" -> System.identityHashCode(proxy);
                    default         -> method.invoke(delegate, args);
                };
            }

            // SAM method: use cached bound MethodHandle for repeated calls.
            MethodHandle mh = cachedHandle;
            if (mh == null) {
                mh = resolveHandle(method);
                cachedHandle = mh;
            }
            try {
                return mh.invokeWithArguments(args);
            } catch (InvocationTargetException ite) {
                sneakyThrow(annotateAndStrip(ite.getCause()));
            } catch (Throwable t) {
                sneakyThrow(annotateAndStrip(t));
            }
            throw new AssertionError("unreachable");
        }

        /**
         * Strips agent-internal frames from {@code t}'s stack trace and injects a
         * synthetic "via λ created in" frame immediately after the executor frame
         * (the first non-agent frame after the last agent frame — i.e. the code
         * that called the lambda).
         *
         * <p>The result looks like:
         * <pre>
         *   at Integer.parseInt(Integer.java:662)                ← what ran inside the lambda
         *   at Parser.parse(Executor.java:7)                     ← who called the lambda
         *   at // ^ via λ created in Foo.main(Foo.java:13)       ← injected: where lambda came from
         *   at Foo.main(Foo.java:14)                             ← broader context
         * </pre>
         *
         * <p>Placing the annotation after the executor frame makes it easy to see both
         * which code called the lambda (the frame above) and where the lambda was
         * defined (the annotation itself, with a clickable file/line).
         *
         * <p>The mutation is on the throwable object itself, so the clean trace is
         * visible regardless of who prints it — caught handler, uncaught handler, logger, IDE.
         */
        private Throwable annotateAndStrip(Throwable t) {
            if (t == null) return null;
            StackTraceElement[] frames = t.getStackTrace();

            // Find the proxy call boundary: the last agent frame.
            // The first non-agent frame after it is the executor (the caller of the lambda).
            // We insert the annotation AFTER that executor frame.
            int lastAgentIdx = -1;
            for (int i = 0; i < frames.length; i++) {
                if (isAgentFrame(frames[i])) lastAgentIdx = i;
            }

            // insertAfter = index in original frames[] of the executor frame.
            int insertAfter = -1;
            if (lastAgentIdx >= 0) {
                for (int i = lastAgentIdx + 1; i < frames.length; i++) {
                    if (!isAgentFrame(frames[i])) { insertAfter = i; break; }
                }
            }

            int keepCount = 0;
            for (StackTraceElement f : frames) {
                if (!isAgentFrame(f)) keepCount++;
            }
            boolean inject = insertAfter >= 0;
            StackTraceElement[] result = new StackTraceElement[keepCount + (inject ? 1 : 0)];
            int out = 0;
            boolean injected = false;
            for (int i = 0; i < frames.length; i++) {
                if (isAgentFrame(frames[i])) continue;
                result[out++] = frames[i];
                if (!injected && inject && i == insertAfter) {
                    result[out++] = creationFrame();
                    injected = true;
                }
            }
            t.setStackTrace(result);
            return t;
        }

        /**
         * Builds the synthetic annotation StackTraceElement.
         *
         * <p>Format: {@code "// ^ via λ created in EnclosingClass.method(File.java:line)"}
         * The {@code ^} arrow points to the executor frame above it.
         * The real file/line are preserved so IDEs can navigate to the creation site on click.
         */
        private StackTraceElement creationFrame() {
            return new StackTraceElement(
                    "// ^ via " + ifaceName + " λ created in " + creationClass,
                    creationMethod,
                    creationFile,
                    creationLine);
        }

        private static boolean isAgentFrame(StackTraceElement f) {
            String cls = f.getClassName();
            return cls.startsWith("lambda.tostring.")
                || cls.startsWith("jdk.proxy")
                || cls.contains("$Proxy")
                || (cls.equals("java.lang.invoke.MethodHandle")
                    && "invokeWithArguments".equals(f.getMethodName()));
        }

        private MethodHandle resolveHandle(Method method) {
            try {
                return callerLookup.unreflect(method).bindTo(delegate);
            } catch (IllegalAccessException e) {
                try {
                    return MethodHandles.privateLookupIn(
                            method.getDeclaringClass(), callerLookup)
                        .unreflect(method).bindTo(delegate);
                } catch (Exception ex) {
                    try {
                        method.setAccessible(true);
                        return MethodHandles.lookup().unreflect(method).bindTo(delegate);
                    } catch (IllegalAccessException ex2) {
                        return null;
                    }
                }
            }
        }
    }

    // --- Label construction ---

    /** All label components needed by both the label string and the stack-trace annotation. */
    private record LabelInfo(String label, String iface, String encClass, String encMethod, String file, int line) {}

    private static LabelInfo buildLabelInfo(MethodHandles.Lookup caller, MethodType factoryType,
                                            MethodHandle implMethod) {
        String iface      = factoryType.returnType().getSimpleName();
        String encClass   = caller.lookupClass().getName();
        String implName   = extractMethodName(caller, implMethod);
        String encMethod  = resolveEnclosingMethod(caller);   // actual enclosing method for creation frame
        int    line       = resolveLineNumber(caller);
        String file       = topLevelSourceFile(caller.lookupClass());
        String label      = LabelFormat.format(iface, encClass, implName, file, line);
        return new LabelInfo(label, iface, encClass, encMethod, file, line);
    }

    /**
     * Extracts a human-readable method name for the {@code %m} token.
     *
     * <p>Uses the caller's Lookup (which has full access to the caller class) to call
     * {@link MethodHandles.Lookup#revealDirect} on the impl handle, giving a stable name
     * for both lambda bodies and method references.
     *
     * <ul>
     *   <li>Lambda body  {@code () -> {}}     → synthetic name {@code lambda$foo$0}
     *       → strips prefix/suffix to give the enclosing method name: {@code foo}
     *   <li>Static ref   {@code Foo::bar}     → {@code bar}
     *   <li>Instance ref {@code s::length}    → {@code length}
     *   <li>Constructor ref {@code Foo::new}  → {@code new}
     * </ul>
     */
    private static String extractMethodName(MethodHandles.Lookup callerLookup,
                                            MethodHandle implMethod) {
        if (implMethod == null) return "lambda";
        try {
            MethodHandleInfo info = callerLookup.revealDirect(implMethod);
            String name = info.getName();
            if ("<init>".equals(name)) return "new";
            // Lambda body synthetic names look like "lambda$enclosingMethod$N".
            // Extract the enclosing method name from between the first and last '$'.
            if (name.startsWith("lambda$")) {
                int first = name.indexOf('$') + 1;
                int last  = name.lastIndexOf('$');
                if (last > first) return name.substring(first, last);
            }
            return name;
        } catch (IllegalArgumentException ignored) {
            // Not a direct handle (indirect/adapter) — no name available.
        }
        return "lambda";
    }

    /**
     * Walks the call stack to find the line number of the first frame whose declaring
     * class matches the lambda's enclosing class.
     * Returns -1 if unavailable.
     */
    private static int resolveLineNumber(MethodHandles.Lookup caller) {
        try {
            return STACK_WALKER.walk(frames -> frames
                    .filter(f -> f.getDeclaringClass() == caller.lookupClass())
                    .mapToInt(StackWalker.StackFrame::getLineNumber)
                    .filter(n -> n > 0)
                    .findFirst()
                    .orElse(-1));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Walks the call stack to find the method name of the first frame whose declaring
     * class matches the lambda's enclosing class (the method that contains the lambda
     * expression or method reference).
     */
    private static String resolveEnclosingMethod(MethodHandles.Lookup caller) {
        try {
            return STACK_WALKER.walk(frames -> frames
                    .filter(f -> f.getDeclaringClass() == caller.lookupClass())
                    .map(StackWalker.StackFrame::getMethodName)
                    .findFirst()
                    .orElse("lambda"));
        } catch (Exception e) {
            return "lambda";
        }
    }

    /** Returns the source file name for the outermost enclosing class (e.g. "Foo.java"). */
    private static String topLevelSourceFile(Class<?> c) {
        while (c.getEnclosingClass() != null) c = c.getEnclosingClass();
        return c.getSimpleName() + ".java";
    }

    /**
     * Rethrows {@code t} without declaring it, bypassing the compiler's checked-exception
     * rules. Used in the InvocationHandler fallback so that undeclared checked exceptions
     * thrown by the delegate propagate with their original type.
     */
    @SuppressWarnings("unchecked")
    static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
