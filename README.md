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

**Stack traces** — the Proxy frame shows a clean class name:
```
java.lang.IllegalStateException: task failed
    at com.example.OrderService.lambda$process$2(OrderService.java:91)
    at lambda.tostring.WrappingMetafactory$LambdaHandler.invoke(WrappingMetafactory.java:...)
    at jdk.proxy1/$Proxy0.run(Unknown Source)
    at com.example.TaskRunner.run(TaskRunner.java:34)
```
The lambda body frame (`lambda$process$2`) is already informative; the agent adds two frames of overhead.

**Thread dumps** — the original lambda class name still appears (no regression):
```
"worker-thread" WAITING
    at ...
    at com.example.App.lambda$main$0(App.java:17)
    at com.example.App$$Lambda/0x...run(Unknown Source)
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
  loaded before the agent runs and cannot be instrumented
- **Undeclared checked exceptions** — `java.lang.reflect.Proxy` wraps any checked
  exception not declared by the interface method in `UndeclaredThrowableException`;
  the original exception is always accessible via `getCause()`
- **Stack traces** — two extra frames appear: `LambdaHandler.invoke` and `$Proxy0.<method>`;
  the lambda body frame itself is unaffected

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and lambda-stringer contributors
