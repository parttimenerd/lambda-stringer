# Changelog

## [0.1] — 2026-09-28

Initial release.

### Features

- **Human-readable `toString()`** for every lambda and method reference, computed once at class-load time (bootstrap) — zero per-invocation cost.
- **Configurable label format** via `-javaagent:...=format=<pattern>` or the `lambda.tostring.format` system property. Tokens: `%i` (interface), `%c` (class FQN), `%s` (class simple name), `%m` (method), `%f` (file), `%l` (line), `%%` (literal `%`). Default: `Lambda[%i @ %c.%m(%f:%l)]`.
- **Clean stack traces** — agent-internal frames (`LambdaHandler`, `$Proxy`, `invokeWithArguments`) are stripped from exceptions thrown through a lambda proxy. The configured label is injected as a `// ^ via <label>` annotation frame after the executor frame, pointing to the lambda's creation site with a real file/line for IDE navigation.
- **Cause chain stripping** — the above stripping applies recursively to `getCause()` and `getSuppressed()` chains.
- **Non-capturing lambda singleton** — the JVM's per-call-site singleton guarantee is preserved; the Proxy is created once at bootstrap, not on every access.
- **`Serializable` lambdas** left unwrapped to preserve serialization round-trips.
- **`agentmain` support** for dynamic attach via `VirtualMachine.attach()` without restarting the JVM.
- **Package-private and module-private interfaces** supported via the caller's `MethodHandles.Lookup`.
- Java 25+ required (uses `java.lang.classfile` API, GA in Java 24).
