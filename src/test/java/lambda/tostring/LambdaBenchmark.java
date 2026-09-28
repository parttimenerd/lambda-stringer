package lambda.tostring;

import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Standalone performance benchmark for the lambda-tostring agent.
 * <p>
 * Run WITHOUT agent to get baseline:
 *   java -cp target/classes:target/test-classes lambda.tostring.LambdaBenchmark
 * <p>
 * Run WITH agent to measure overhead:
 *   java -javaagent:target/lambda-tostring-1.0.jar \
 *        -cp target/classes:target/test-classes lambda.tostring.LambdaBenchmark
 * <p>
 * Output: one row per scenario with ns/op, so the two runs can be compared directly.
 */
public class LambdaBenchmark {

    static final int WARMUP   =  100_000;
    static final int MEASURED = 2_000_000;

    static void main(String[] args) throws Exception {
        System.out.println("lambda-tostring benchmark  (agent active = " + agentActive() + ")");
        System.out.println("warmup=" + WARMUP + "  measured=" + MEASURED);
        System.out.println();
        System.out.printf("%-42s  %10s%n", "scenario", "ns/op");
        System.out.println("-".repeat(56));

        benchNonCapturing();
        benchCapturing();
        benchToString();
        benchInvoke();
        benchInvokeWithToString();
        benchMethodRefStatic();
        benchMethodRefInstance();
        benchConstructorRef();
        benchHighArity();
        benchStreamPipeline();

        System.out.println();
        System.out.println("Note: compare with/without -javaagent to isolate agent overhead.");
    }

    // -----------------------------------------------------------------------
    // Scenarios
    // -----------------------------------------------------------------------

    /** Cost of creating a non-capturing lambda instance. */
    static void benchNonCapturing() {
        Runnable r = () -> {};  // warmup — triggers bootstrap
        for (int i = 0; i < WARMUP; i++) { Runnable x = () -> {}; blackhole(x); }

        long t = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) { Runnable x = () -> {}; blackhole(x); }
        print("create non-capturing Runnable", t);
    }

    /** Cost of creating a capturing (closure) lambda instance. */
    static void benchCapturing() {
        for (int i = 0; i < WARMUP; i++) { int v = i; Supplier<Integer> s = () -> v; blackhole(s); }

        long t = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) { int v = i; Supplier<Integer> s = () -> v; blackhole(s); }
        print("create capturing Supplier<Integer>", t);
    }

    /** Cost of calling toString() on a pre-created lambda. The label is a constant. */
    static void benchToString() {
        Runnable r = () -> {};
        for (int i = 0; i < WARMUP; i++) blackhole(r.toString());

        long t = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) blackhole(r.toString());
        print("toString() on pre-created lambda", t);
    }

    /** Cost of invoking a pre-created non-capturing Runnable. */
    static void benchInvoke() {
        Runnable r = () -> {};
        for (int i = 0; i < WARMUP; i++) r.run();

        long t = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) r.run();
        print("invoke pre-created Runnable", t);
    }

    /** Combined invoke + toString — typical usage pattern in logging/debugging. */
    static void benchInvokeWithToString() {
        Supplier<Integer> s = () -> 42;
        for (int i = 0; i < WARMUP; i++) { s.get(); blackhole(s.toString()); }

        long t = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) { s.get(); blackhole(s.toString()); }
        print("invoke + toString() Supplier", t);
    }

    /** Static method reference — has no separate lambda body. */
    static void benchMethodRefStatic() {
        Function<String, Integer> f = Integer::parseInt;
        for (int i = 0; i < WARMUP; i++) blackhole(f.apply("1"));

        long t = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) blackhole(f.apply("1"));
        print("invoke static method ref Function", t);
    }

    /** Instance method reference. */
    static void benchMethodRefInstance() {
        Function<String, String> f = String::toUpperCase;
        for (int i = 0; i < WARMUP; i++) blackhole(f.apply("hello"));

        long t = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) blackhole(f.apply("hello"));
        print("invoke instance method ref Function", t);
    }

    /** Constructor reference — allocates on each call. */
    static void benchConstructorRef() {
        Function<String, StringBuilder> f = StringBuilder::new;
        for (int i = 0; i < WARMUP; i++) blackhole(f.apply("x"));

        long t = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) blackhole(f.apply("x"));
        print("invoke constructor ref Function", t);
    }

    /** High-arity BiFunction — tests that the proxy dispatch scales with arg count. */
    static void benchHighArity() {
        BiFunction<String, String, String> f = (a, b) -> a + b;
        for (int i = 0; i < WARMUP; i++) blackhole(f.apply("a", "b"));

        long t = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) blackhole(f.apply("a", "b"));
        print("invoke BiFunction (a, b) -> a + b", t);
    }

    /** Stream pipeline — real-world usage; lambdas are created and invoked many times. */
    static void benchStreamPipeline() throws Exception {
        String[] data = new String[100];
        for (int i = 0; i < data.length; i++) data[i] = "item" + i;

        Callable<Integer> task = () -> {
            int sum = 0;
            for (String s : data) if (s.length() > 4) sum += s.length();
            return sum;
        };

        for (int i = 0; i < WARMUP / 10; i++) task.call();

        long t = System.nanoTime();
        for (int i = 0; i < MEASURED / 10; i++) task.call();
        print("stream-like loop via Callable (÷10)", t, MEASURED / 10);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static volatile Object sink;   // prevents dead-code elimination

    static void blackhole(Object o) { sink = o; }
    static void blackhole(int i)    { sink = i; }

    static void print(String label, long startNs) {
        print(label, startNs, MEASURED);
    }

    static void print(String label, long startNs, int iters) {
        double nsPerOp = (double)(System.nanoTime() - startNs) / iters;
        System.out.printf("%-42s  %10.1f%n", label, nsPerOp);
    }

    static boolean agentActive() {
        try {
            Runnable r = () -> {};
            return r.toString().startsWith("Lambda[");
        } catch (Exception e) {
            return false;
        }
    }
}
