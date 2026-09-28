# lambda-stringer

[![CI](https://github.com/parttimenerd/lambda-stringer/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/lambda-stringer/actions/workflows/ci.yml)

A Java agent that gives every lambda expression a human-readable `toString()` at no cost to your source code.

Without the agent:
```
java.lang.Object$$Lambda/0x00007f9c3c001200@1b6d3586
```

With the agent:
```
Lambda[Runnable @ com.example.Foo.bar(Foo.java:42)]
```

## Where it helps

**Logging queues and collections of lambdas:**
```
=== Pending tasks ===
  Lambda[Runnable @ OrderService.processOrder(OrderService.java:88)]
  Lambda[Runnable @ NotificationService.sendEmail(NotificationService.java:42)]
  Lambda[Runnable @ CacheService.evict(CacheService.java:117)]
```

**Debugging which lambda is stored in a field or passed as a callback:**
```java
log.debug("retry action: {}", retryAction);
// → retry action: Lambda[Runnable @ PaymentHandler.retryCharge(PaymentHandler.java:63)]
```

**Stack traces** — agent frames are stripped and a `// ^ via` annotation is injected after the executor frame, telling you both the interface type and where the lambda was created:

```
java.lang.RuntimeException: task failed
    at com.example.OrderService.lambda$process$2(OrderService.java:91)
    at com.example.Registry.runAll(Registry.java:12)
    at // ^ via Runnable λ created in OrderService.setup(OrderService.java:88)
    at com.example.App.main(App.java:20)
```

The `^` points at the executor frame (who called the lambda). The annotation gives the interface type and the exact creation site — both are clickable in IDEs.

For method references, where no lambda body frame appears, this is especially useful:

```
java.lang.NumberFormatException: For input string: "abc"
    at java.lang.Integer.parseInt(Integer.java:662)
    at com.example.Parser.parse(Parser.java:7)
    at // ^ via Function λ created in MyService.configure(MyService.java:34)
    at com.example.MyService.configure(MyService.java:35)
```

**Thread dumps** — the original lambda class name still appears (no regression):
```
"worker-thread" WAITING
    at ...
    at com.example.App.lambda$main$0(App.java:17)
    at com.example.App$$Lambda/0x...run(Unknown Source)
```

## Use cases

### Executor / thread-pool debugging

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
List<Runnable> tasks = List.of(
    () -> processOrder(orderId),
    () -> sendNotification(userId),
    () -> evictCache(key)
);
tasks.forEach(pool::submit);

// If a task hangs, log it to see exactly which lambda is stuck:
log.warn("long-running task: {}", runningTask);
// → long-running task: Lambda[Runnable @ OrderService.processOrder(OrderService.java:88)]
```

### Callback registries

```java
// Before the agent: impossible to tell which handler is registered
eventBus.register("payment.failed", handler);
log.info("registered handler: {}", handler);
// → registered handler: Lambda[Consumer @ PaymentService.onFailure(PaymentService.java:55)]
```

### Spring Boot / dependency injection

Attach the agent to your Spring Boot application — no code changes required:

```sh
java -javaagent:lambda-stringer-1.0.jar -jar my-app.jar
```

Spring's `@EventListener`, `@Scheduled`, and `ApplicationListener` lambdas will all have readable labels in logs and thread dumps.

### Testing and assertions

```java
// Assert that the exact lambda you expect is registered
String label = registry.getHandler().toString();
assertTrue("expected payment handler", label.contains("PaymentService.onFailure"));
```

### Structured logging (e.g. SLF4J / Logback)

The label embeds cleanly into JSON logs:
```java
log.atDebug()
   .addKeyValue("task", task.toString())
   .log("submitting");
// → {"level":"DEBUG","task":"Lambda[Runnable @ Scheduler.buildTask(Scheduler.java:42)]",...}
```

### Custom format for log parsers

```sh
# Compact format for log ingestion pipelines
java -javaagent:lambda-stringer-1.0.jar=format=%c#%m:%l -jar my-app.jar
# → com.example.Scheduler#buildTask:42
```

## Requirements

Java 25+

## Build

```sh
git clone https://github.com/parttimenerd/lambda-stringer
cd lambda-stringer
mvn package -DskipTests    # builds target/lambda-stringer-1.0.jar
mvn package                # build + run all tests
```

Or with Make:
```sh
make                 # build
make test            # build + test
make bench           # benchmark WITH agent
make bench-baseline  # benchmark WITHOUT agent (for comparison)
```

## Use

Attach the agent to any Java 25+ application — no source changes required:

```sh
java -javaagent:target/lambda-stringer-1.0.jar -jar your-app.jar
```

Every lambda's `toString()` now returns a label like:
```
Lambda[Supplier @ com.example.MyService.buildFactory(MyService.java:87)]
```

## Custom format

The label format is configurable. Pass a `format=` argument to the agent:

```sh
java -javaagent:target/lambda-stringer-1.0.jar=format=%i@%c#%m:%l -jar your-app.jar
# → Supplier@com.example.MyService#buildFactory:87
```

Or set a system property before the agent loads:
```sh
java -Dlambda.tostring.format="%s::%m:%l" -javaagent:target/lambda-stringer-1.0.jar ...
# → MyService::buildFactory:87
```

### Available tokens

| Token | Meaning | Example |
|-------|---------|---------|
| `%i`  | functional interface simple name | `Runnable` |
| `%c`  | enclosing class fully-qualified name | `com.example.Foo` |
| `%s`  | enclosing class simple name | `Foo` |
| `%m`  | enclosing method name | `bar` |
| `%f`  | source file name | `Foo.java` |
| `%l`  | line number (`?` if unavailable) | `42` |
| `%%`  | literal `%` | `%` |

Default pattern: `Lambda[%i @ %c.%m(%f:%l)]`

## Performance

The label is computed **once per call site** at class-load time (bootstrap), not on every invocation. After the bootstrap, `toString()` is a single field read — O(1) and allocation-free.

Typical overhead measured with the built-in benchmark (`make bench` vs `make bench-baseline`):

| Scenario | Without agent | With agent |
|---|---|---|
| Create non-capturing lambda | ~2 ns | ~2 ns (singleton cached at bootstrap) |
| Invoke pre-created lambda | ~1 ns | ~35 ns (Proxy dispatch) |
| `toString()` on lambda | ~4 ns | ~4 ns (field read, unchanged) |

_Numbers from an M-series Mac; results vary by JVM and hardware._

Non-capturing lambdas are wrapped **once at class-load time** — the Proxy is a singleton just like the original, so repeated accesses to the same call site pay no allocation cost. The Proxy dispatch overhead on each `invoke()` call (~34 ns extra) is negligible for any workload where lambdas do real work. The main cost is visible only in tight micro-benchmarks that invoke a trivial lambda millions of times.

## How it works

Lambda classes are *hidden classes* (since Java 15): they are never passed to a
`ClassFileTransformer`, so they cannot be instrumented directly.

Instead, the agent rewrites `invokedynamic` bootstrap references in *caller* classes
at load time, redirecting `LambdaMetafactory` → `WrappingMetafactory`. The wrapper
intercepts each call site and chains the real lambda through a `java.lang.reflect.Proxy`
that overrides `toString()` with a label computed once at bootstrap time from the
enclosing class, method, source file, and line number (via `StackWalker`).

`Serializable` lambdas are left unwrapped to preserve serialization round-trips.

## Limitations

- **Java 25+ required** — uses `java.lang.classfile` (GA in Java 24) and `StackWalker`
- **`Serializable` lambdas** — not wrapped; they report the default JVM `toString()`
- **Classes loaded before agent installation** — lambdas in those classes are not instrumented
- **JDK-internal lambdas** — lambdas created by `java/util/function/` default methods
  (`andThen`, `compose`, `negate`, `reversed`, etc.) come from JDK classes that are
  loaded before the agent runs and cannot be instrumented; `chain.toString()` will return
  the default JVM representation
- **Undeclared checked exceptions** — `java.lang.reflect.Proxy` wraps any checked
  exception not declared by the interface method in `UndeclaredThrowableException`;
  the original exception is always accessible via `getCause()`
- **Stack traces** — agent frames (`LambdaHandler`, `$Proxy`, `invokeWithArguments`) are stripped;
  a `// ^ via InterfaceName λ created in …` annotation is injected after the executor frame
  so you can see both who called the lambda and where it was defined

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and lambda-stringer contributors
