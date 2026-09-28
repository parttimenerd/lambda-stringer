package lambda.tostring;

import org.junit.Test;

import java.io.*;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Correctness tests for the lambda-tostring agent.
 * For performance numbers, run {@link LambdaBenchmark} directly.
 */
public class LambdaToStringTest {

    // Every agent-produced label must match this pattern.
    private static final Pattern LABEL = Pattern.compile(
            "^Lambda\\[\\S+ @ [\\w.$]+\\.[\\w$]+\\([\\w$.]+\\.java(:\\d+)?\\)\\]$");

    private static void assertLabel(String got) {
        assertNotNull("toString() returned null", got);
        assertTrue("label does not match expected pattern — got: " + got,
                LABEL.matcher(got).matches());
    }

    // -----------------------------------------------------------------------
    // Functional interface coverage
    // -----------------------------------------------------------------------

    @Test public void runnable()       { assertLabel(((Runnable)             () -> {}).toString()); }
    @Test public void supplier()       { assertLabel(((Supplier<String>)     () -> "x").toString()); }
    @Test public void consumer()       { assertLabel(((Consumer<String>)     s -> {}).toString()); }
    @Test public void biConsumer()     { assertLabel(((BiConsumer<String, String>) (a, b) -> {}).toString()); }
    @Test public void function()       { assertLabel(((Function<String, Integer>) String::length).toString()); }
    @Test public void biFunction()     { assertLabel(((BiFunction<String, String, String>) (a, b) -> a + b).toString()); }
    @Test public void predicate()      { assertLabel(((Predicate<String>)    String::isEmpty).toString()); }
    @Test public void biPredicate()    { assertLabel(((BiPredicate<String, String>) String::equals).toString()); }
    @Test public void comparator()     { assertLabel(((Comparator<String>)   (a, b) -> a.compareTo(b)).toString()); }
    @Test public void callable()       throws Exception { assertLabel(((Callable<String>) () -> "y").toString()); }
    @Test public void intSupplier()    { assertLabel(((IntSupplier)          () -> 1).toString()); }
    @Test public void longSupplier()   { assertLabel(((LongSupplier)         () -> 1L).toString()); }
    @Test public void doubleSupplier() { assertLabel(((DoubleSupplier)       () -> 1.0).toString()); }
    @Test public void intUnary()       { assertLabel(((IntUnaryOperator)     x -> x + 1).toString()); }
    @Test public void intBinary()      { assertLabel(((IntBinaryOperator)    (a, b) -> a + b).toString()); }

    // -----------------------------------------------------------------------
    // Label content
    // -----------------------------------------------------------------------

    @Test public void labelContainsEnclosingClass() {
        String s = ((Runnable) () -> {}).toString();
        assertTrue("expected enclosing class name in label, got: " + s,
                s.contains("LambdaToStringTest"));
    }

    @Test public void labelContainsFunctionalInterface() {
        String s = ((Supplier<String>) () -> "z").toString();
        assertTrue("expected 'Supplier' in label, got: " + s, s.contains("Supplier"));
    }

    @Test public void labelContainsLineNumber() {
        String s = ((Runnable) () -> {}).toString();
        assertTrue("expected ':NNN' line number in label, got: " + s,
                Pattern.compile(":\\d+").matcher(s).find());
    }

    @Test public void labelContainsSourceFileName() {
        String s = ((Runnable) () -> {}).toString();
        assertTrue("expected .java source file in label, got: " + s,
                s.contains("LambdaToStringTest.java"));
    }

    @Test public void labelIsHumanReadable() {
        String s = ((Runnable) () -> {}).toString();
        // Must not contain raw hex addresses like 0x... or @<hashcode>
        assertFalse("label should not contain hex addresses, got: " + s,
                s.matches(".*0x[0-9a-fA-F]+.*"));
        assertFalse("label should not contain hash codes, got: " + s,
                s.matches(".*@[0-9a-f]{6,}.*"));
    }

