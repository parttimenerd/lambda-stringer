package lambda.tostring;

import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

public class LambdaToStringAgent {
    public static void premain(String args, Instrumentation inst) {
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
