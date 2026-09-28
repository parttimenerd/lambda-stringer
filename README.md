# lambda-stringer

[![CI](https://github.com/parttimenerd/lambda-stringer/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/lambda-stringer/actions/workflows/ci.yml)

Lambda and method references are quite opaque in Java. The default `toString()` is a JVM-generated class name and hash code,
which is not very useful for debugging or logging. This project provides a Java agent that gives every lambda and method 
reference a human-readable `toString()` without requiring any source code changes.

Without the agent:
```
java.lang.Object$$Lambda/0x00007f9c3c001200@1b6d3586
```

With the agent:
```
Lambda[Runnable @ com.example.Foo.bar(Foo.java:42)]
```

In stack traces, the agent injects a `// ^ via` annotation after the executor frame 
showing the interface type and the lambda's creation site:

```
java.lang.NumberFormatException: For input string: "abc"
    at java.lang.Integer.parseInt(Integer.java:662)
    at com.example.Parser.parse(Parser.java:7)
    at // ^ via Lambda[Function @ MyService.configure(MyService.java:34)]
    at com.example.MyService.configure(MyService.java:35)
```

The emitted format is configurable, and the agent can be attached at runtime or during tests.

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

### Maven — attach during tests

Add the dependency so Maven downloads the jar, then tell Surefire to attach it.
`${settings.localRepository}` resolves to wherever `~/.m2` lives on the machine.

```xml
<dependencies>
  <dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>lambda-stringer</artifactId>
    <version>0.2</version>
    <scope>test</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <configuration>
        <argLine>-javaagent:${settings.localRepository}/me/bechberger/lambda-stringer/0.2/lambda-stringer-0.2.jar</argLine>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### Maven — attach at runtime (packaged app)

Use the `maven-dependency-plugin` to copy the jar to a known path during the build,
then reference it on the command line:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-dependency-plugin</artifactId>
      <executions>
        <execution>
          <id>copy-agent</id>
          <phase>prepare-package</phase>
          <goals><goal>copy</goal></goals>
          <configuration>
            <artifactItems>
              <artifactItem>
                <groupId>me.bechberger</groupId>
                <artifactId>lambda-stringer</artifactId>
                <version>0.2</version>
                <outputDirectory>${project.build.directory}/agents</outputDirectory>
                <destFileName>lambda-stringer.jar</destFileName>
              </artifactItem>
            </artifactItems>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

Then attach at startup with a version-independent path:

```sh
java -javaagent:target/agents/lambda-stringer.jar -jar your-app.jar
```

## What it does

Works anywhere you log, inspect, or assert on a lambda — no source changes needed:

```java
log.warn("stuck task: {}", runningTask);
// → stuck task: Lambda[Runnable @ OrderService.processOrder(OrderService.java:88)]

log.atDebug().addKeyValue("task", task.toString()).log("submitting");
// → {"level":"DEBUG","task":"Lambda[Runnable @ Scheduler.buildTask(Scheduler.java:42)]",...}

assertTrue(registry.getHandler().toString().contains("PaymentService.onFailure"));
```

```
=== Pending tasks ===
  Lambda[Runnable @ OrderService.processOrder(OrderService.java:88)]
  Lambda[Runnable @ NotificationService.sendEmail(NotificationService.java:42)]
  Lambda[Runnable @ CacheService.evict(CacheService.java:117)]
```

The `// ^ via` stack trace annotation (shown in the intro) is injected automatically for both lambda bodies and method references.

## Custom format

Pass a `format=` argument to the agent or set a system property:

```sh
java -javaagent:lambda-stringer.jar=format=%c#%m:%l -jar your-app.jar
# → com.example.Scheduler#buildTask:42

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

_M-series Mac. Run `make bench` / `make bench-baseline` to measure on your hardware._

The ~35 ns per-invocation overhead is negligible for real workloads; it only shows up in microbenchmarks invoking a trivial lambda in a tight loop.

## How it works

Lambda classes are *hidden classes* (Java 15+) — never passed to a `ClassFileTransformer`, so they can't be instrumented directly. Instead, the agent rewrites `invokedynamic` bootstrap references in *caller* classes at load time, redirecting `LambdaMetafactory` → `WrappingMetafactory`. The wrapper chains the real lambda through a `java.lang.reflect.Proxy` that overrides `toString()` with a label computed once at bootstrap time via `StackWalker`. `Serializable` lambdas are left unwrapped to preserve serialization round-trips.

## Limitations

- **Java 25+ required** — uses `java.lang.classfile` (GA in Java 24) and `StackWalker`
- **`Serializable` lambdas** — not wrapped; report the default JVM `toString()`
- **Classes loaded before agent installation** — not instrumented
- **JDK-internal lambdas** — `andThen`, `compose`, `negate`, etc. come from JDK classes loaded before the agent; their `toString()` returns the default JVM representation
- **Undeclared checked exceptions** — `Proxy` wraps them in `UndeclaredThrowableException`; original always in `getCause()`

## Build from source

```sh
git clone https://github.com/parttimenerd/lambda-stringer
cd lambda-stringer
mvn package -DskipTests    # builds target/lambda-stringer.jar
mvn package                # build + run all tests
```

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and lambda-stringer contributors