    // -----------------------------------------------------------------------
    // Capturing lambdas
    // -----------------------------------------------------------------------

    @Test public void capturingLambdaHasLabel() {
        String captured = "hello";
        Supplier<String> s = () -> captured.toUpperCase();
        assertLabel(s.toString());
    }

    @Test public void capturingLambdaWorksCorrectly() {
        String captured = "hello";
        Supplier<String> s = () -> captured.toUpperCase();
        assertEquals("HELLO", s.get());
    }

    @Test public void multiCaptureLambdaWorks() {
        int x = 3, y = 7;
        IntSupplier sum = () -> x + y;
        assertEquals(10, sum.getAsInt());
        assertLabel(sum.toString());
    }

    @Test public void capturesMutableContainer() {
        // Lambdas often capture arrays or AtomicXxx as a mutable box
        int[] counter = {0};
        Runnable inc = () -> counter[0]++;
        inc.run();
        inc.run();
        assertEquals(2, counter[0]);
        assertLabel(inc.toString());
    }

    @Test public void capturesAtomicInteger() {
        AtomicInteger n = new AtomicInteger();
        Runnable inc = n::incrementAndGet;
        inc.run();
        assertEquals(1, n.get());
        assertLabel(inc.toString());
    }

    // -----------------------------------------------------------------------
    // Method references
    // -----------------------------------------------------------------------

    @Test public void staticMethodReference() {
        Function<String, Integer> f = Integer::parseInt;
        assertLabel(f.toString());
        assertEquals(42, (int) f.apply("42"));
    }

    @Test public void instanceMethodReferenceUnbound() {
        Function<String, String> f = String::toUpperCase;
        assertLabel(f.toString());
        assertEquals("HELLO", f.apply("hello"));
    }

    @Test public void instanceMethodReferenceBound() {
        String s = "hello";
        Supplier<String> f = s::toUpperCase;
        assertLabel(f.toString());
        assertEquals("HELLO", f.get());
    }

    @Test public void constructorReference() {
        Function<String, StringBuilder> f = StringBuilder::new;
        assertLabel(f.toString());
        assertEquals("hi", f.apply("hi").toString());
    }

    // -----------------------------------------------------------------------
    // Behavioural correctness — wrapping must not alter semantics
    // -----------------------------------------------------------------------

    @Test public void runnableRuns() {
        int[] counter = {0};
        Runnable r = () -> counter[0]++;
        r.run();
        assertEquals(1, counter[0]);
    }

    @Test public void supplierReturnsCorrectValue() {
        Supplier<Integer> s = () -> 42;
        assertEquals(42, (int) s.get());
    }

    @Test public void comparatorComparesCorrectly() {
        Comparator<Integer> cmp = (a, b) -> b - a;   // reverse order
        assertTrue(cmp.compare(1, 2) > 0);
        assertTrue(cmp.compare(2, 1) < 0);
        assertEquals(0, cmp.compare(1, 1));
    }

    @Test public void predicateFiltersCorrectly() {
        Predicate<String> p = s -> s.length() > 3;
        assertTrue(p.test("hello"));
        assertFalse(p.test("hi"));
    }

    @Test public void functionTransformsCorrectly() {
        Function<Integer, Integer> square = x -> x * x;
        assertEquals(25, (int) square.apply(5));
    }

    @Test public void chainedFunctionsWork() {
        Function<String, String> trim   = String::trim;
        Function<String, String> upper  = String::toUpperCase;
        Function<String, String> chain  = trim.andThen(upper);
        assertEquals("HELLO", chain.apply("  hello  "));
    }

    @Test public void composedPredicatesWork() {
        Predicate<Integer> pos  = n -> n > 0;
        Predicate<Integer> even = n -> n % 2 == 0;
        Predicate<Integer> posEven = pos.and(even);
        assertTrue(posEven.test(4));
        assertFalse(posEven.test(3));
        assertFalse(posEven.test(-2));
    }

