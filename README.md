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

This is useful for logging, debugging, and any place where you want to know *which* lambda you are looking at without adding manual labels throughout your codebase.

## Requirements

Java 25+

## Build

```sh
git clone https://github.com/parttimenerd/lambda-stringer
cd lambda-stringer
mvn package -DskipTests    # builds target/lambda-stringer-1.0.jar
mvn test                   # build + run all tests
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

- Requires Java 25 (uses `java.lang.classfile` and `StackWalker`)
- `Serializable` lambdas report their default JVM `toString()`, not a label
- The label is baked in at call-site bootstrap time; if a class is loaded before the
  agent is installed the lambdas in that class are not instrumented

## License

MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger and lambda-stringer contributors
