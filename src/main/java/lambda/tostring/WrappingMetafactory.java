package lambda.tostring;

import java.lang.invoke.*;

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

        return java.lang.reflect.Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                new Class<?>[]{ ifaceType },
                (proxy, method, args) -> {
                    // toString: return the pre-built label
                    if (method.getName().equals("toString") && (args == null || args.length == 0))
                        return label;

                    // Route Object-declared methods through proxy identity so that
                    // proxy.equals(proxy) == true and collections work correctly.
                    // Note: check *both* the declaring class AND the method name, because
                    // some interfaces re-declare Object methods (e.g. Comparator.equals) —
                    // those re-declarations have declaring class = the interface, not Object,
                    // but we still want proxy identity semantics for them.
                    String name = method.getName();
                    if (method.getDeclaringClass() == Object.class
                            || ((name.equals("equals") || name.equals("hashCode"))
                                && (args == null ? 0 : args.length) <= 1
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
                        return method.invoke(delegate, args);
                    }
                });
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
     * Extracts the bare method name from the impl MethodHandle's string representation.
     * MethodHandle.toString() is not a stable API, so this is best-effort: on failure
     * the name falls back to "lambda".
     *
     * Example: "MethodHandle(Foo)void lambda$main$0()" → "lambda$main$0"
     */
    private static String extractMethodName(MethodHandle implMethod) {
        if (implMethod == null) return "lambda";
        String s    = implMethod.toString();
        int space   = s.lastIndexOf(' ');
        int paren   = s.indexOf('(', Math.max(0, space + 1));
        // Validate that both markers were found and are in the right order
        if (space < 0 || paren < 0 || space + 1 >= paren) return "lambda";
        return s.substring(space + 1, paren);
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
}