    @Test public void callableReturnsValue() throws Exception {
        Callable<String> c = () -> "result";
        assertEquals("result", c.call());
    }

    @Test public void consumerAcceptsValue() {
        StringBuilder sb = new StringBuilder();
        Consumer<String> append = sb::append;
        append.accept("hello");
        assertEquals("hello", sb.toString());
    }

    @Test public void biConsumerAcceptsTwoValues() {
        StringBuilder sb = new StringBuilder();
        BiConsumer<String, String> join = (a, b) -> sb.append(a).append(b);
        join.accept("foo", "bar");
        assertEquals("foobar", sb.toString());
    }

    // -----------------------------------------------------------------------
    // toString stability and identity
    // -----------------------------------------------------------------------

    @Test public void toStringIsStable() {
        Runnable r = () -> {};
        assertEquals("toString() must return the same string on every call",
                r.toString(), r.toString());
    }

    @Test public void twoDifferentLinesProduceDifferentLabels() {
        Runnable a = () -> {};
        Runnable b = () -> {};
        assertNotEquals("lambdas on different lines should have different labels",
                a.toString(), b.toString());
    }

    @Test public void sameInstanceToStringMatchesSelf() {
        // A single lambda instance always returns the same label.
        Supplier<String> s = () -> "x";
        assertEquals("same instance must return identical label on each call",
                s.toString(), s.toString());
    }

    @Test public void nonCapturingLambdaIdentityPreserved() {
        // Non-capturing lambdas from the same call site must return the same instance
        // on every invocation — the JVM caches them as singletons, and the agent must
        // preserve that guarantee (it wraps the singleton once at class-load time).
        Runnable first  = getNonCapturing();
        Runnable second = getNonCapturing();
        assertSame("repeated calls to the same non-capturing lambda site must return the same instance",
                first, second);
    }

    private static Runnable getNonCapturing() { return () -> {}; }

    @Test public void nonCapturingLambdaLabelCorrect() {
        Runnable r = () -> {};
        assertLabel(r.toString());
        assertTrue("label must contain enclosing method name",
                r.toString().contains("nonCapturingLambdaLabelCorrect"));
    }

    @Test public void labelContainsEnclosingMethodName() {
        Runnable r = () -> {};
        assertTrue("label must contain enclosing method name, got: " + r,
                r.toString().contains("labelContainsEnclosingMethodName"));
    }

    // -----------------------------------------------------------------------
    // Anonymous class — must NOT be instrumented
    // -----------------------------------------------------------------------

    @Test public void anonymousClassToStringPreserved() {
        Runnable r = new Runnable() {
            @Override public void run() {}
            @Override public String toString() { return "MY_CUSTOM"; }
        };
        assertEquals("MY_CUSTOM", r.toString());
    }

    @Test public void anonymousClassWithoutToStringUsesObjectDefault() {
        // No toString() override → Object.toString() (contains @hashcode)
        Runnable r = new Runnable() { @Override public void run() {} };
        assertTrue("anonymous class should use Object.toString(), got: " + r.toString(),
                r.toString().contains("@"));
    }

    // -----------------------------------------------------------------------
    // Nested and higher-order lambdas
    // -----------------------------------------------------------------------

    @Test public void lambdaReturnedFromLambdaHasLabel() {
        Supplier<Runnable> outer = () -> () -> {};
        assertLabel(outer.toString());
        assertLabel(outer.get().toString());
    }

    @Test public void lambdaPassedToHigherOrderFunctionWorks() {
        Function<Supplier<Integer>, Integer> invoke = Supplier::get;
        Supplier<Integer> s = () -> 99;
        assertEquals(99, (int) invoke.apply(s));
        assertLabel(s.toString());
    }

    @Test public void lambdaStoredInFieldWorks() {
        assertEquals("hello", FIELD_LAMBDA.get());
        assertLabel(FIELD_LAMBDA.toString());
    }

