# lambda-stringer

[![CI](https://github.com/parttimenerd/lambda-stringer/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/lambda-stringer/actions/workflows/ci.yml)

A Java agent that gives every lambda and method reference a human-readable `toString()` — no source changes required.

Without the agent:
```
java.lang.Object$$Lambda/0x00007f9c3c001200@1b6d3586
```

With the agent:
```
Lambda[Runnable @ com.example.Foo.bar(Foo.java:42)]
```

## Install

Download the latest release:

```sh
curl -L -o lambda-stringer.jar \
  https://github.com/parttimenerd/lambda-stringer/releases/download/latest/lambda-stringer.jar
```

Then attach it at startup:

```sh
java -javaagent:lambda-stringer.jar -jar your-app.jar
```

### Maven (attach during tests)

```xml
<dependency>
  <groupId>me.bechberger</groupId>
  <artifactId>lambda-stringer</artifactId>
  <version>0.2</version>
  <scope>test</scope>
</dependency>
```

Configure Surefire to attach the agent:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>
      -javaagent:${settings.localRepository}/me/bechberger/lambda-stringer/0.2/lambda-stringer-0.2.jar
    </argLine>
  </configuration>
</plugin>
```

## What it does

**Log lambdas stored in queues, fields, or collections:**

```java
log.warn("stuck task: {}", runningTask);
// → stuck task: Lambda[Runnable @ OrderService.processOrder(OrderService.java:88)]
```

```
=== Pending tasks ===
  Lambda[Runnable @ OrderService.processOrder(OrderService.java:88)]
  Lambda[Runnable @ NotificationService.sendEmail(NotificationService.java:42)]
  Lambda[Runnable @ CacheService.evict(CacheService.java:117)]
```

**Read clean stack traces** — agent frames are stripped; a `// ^ via` annotation is injected after the executor frame showing the interface type and the lambda's creation site:

```
java.lang.RuntimeException: task failed
    at com.example.OrderService.lambda$process$2(OrderService.java:91)
    at com.example.Registry.runAll(Registry.java:12)
    at // ^ via Lambda[Runnable @ OrderService.setup(OrderService.java:88)]
    at com.example.App.main(App.java:20)
```

For method references, where no lambda body frame appears, this annotation is especially useful:

```
java.lang.NumberFormatException: For input string: "abc"
    at java.lang.Integer.parseInt(Integer.java:662)
    at com.example.Parser.parse(Parser.java:7)
    at // ^ via Lambda[Function @ MyService.configure(MyService.java:34)]
    at com.example.MyService.configure(MyService.java:35)
```

**Assert on lambda identity in tests:**

```java
String label = registry.getHandler().toString();
assertTrue(label.contains("PaymentService.onFailure"));
```

**Embed labels in structured logs:**

```java
log.atDebug().addKeyValue("task", task.toString()).log("submitting");
// → {"level":"DEBUG","task":"Lambda[Runnable @ Scheduler.buildTask(Scheduler.java:42)]",...}
```

## Custom format

Pass a `format=` argument to the agent:

```sh
java -javaagent:lambda-stringer.jar=format=%c#%m:%l -jar your-app.jar
# → com.example.Scheduler#buildTask:42
```

Or set a system property before the agent loads:

```sh
java -Dlambda.tostring.format="%s::%m:%l" -javaagent:lambda-stringer.jar ...
# → Scheduler::buildTask:42
```

### Format tokens

| Token | Meaning                              | Example           |
|-------|--------------------------------------|-------------------|
| `%i`  | functional interface simple name     | `Runnable`        |
| `%c`  | enclosing class fully-qualified name | `com.example.Foo` |
| `%s`  | enclosing class simple name          | `Foo`             |
| `%m`  | enclosing method name                | `bar`             |
| `%f`  | source file name                     | `Foo.java`        |
| `%l`  | line number (`?` if unavailable)     | `42`              |
| `%%`  | literal `%`                          | `%`               |

Default: `Lambda[%i @ %c.%m(%f:%l)]`

## Performance

Labels are computed **once per call site** at class-load time — `toString()` is then a single field read.

| Scenario                    | Without agent | With agent                            |
|-----------------------------|---------------|---------------------------------------|
| Create non-capturing lambda | ~2 ns         | ~2 ns (singleton cached at bootstrap) |
| Invoke pre-created lambda   | ~1 ns         | ~35 ns (Proxy dispatch)               |
| `toString()` on lambda      | ~4 ns         | ~4 ns (field read)                    |

_M-series Mac; results vary by JVM and hardware. Run `make bench` and `make bench-baseline` to measure on your hardware._

The ~35 ns Proxy dispatch overhead per invocation is negligible for any real workload. It shows up only in tight microbenchmarks that invoke a trivial lambda millions of times.

## How it works

Lambda classes are *hidden classes* (Java 15+) and are never passed to a `ClassFileTransformer`, so they cannot be instrumented directly.

Instead, the agent rewrites `invokedynamic` bootstrap references in *caller* classes at load time, redirecting `LambdaMetafactory` → `WrappingMetafactory`. The wrapper chains the real lambda through a `java.lang.reflect.Proxy` that overrides `toString()` with a label computed once at bootstrap time from the enclosing class, method, source file, and line number (via `StackWalker`). `Serializable` lambdas are left unwrapped to preserve serialization round-trips.

## Limitations

- **Java 25+ required** — uses `java.lang.classfile` (GA in Java 24) and `StackWalker`
- **`Serializable` lambdas** — not wrapped; they report the default JVM `toString()`
- **Classes loaded before agent installation** — lambdas in those classes are not instrumented
- **JDK-internal lambdas** — lambdas created by `java/util/function/` default methods (`andThen`, `compose`, `negate`, `reversed`, etc.) are from JDK classes loaded before the agent; their `toString()` returns the default JVM representation
- **Undeclared checked exceptions** — `Proxy` wraps undeclared checked exceptions in `UndeclaredThrowableException`; the original is always in `getCause()`

## Build from source

```sh
git clone https://github.com/parttimenerd/lambda-stringer
cd lambda-stringer
mvn package -DskipTests    # builds target/lambda-stringer.jar
mvn package                # build + run all tests
```

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and lambda-stringer contributors
