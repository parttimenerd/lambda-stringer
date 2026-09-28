package lambda.tostring;

import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Rewrites every invokedynamic that targets LambdaMetafactory to target
 * WrappingMetafactory instead, causing each lambda to receive a toString().
 *
 * <p>Because lambda classes are hidden classes (since Java 15), they are never
 * passed to ClassFileTransformer. The workaround is to rewrite the <em>caller</em>:
 * redirect its invokedynamic bootstrap from LambdaMetafactory to our wrapper,
 * which intercepts the call site and wraps the resulting lambda in a Proxy.
 */
public class LambdaToStringTransformer implements ClassFileTransformer {

    private static final String LAMBDA_METAFACTORY_OWNER = "java/lang/invoke/LambdaMetafactory";

    // Descriptors for our two bootstrap replacements — one per LambdaMetafactory entry point.
    private static final DirectMethodHandleDesc BSM_WRAP_META = bsmHandleFor("metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
            "Ljava/lang/invoke/CallSite;");

    private static final DirectMethodHandleDesc BSM_WRAP_ALT = bsmHandleFor("altMetafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;");

    private static DirectMethodHandleDesc bsmHandleFor(String name, String descriptor) {
        return MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                ClassDesc.ofInternalName("lambda/tostring/WrappingMetafactory"),
                name,
                MethodTypeDesc.ofDescriptor(descriptor));
    }

    // --- ClassFileTransformer ---

    /** Java 9+: called for all classes, including those in named modules. */
    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain domain,
                            byte[] classfileBuffer) {
        return rewriteIfNeeded(className, classfileBuffer);
    }

    /** Pre-Java 9 fallback (also called for unnamed-module classes in some JVMs). */
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        return rewriteIfNeeded(className, classfileBuffer);
    }

    // --- Rewriting ---

    private byte[] rewriteIfNeeded(String className, byte[] classfileBuffer) {
        if (shouldSkip(className)) return null;
        try {
            ClassModel cm = ClassFile.of().parse(classfileBuffer);
            if (!hasLambdaMetaIndy(cm)) return null;
            return ClassFile.of().transformClass(cm,
                    ClassTransform.transformingMethods(
                            MethodTransform.transformingCode(this::rewriteIndy)));
        } catch (Exception e) {
            return null; // never break class loading
        }
    }

    /**
     * Skips classes that must not be rewritten:
     * - Our own agent classes (avoid recursion when WrappingMetafactory is loaded)
     * - JDK internals (they reference Proxy/AnnotationParser before WrappingMetafactory is
     *   resolvable on the bootstrap classpath, causing BootstrapMethodErrors)
     */
    private static boolean shouldSkip(String className) {
        if (className == null) return true;
        // Skip our three agent classes by exact name to avoid self-referential recursion.
        // Note: do NOT skip the whole package — test/user code lives there too.
        if (className.equals("lambda/tostring/LambdaToStringAgent")
                || className.equals("lambda/tostring/LambdaToStringTransformer")
                || className.equals("lambda/tostring/LabelFormat")
                || className.equals("lambda/tostring/WrappingMetafactory")) return true;
        return className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("org/xml/");
    }

    /** Returns true if any method in the class has an invokedynamic targeting LambdaMetafactory. */
    private static boolean hasLambdaMetaIndy(ClassModel cm) {
        for (var method : cm.methods()) {
            var code = method.findAttribute(Attributes.code()).orElse(null);
            if (code == null) continue;
            for (var element : code) {
                if (element instanceof InvokeDynamicInstruction indy && isLambdaMeta(indy))
                    return true;
            }
        }
        return false;
    }

    /** Replaces a LambdaMetafactory invokedynamic with our WrappingMetafactory equivalent. */
    private void rewriteIndy(CodeBuilder cb, CodeElement el) {
        if (!(el instanceof InvokeDynamicInstruction indy) || !isLambdaMeta(indy)) {
            cb.with(el);
            return;
        }
        String bsmName = indy.invokedynamic().bootstrap().bootstrapMethod()
                .reference().name().stringValue();
        DirectMethodHandleDesc replacement = bsmName.equals("altMetafactory")
                ? BSM_WRAP_ALT : BSM_WRAP_META;
        cb.invokedynamic(DynamicCallSiteDesc.of(
                replacement,
                indy.name().stringValue(),
                indy.typeSymbol(),
                indy.bootstrapArgs().toArray(ConstantDesc[]::new)));
    }

    private static boolean isLambdaMeta(InvokeDynamicInstruction indy) {
        return indy.invokedynamic().bootstrap().bootstrapMethod()
                .reference().owner().asInternalName()
                .equals(LAMBDA_METAFACTORY_OWNER);
    }
}