    private static final Supplier<String> FIELD_LAMBDA = () -> "hello";

    // -----------------------------------------------------------------------
    // Stream pipelines — real-world usage
    // -----------------------------------------------------------------------

    @Test public void streamFilterMapCollect() {
        List<String> result = Stream.of("a", "bb", "ccc")
                .filter(s -> s.length() > 1)
                .map(String::toUpperCase)
                .toList();
        assertEquals(List.of("BB", "CCC"), result);
    }

    @Test public void streamReduceWorks() {
        int sum = IntStream.rangeClosed(1, 10).reduce(0, Integer::sum);
        assertEquals(55, sum);
    }

    @Test public void optionalMapWorks() {
        Optional<String> result = Optional.of("hello")
                .map(String::toUpperCase)
                .filter(s -> s.startsWith("H"));
        assertEquals("HELLO", result.orElse(""));
    }

    // -----------------------------------------------------------------------
    // Custom @FunctionalInterface
    // -----------------------------------------------------------------------

    @FunctionalInterface
    interface Transformer<A, B> { B transform(A input); }

    @Test public void customFunctionalInterface() {
        Transformer<String, Integer> t = String::length;
        assertLabel(t.toString());
        assertEquals(5, (int) t.transform("hello"));
    }

    @Test public void customFunctionalInterfaceLambda() {
        Transformer<Integer, Integer> square = x -> x * x;
        assertLabel(square.toString());
        assertEquals(9, (int) square.transform(3));
    }

    // -----------------------------------------------------------------------
    // Interface that re-declares Object methods (Comparator.equals)
    // -----------------------------------------------------------------------

    @Test public void comparatorEqualsIsProxyIdentity() {
        // Comparator re-declares equals(Object) from Object.
        // The proxy must use identity semantics for it, not delegate to the inner lambda.
        Comparator<String> c = String::compareTo;
        assertEquals("proxy.equals(itself) must be true", c, c);
        Comparator<String> c2 = String::compareTo;
        assertNotEquals("two different proxy instances must not be equal", c, c2);
    }

    @Test public void comparatorHashCodeIsIdentityBased() {
        Comparator<String> c = String::compareTo;
        assertEquals(System.identityHashCode(c), c.hashCode());
    }

    // -----------------------------------------------------------------------
    // Default methods on functional interface
    // -----------------------------------------------------------------------

    @Test public void comparatorReversedDelegatesCorrectly() {
        Comparator<Integer> asc = (a, b) -> a - b;
        Comparator<Integer> desc = asc.reversed();
        assertTrue(desc.compare(1, 2) > 0);
        assertTrue(desc.compare(2, 1) < 0);
    }

    @Test public void functionComposedViaAndThen() {
        Function<String, String> trim  = String::trim;
        Function<String, String> upper = String::toUpperCase;
        Function<String, String> chain = trim.andThen(upper);
        assertEquals("HELLO", chain.apply("  hello  "));
    }

    @Test public void predicateNegated() {
        Predicate<String> notEmpty = ((Predicate<String>) String::isEmpty).negate();
        assertTrue(notEmpty.test("x"));
        assertFalse(notEmpty.test(""));
    }

    // -----------------------------------------------------------------------
    // Serializable lambdas — must NOT be wrapped (serialization must work)
    // -----------------------------------------------------------------------

    @Test public void serializableLambdaIsNotWrapped() throws Exception {
        // Cast to both Runnable and Serializable simultaneously (altMetafactory path)
        Runnable r = (Runnable & Serializable) () -> {};
        // Serialisation round-trip must succeed
        byte[] bytes;
        try (var baos = new ByteArrayOutputStream();
             var oos  = new ObjectOutputStream(baos)) {
            oos.writeObject(r);
            bytes = baos.toByteArray();
        }
        Runnable r2;
        try (var ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            r2 = (Runnable) ois.readObject();
        }
        // The deserialized lambda must be callable
        r2.run();
        // The deserialized lambda must have its JVM-default toString (not our label),
        // because we deliberately skip wrapping Serializable lambdas.
        assertFalse("serializable lambda must not carry our label",
                r2.toString().startsWith("Lambda["));
    }

