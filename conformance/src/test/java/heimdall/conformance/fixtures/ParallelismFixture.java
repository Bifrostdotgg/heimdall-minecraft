package heimdall.conformance.fixtures;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

/**
 * Deliberate violations of {@link heimdall.conformance.HeimdallRules#noSharedParallelism} that
 * never mention the common pool by name.
 *
 * <p>These are the cases that matter: {@code ForkJoinPool.commonPool()} is easy to spot in review,
 * whereas {@code parallelStream()} looks like an innocent performance tweak and lands in exactly
 * the same place.
 */
public final class ParallelismFixture {

    private ParallelismFixture() {
    }

    /** Fans out onto the common pool without naming it. */
    public static long viaParallelStream(List<String> values) {
        return values.parallelStream().filter(v -> !v.isEmpty()).count();
    }

    /** Same, through the stream API. */
    public static long viaStreamParallel(List<String> values) {
        return values.stream().parallel().count();
    }

    /** Sorts on the common pool. */
    public static void viaArraysParallelSort(int[] values) {
        Arrays.parallelSort(values);
    }

    /** Builds an unowned, unnamed, never-shut-down pool. */
    public static ForkJoinPool viaConstructor() {
        return new ForkJoinPool(2);
    }
}
