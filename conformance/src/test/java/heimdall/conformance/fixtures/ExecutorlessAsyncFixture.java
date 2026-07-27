package heimdall.conformance.fixtures;

import java.util.concurrent.CompletableFuture;

/**
 * Deliberate violation of {@link heimdall.conformance.HeimdallRules#executorsAreAlwaysNamed}.
 */
public final class ExecutorlessAsyncFixture {

    private ExecutorlessAsyncFixture() {
    }

    /** Uses the executor-less {@code supplyAsync} / {@code thenApplyAsync} overloads. */
    public static CompletableFuture<String> unnamedExecutor() {
        return CompletableFuture.supplyAsync(() -> "phase-0").thenApplyAsync(String::toUpperCase);
    }
}
