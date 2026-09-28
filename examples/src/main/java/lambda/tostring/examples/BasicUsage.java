package lambda.tostring.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Demonstrates human-readable toString() labels on lambdas and method references
 * when lambda-stringer is attached as a Java agent.
 *
 * Run with: java -javaagent:lambda-stringer.jar -cp ... lambda.tostring.examples.BasicUsage
 */
public class BasicUsage {

    public static void main(String[] args) {
        // --- Runnable ---
        Runnable task = () -> System.out.println("processing order");
        System.out.println("task      : " + task);
        // → task      : Lambda[Runnable @ BasicUsage.main(BasicUsage.java:XX)]

        // --- Supplier ---
        Supplier<String> greeting = () -> "hello";
        System.out.println("greeting  : " + greeting);
        // → greeting  : Lambda[Supplier @ BasicUsage.main(BasicUsage.java:XX)]

        // --- Function (method reference) ---
        Function<String, Integer> parser = Integer::parseInt;
        System.out.println("parser    : " + parser);
        // → parser    : Lambda[Function @ BasicUsage.main(BasicUsage.java:XX)]

        // --- Consumer ---
        Consumer<String> printer = System.out::println;
        System.out.println("printer   : " + printer);
        // → printer   : Lambda[Consumer @ BasicUsage.main(BasicUsage.java:XX)]

        // --- Collection of lambdas ---
        List<Runnable> tasks = List.of(
            () -> processOrder(1001),
            () -> sendNotification("user@example.com"),
            () -> evictCache("product:42")
        );
        System.out.println("\n=== Pending tasks ===");
        tasks.forEach(t -> System.out.println("  " + t));
        // → Lambda[Runnable @ BasicUsage.main(BasicUsage.java:XX)]  (one per entry)
    }

    static void processOrder(int id)             { System.out.println("order " + id); }
    static void sendNotification(String address) { System.out.println("notify " + address); }
    static void evictCache(String key)            { System.out.println("evict " + key); }
}