    @Test public void serializableLambdaFunctionallyCorrect() throws Exception {
        java.util.function.Supplier<String> s = (Supplier<String> & Serializable) () -> "ok";
        assertEquals("ok", s.get());
    }

    // -----------------------------------------------------------------------
    // Lambda returning null
    // -----------------------------------------------------------------------

    @Test public void lambdaReturningNullHasLabel() {
        Supplier<String> s = () -> null;
        assertLabel(s.toString());
        assertNull(s.get());
    }

    // -----------------------------------------------------------------------
    // Same-line lambdas
    // -----------------------------------------------------------------------

    @Test public void twoLambdasOnSameLineHaveSameLineNumber() {
        // Both lambdas are on the next line; they must report the same line number.
        Runnable a = () -> {}; Runnable b = () -> {};
        String sa = a.toString(), sb = b.toString();
        // Extract :NNN from each label
        java.util.regex.Matcher ma = Pattern.compile(":(\\d+)\\)").matcher(sa);
        java.util.regex.Matcher mb = Pattern.compile(":(\\d+)\\)").matcher(sb);
        assertTrue("label a must contain line number", ma.find());
        assertTrue("label b must contain line number", mb.find());
        assertEquals("same-line lambdas must share the same line number",
                ma.group(1), mb.group(1));
    }

    // -----------------------------------------------------------------------
    // Lambda in enum
    // -----------------------------------------------------------------------

    enum Op {
        DOUBLE(x -> x * 2),
        NEGATE(x -> -x);

        final IntUnaryOperator fn;
        Op(IntUnaryOperator fn) { this.fn = fn; }
    }

    @Test public void lambdaInEnumHasLabel() {
        assertLabel(Op.DOUBLE.fn.toString());
        assertLabel(Op.NEGATE.fn.toString());
    }

    @Test public void lambdaInEnumFunctionallyCorrect() {
        assertEquals(8,  Op.DOUBLE.fn.applyAsInt(4));
        assertEquals(-3, Op.NEGATE.fn.applyAsInt(3));
    }

    // -----------------------------------------------------------------------
    // Lambda in record
    // -----------------------------------------------------------------------

    record Transformer2(Supplier<String> supplier) {}

    @Test public void lambdaInRecordHasLabel() {
        var t = new Transformer2(() -> "rec");
        assertLabel(t.supplier().toString());
        assertEquals("rec", t.supplier().get());
    }

    // -----------------------------------------------------------------------
    // Package-private functional interface (same package as test)
    // -----------------------------------------------------------------------

    @FunctionalInterface
    interface PackagePrivateAction { void run(); }

    @Test public void packagePrivateFunctionalInterface() {
        PackagePrivateAction a = () -> {};
        assertLabel(a.toString());
        a.run(); // must not throw
    }

    @Test public void packagePrivateFunctionalInterfaceWithCapture() {
        int[] counter = {0};
        PackagePrivateAction a = () -> counter[0]++;
        a.run();
        assertEquals(1, counter[0]);
        assertLabel(a.toString());
    }

    // -----------------------------------------------------------------------
    // Exception propagation through proxy
    // -----------------------------------------------------------------------

    @Test public void exceptionPropagatedThroughProxy() {
        Callable<String> boom = () -> { throw new IllegalStateException("boom"); };
        assertLabel(boom.toString());
        try {
            boom.call();
            fail("should have thrown");
        } catch (IllegalStateException e) {
            assertEquals("boom", e.getMessage());
        } catch (Exception e) {
            fail("unexpected exception type: " + e);
        }
    }

