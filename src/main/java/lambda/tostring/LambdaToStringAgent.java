package lambda.tostring;

import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

public class LambdaToStringAgent {
    public static void premain(String args, Instrumentation inst) {
        install(args, inst);
    }

    /** Called when attached to a running JVM via VirtualMachine.attach(). */
    public static void agentmain(String args, Instrumentation inst) {
        install(args, inst);
    }

    private static void install(String args, Instrumentation inst) {
        // Propagate format= arg as a system property BEFORE adding the jar to the
        // bootstrap classloader search.  LabelFormat may be loaded by two classloaders
        // (app + bootstrap); the system property is the only shared channel between them.
        LabelFormat.configure(args);
        try {
            var src = LambdaToStringAgent.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(src.getLocation().getPath()));
            }
        } catch (Exception e) {
            // best-effort; if we can't add to bootstrap CP, WrappingMetafactory may fail
            // for classes loaded by non-app classloaders — acceptable degradation
        }
        inst.addTransformer(new LambdaToStringTransformer());
    }
}
