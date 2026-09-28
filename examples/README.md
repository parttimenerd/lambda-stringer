# lambda-stringer examples

This Maven module contains runnable examples and tests for `lambda-stringer`.

## Prerequisites

1. Java 25+
2. Build and install `lambda-stringer` into your local Maven repository:

   ```sh
   # From the root of the lambda-stringer project:
   mvn install -DskipTests
   ```

   This installs `me.bechberger:lambda-stringer:VERSION` into `~/.m2/repository`.

## Run the tests

```sh
cd examples
mvn test
```

The `maven-surefire-plugin` is configured to attach `lambda-stringer.jar` automatically via `-javaagent`. The tests in `LambdaLabelTest` verify that:

- Every lambda and method reference has a `Lambda[...]` label
- Labels contain the interface name, enclosing class, method, source file, and line number
- Non-capturing lambdas remain singletons (JVM guarantee preserved)
- Agent frames are stripped from stack traces; a `// ^ via` annotation is injected

## Run a demo

```sh
mvn package -DskipTests
java -javaagent:${HOME}/.m2/repository/me/bechberger/lambda-stringer/VERSION/lambda-stringer-VERSION.jar \
     -cp target/lambda-stringer-examples-*.jar \
     lambda.tostring.examples.BasicUsage
```

## Using lambda-stringer in your own Maven project

Add the dependency and configure Surefire:

```xml
<dependency>
  <groupId>me.bechberger</groupId>
  <artifactId>lambda-stringer</artifactId>
  <version>VERSION</version>
  <scope>test</scope>
</dependency>

<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>
      -javaagent:${settings.localRepository}/me/bechberger/lambda-stringer/VERSION/lambda-stringer-VERSION.jar
    </argLine>
  </configuration>
</plugin>
```

For production use (attach at startup, not just tests):

```sh
java -javaagent:lambda-stringer.jar -jar your-app.jar
```

Or download the latest release:

```sh
curl -L -o lambda-stringer.jar \
  https://github.com/parttimenerd/lambda-stringer/releases/download/latest/lambda-stringer.jar
```