    @Test public void exceptionStackTraceHasNoAgentFrames() {
        // Stack trace from an exception thrown inside a lambda must have no agent internals,
        // and must contain a synthetic "λ created in" annotation frame.
        Runnable r = () -> { throw new RuntimeException("trace-test"); };
        try {
            r.run();
            fail("should have thrown");
        } catch (RuntimeException e) {
            boolean sawCreationFrame = false;
            for (StackTraceElement frame : e.getStackTrace()) {
                String cls = frame.getClassName();
                assertFalse("agent frame leaked into stack trace: " + frame,
                        cls.startsWith("lambda.tostring."));
                assertFalse("proxy frame leaked into stack trace: " + frame,
                        cls.startsWith("jdk.proxy") || cls.contains("$Proxy"));
                assertFalse("invokeWithArguments frame leaked into stack trace: " + frame,
                        cls.equals("java.lang.invoke.MethodHandle")
                        && "invokeWithArguments".equals(frame.getMethodName()));
                if (cls.startsWith("// ^ via ")) sawCreationFrame = true;
            }
            assertTrue("stack trace must contain a '// ^ via' annotation frame",
                    sawCreationFrame);
        }
    }

    // -----------------------------------------------------------------------
    // LabelFormat token coverage
    // -----------------------------------------------------------------------

    @Test public void labelFormatDefaultPattern() {
        // Default pattern must be Lambda[%i @ %c.%m(%f:%l)]
        assertEquals("Lambda[%i @ %c.%m(%f:%l)]", LabelFormat.DEFAULT_PATTERN);
    }

    @Test public void labelFormatAllTokens() {
        String result = LabelFormat.format("Runnable", "com.example.Foo", "bar", "Foo.java", 42);
        assertEquals("Lambda[Runnable @ com.example.Foo.bar(Foo.java:42)]", result);
    }

    @Test public void labelFormatMissingLine() {
        String result = LabelFormat.format("Runnable", "com.example.Foo", "bar", "Foo.java", -1);
        assertEquals("Lambda[Runnable @ com.example.Foo.bar(Foo.java:?)]", result);
    }

    @Test public void labelFormatSimpleClass() {
        String result = LabelFormat.format("Supplier", "com.example.Foo", "main", "Foo.java", 10,
                LabelFormat.DEFAULT_PATTERN.replace("%c", "%s"));
        // %s should give just "Foo"
        assertTrue("expected simple class name 'Foo', got: " + result, result.contains("Foo.main"));
        assertFalse("should not contain full package", result.contains("com.example.Foo.main"));
    }

    @Test public void labelFormatCustomPattern() {
        String pat = "%i|%c|%m|%f|%l";
        String result = LabelFormat.format("Runnable", "pkg.C", "m", "C.java", 7, pat);
        assertEquals("Runnable|pkg.C|m|C.java|7", result);
    }

    @Test public void labelFormatEscapedPercent() {
        String result = LabelFormat.format("X", "a.B", "c", "B.java", 1, "100%%");
        assertEquals("100%", result);
    }

    @Test public void labelFormatUnknownTokenPassedThrough() {
        // Unknown token %z: the % is emitted literally, z is left as-is
        String result = LabelFormat.format("X", "a.B", "c", "B.java", 1, "%z");
        assertEquals("%z", result);
    }

    // -----------------------------------------------------------------------
    // Exception propagation — proxy must not wrap in UndeclaredThrowableException
    // -----------------------------------------------------------------------

    @FunctionalInterface interface ThrowingAction { void run() throws Exception; }

