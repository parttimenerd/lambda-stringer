JAR       = target/lambda-stringer-1.0.jar
TEST_CP   = target/test-classes:$(JAR)
BENCH_CLS = lambda.tostring.LambdaBenchmark

.PHONY: all test bench bench-baseline clean

all: $(JAR)

$(JAR): pom.xml $(shell find src/main -name '*.java')
	mvn package -q -DskipTests

test: $(JAR)
	mvn package -q

# Run benchmark WITH the agent (shows actual overhead)
bench: $(JAR)
	mvn test-compile -q
	@echo "=== WITH agent ==="
	java -javaagent:$(JAR) -cp $(TEST_CP) $(BENCH_CLS)

# Run benchmark WITHOUT the agent (baseline)
bench-baseline: $(JAR)
	mvn test-compile -q
	@echo "=== WITHOUT agent (baseline) ==="
	java -cp $(TEST_CP) $(BENCH_CLS)

clean:
	mvn -q clean
