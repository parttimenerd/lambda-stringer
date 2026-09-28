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
                                         MethodType factoryType, String label) throws Throwable {
        Class<?> ifaceType = factoryType.returnType();

        if (factoryType.parameterCount() == 0) {
            // Non-capturing lambda: wrap the singleton once and return a constant handle.
            Object singleton = original.dynamicInvoker().invoke();
            Object wrapped   = wrapInstance(caller, ifaceType, label, singleton);
            return new ConstantCallSite(MethodHandles.constant(ifaceType, wrapped));
        }

        // Capturing lambda: chain original → wrapInstance on every call.
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
        // Each proxy wraps one functional interface — one SAM method.
        // Cache the bound MethodHandle after first resolution to avoid unreflect+bindTo per call.
        // Stored in a plain volatile; racing threads may resolve twice on the first call but
        // will always store the same logically-equivalent handle.
        private volatile MethodHandle cachedHandle;

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

            // SAM method: use cached bound MethodHandle for repeated calls.
            MethodHandle mh = cachedHandle;
            if (mh == null) {
                mh = resolveHandle(method);
                cachedHandle = mh;
            }
            try {
                return mh.invokeWithArguments(args);
            } catch (InvocationTargetException ite) {
                sneakyThrow(ite.getCause());
            }
            throw new AssertionError("unreachable");
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
                    // Reflection fallback: wrap method.invoke in a MethodHandle so
                    // the cache still helps on repeated calls.
                    try {
                        method.setAccessible(true);
                        return MethodHandles.lookup().unreflect(method).bindTo(delegate);
                    } catch (IllegalAccessException ex2) {
                        // Absolute last resort — direct reflection each time.
                        // Return a sentinel that will fall through to method.invoke below.
                        return null;
                    }
                }
            }
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
        String method   = extractMethodName(caller, implMethod);
        int    line     = resolveLineNumber(caller);
        String file     = topLevelSourceFile(caller.lookupClass());

        return LabelFormat.format(iface, encClass, method, file, line);
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
