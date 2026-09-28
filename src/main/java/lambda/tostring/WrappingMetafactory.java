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
                            Class.class, String.class, Object.class));
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

        return wrapCallSite(original, caller, factoryType,
                buildLabel(caller, factoryType, implMethod));
    }

    public static CallSite altMetafactory(
            MethodHandles.Lookup caller,
            String interfaceMethodName,
            MethodType factoryType,
            Object... args) throws Throwable {

        CallSite original = LambdaMetafactory.altMetafactory(
                caller, interfaceMethodName, factoryType, args);

        return wrapCallSite(original, caller, factoryType,
                buildLabel(caller, factoryType, null));
    }

    // --- Call site wrapping ---

    /**
     * Returns a new ConstantCallSite whose target chains: original → wrapInstance.
     * The original handle's return type is widened to Object so filterReturnValue
     * type-checks, then narrowed back to the declared interface type.
     */
    private static CallSite wrapCallSite(CallSite original, MethodHandles.Lookup caller,
                                         MethodType factoryType, String label) throws Throwable {
        Class<?> ifaceType = factoryType.returnType();
        MethodHandle filter = MethodHandles.insertArguments(
                WRAP_INSTANCE_MH, 0, caller, ifaceType, label);

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
                                String label, Object delegate) {
        if (delegate == null) return null;

        // Don't wrap Serializable lambdas: the Proxy's InvocationHandler is not Serializable,
        // which would break ObjectOutputStream. Correctness > toString label.
        if (delegate instanceof java.io.Serializable) return delegate;

        return Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                new Class<?>[]{ ifaceType },
                new LambdaHandler(callerLookup, label, delegate));
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
        private final Object               delegate;

        LambdaHandler(MethodHandles.Lookup callerLookup, String label, Object delegate) {
            this.callerLookup = callerLookup;
            this.label        = label;
            this.delegate     = delegate;
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

            // All other methods: delegate via caller's lookup so package-private
            // interfaces (inaccessible to WrappingMetafactory) are reachable.
            try {
                return callerLookup.unreflect(method).bindTo(delegate).invokeWithArguments(args);
            } catch (IllegalAccessException e) {
                // Fallback: direct reflection. Unwrap InvocationTargetException so the
                // original cause propagates rather than being wrapped again.
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException ite) {
                    sneakyThrow(ite.getCause());
                }
            }
            throw new AssertionError("unreachable");
        }
    }

    // --- Label construction ---

    /**
     * Builds the label using the current {@link LabelFormat} pattern.
     * Called once per bootstrap invocation; the result is baked into the CallSite.
     */
    private static String buildLabel(MethodHandles.Lookup caller, MethodType factoryType,
                                     MethodHandle implMethod) {
        String iface    = factoryType.returnType().getSimpleName();
        String encClass = caller.lookupClass().getName();
        String method   = extractMethodName(implMethod);
        int    line     = resolveLineNumber(caller);
        String file     = topLevelSourceFile(caller.lookupClass());

        return LabelFormat.format(iface, encClass, method, file, line);
    }

    /**
     * Extracts the method name from the impl MethodHandle using {@link MethodHandles.Lookup#revealDirect}.
     * Falls back to parsing {@code toString()} for indirect handles, then to {@code "lambda"}.
     *
     * <p>Examples:
     * <ul>
     *   <li>Lambda body {@code () -> {}}   → {@code "lambda$main$0"}
     *   <li>Static ref  {@code Foo::bar}   → {@code "bar"}
     *   <li>Instance ref {@code s::length} → {@code "length"}
     *   <li>Constructor ref {@code Foo::new} → {@code "new"}
     * </ul>
     */
    private static String extractMethodName(MethodHandle implMethod) {
        if (implMethod == null) return "lambda";
        // Preferred: stable API that works for both lambda bodies and method references.
        try {
            MethodHandleInfo info = MethodHandles.lookup().revealDirect(implMethod);
            String name = info.getName();
            return "<init>".equals(name) ? "new" : name;
        } catch (IllegalArgumentException ignored) {
            // Not a direct handle (e.g. adapter or bound handle) — fall through
        }
        // Fallback: parse "MethodHandle(...)ReturnType methodName()" from toString().
        // This format was observed on some JVM versions for lambda bodies.
        String s    = implMethod.toString();
        int space   = s.lastIndexOf(' ');
        int paren   = s.indexOf('(', Math.max(0, space + 1));
        if (space >= 0 && paren >= 0 && space + 1 < paren)
            return s.substring(space + 1, paren);
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
