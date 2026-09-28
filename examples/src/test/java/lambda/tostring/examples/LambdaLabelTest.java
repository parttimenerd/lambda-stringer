package lambda.tostring.examples;

import org.junit.Test;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Tests that verify lambda-stringer labels are present when the agent is attached.
 *
 * The Surefire configuration in pom.xml attaches the agent automatically:
 *   <argLine>-javaagent:${agent.jar}</argLine>
 */
public class LambdaLabelTest {

    // -----------------------------------------------------------------------
    // toString() label tests
    // -----------------------------------------------------------------------

    @Test
    public void runnableLambdaHasReadableLabel() {
        Runnable task = () -> { /* no-op */ };
        String label = task.toString();
        assertTrue("expected Lambda[...] label, got: " + label,
                label.startsWith("Lambda["));
        assertTrue("label should mention Runnable", label.contains("Runnable"));
        assertTrue("label should reference this class", label.contains("LambdaLabelTest"));
    }

    @Test
    public void supplierLambdaHasReadableLabel() {
        Supplier<String> s = () -> "hello";
        String label = s.toString();
        assertTrue("expected Lambda[...] label, got: " + label,
                label.startsWith("Lambda["));
        assertTrue("label should mention Supplier", label.contains("Supplier"));
    }

    @Test
    public void methodReferenceHasReadableLabel() {
        Function<String, Integer> parser = Integer::parseInt;
        String label = parser.toString();
        assertTrue("expected Lambda[...] label, got: " + label,
                label.startsWith("Lambda["));
        assertTrue("label should mention Function", label.contains("Function"));
    }

    @Test
    public void consumerMethodReferenceHasReadableLabel() {
        Consumer<String> printer = System.out::println;
        String label = printer.toString();
        assertTrue("expected Lambda[...] label, got: " + label,
                label.startsWith("Lambda["));
    }

    @Test
    public void labelContainsLineNumber() {
        Runnable task = () -> { /* no-op */ };
        String label = task.toString();
        // Label format: Lambda[Runnable @ pkg.Class.method(File.java:LINE)]
        assertTrue("label should contain a line number, got: " + label,
                label.matches(".*:\\d+\\)\\]"));
    }

    @Test
    public void labelContainsSourceFile() {
        Runnable task = () -> { /* no-op */ };
        String label = task.toString();
        assertTrue("label should reference .java source file, got: " + label,
                label.contains(".java:"));
    }

    // -----------------------------------------------------------------------
    // Non-capturing singleton identity
    // -----------------------------------------------------------------------

    @Test
    public void nonCapturingLambdaIsSingleton() {
        Runnable a = getNonCapturing();
        Runnable b = getNonCapturing();
        assertSame("non-capturing lambda wrapper must be a singleton", a, b);
    }

    private static Runnable getNonCapturing() {
        return () -> { /* non-capturing */ };
    }

    // -----------------------------------------------------------------------
    // Stack-trace annotation
    // -----------------------------------------------------------------------

    @Test
    public void exceptionHasNoAgentFrames() {
        Runnable task = () -> { throw new RuntimeException("boom"); };
        try {
            task.run();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            for (StackTraceElement frame : e.getStackTrace()) {
                String cls = frame.getClassName();
                assertFalse("agent internals should be stripped: " + cls,
                        cls.contains("LambdaHandler")
                        || cls.contains("$Proxy")
                        || (cls.contains("MethodHandle") && cls.contains("invokeWithArguments")));
            }
            // Annotation frame should be present
            boolean hasAnnotation = false;
            for (StackTraceElement frame : e.getStackTrace()) {
                if (frame.getClassName().startsWith("// ^ via ")) {
                    hasAnnotation = true;
                    break;
                }
            }
            assertTrue("stack trace should have a '// ^ via' annotation frame", hasAnnotation);
        }
    }

    // -----------------------------------------------------------------------
    // Custom format via system property
    // -----------------------------------------------------------------------

    @Test
    public void defaultFormatMatchesPattern() {
        Runnable task = () -> { /* no-op */ };
        // Default: Lambda[%i @ %c.%m(%f:%l)]
        assertTrue("default format should match Lambda[<iface> @ <class>.<method>(<file>:<line>)]",
                task.toString().matches("Lambda\\[\\w+ @ [\\w.$]+\\.\\w+\\([^:]+\\.java:\\d+\\)\\]"));
    }

    // -----------------------------------------------------------------------
    // Collection of lambdas
    // -----------------------------------------------------------------------

    @Test
    public void collectionOfLambdasAllHaveLabels() {
        List<Runnable> tasks = List.of(
                () -> { /* task A */ },
                () -> { /* task B */ },
                () -> { /* task C */ }
        );
        for (Runnable task : tasks) {
            String label = task.toString();
            assertTrue("each lambda in a collection should have a Lambda[...] label, got: " + label,
                    label.startsWith("Lambda["));
            assertTrue("label should reference this test class, got: " + label,
                    label.contains("LambdaLabelTest"));
        }
    }
}