    @Test public void undeclaredCheckedExceptionWrappedInUTE() {
        // Known Proxy limitation: java.lang.reflect.Proxy wraps any checked exception
        // thrown by the delegate that is NOT declared by the interface method in an
        // UndeclaredThrowableException. This is inherent to how Proxy works and cannot
        // be bypassed without switching to a generated class approach.
        // The cause is always the original exception, so callers can unwrap it.
        Supplier<String> s = () -> { sneakyThrow(new java.io.IOException("sneaky")); return ""; };
        try {
            s.get();
            fail("expected exception");
        } catch (java.lang.reflect.UndeclaredThrowableException e) {
            // Expected: Proxy wraps the undeclared checked exception
            assertTrue("cause must be the original IOException",
                    e.getCause() instanceof java.io.IOException);
            assertEquals("sneaky", e.getCause().getMessage());
        } catch (Throwable t) {
            fail("expected UndeclaredThrowableException, got: " + t);
        }
    }

    @Test public void declaredCheckedExceptionPropagatesCleanly() throws Exception {
        // Callable.call() declares throws Exception — IOException propagates normally.
        Callable<String> c = () -> { throw new java.io.IOException("declared"); };
        try {
            c.call();
            fail("expected IOException");
        } catch (java.io.IOException e) {
            assertEquals("declared", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E { throw (E) t; }

    // -----------------------------------------------------------------------
    // LabelFormat — format patterns containing commas
    // -----------------------------------------------------------------------

    @Test public void labelFormatConfigureWithCommaInPattern() {
        // Simulate agent arg "format=Lambda[%i,%c]"
        // The comma is part of the pattern, not a key separator.
        LabelFormat.configure("format=Lambda[%i,%c]");
        try {
            String result = LabelFormat.format("Runnable", "pkg.Foo", "m", "Foo.java", 1);
            assertEquals("Lambda[Runnable,pkg.Foo]", result);
        } finally {
            // Restore default so other tests are not affected
            LabelFormat.configure("format=" + LabelFormat.DEFAULT_PATTERN);
        }
    }

    @Test public void labelFormatConfigureWithLeadingKeyAndCommaInPattern() {
        // Other keys before format= must still work, and pattern preserves commas
        LabelFormat.configure("verbose=true,format=%i,%c");
        try {
            String result = LabelFormat.format("Runnable", "pkg.Foo", "m", "Foo.java", 1);
            assertEquals("Runnable,pkg.Foo", result);
        } finally {
            LabelFormat.configure("format=" + LabelFormat.DEFAULT_PATTERN);
        }
    }

    @Test public void exceptionCauseChainHasNoAgentFrames() {
        // Agent frames must be stripped from cause/suppressed exceptions too.
        RuntimeException cause = new RuntimeException("cause");
        Runnable r = () -> {
            RuntimeException wrapper = new RuntimeException("wrapper", cause);
            RuntimeException suppressed = new RuntimeException("suppressed");
            wrapper.addSuppressed(suppressed);
            throw wrapper;
        };
        try {
            r.run();
            fail("should have thrown");
        } catch (RuntimeException e) {
            for (Throwable t : new Throwable[]{ e, e.getCause(), e.getSuppressed()[0] }) {
                for (StackTraceElement frame : t.getStackTrace()) {
                    String cls = frame.getClassName();
                    assertFalse("agent frame in cause/suppressed: " + frame,
                            cls.startsWith("lambda.tostring.") || cls.startsWith("jdk.proxy")
                            || cls.contains("$Proxy")
                            || (cls.equals("java.lang.invoke.MethodHandle")
                                && "invokeWithArguments".equals(frame.getMethodName())));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // andThen / compose result — known limitation: not a wrapped lambda
    // -----------------------------------------------------------------------

    @Test public void andThenResultIsNotWrappedLambda() {
        // The result of Function.andThen() is an internal JDK lambda, not created
        // via our rewritten invokedynamic, so it does NOT get our label.
        // This test documents the limitation and ensures andThen still works correctly.
        Function<String, String> trim  = String::trim;
        Function<String, String> upper = String::toUpperCase;
        Function<String, String> chain = trim.andThen(upper);
        assertEquals("HELLO", chain.apply("  hello  "));
        assertFalse("andThen result should NOT have our label (known limitation)",
                chain.toString().startsWith("Lambda["));
    }
}
