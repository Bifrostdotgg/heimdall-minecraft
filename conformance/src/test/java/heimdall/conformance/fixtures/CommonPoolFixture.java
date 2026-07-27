package heimdall.conformance.fixtures;

import java.util.concurrent.ForkJoinPool;

/**
 * Deliberate violation of {@link heimdall.conformance.HeimdallRules#noCommonPool}.
 */
public final class CommonPoolFixture {

    private CommonPoolFixture() {
    }

    /** Submits work to the JVM-wide common pool, which Heimdall code may not do. */
    public static void submitToCommonPool() {
        ForkJoinPool.commonPool().execute(new Runnable() {
            @Override
            public void run() {
                // no-op
            }
        });
    }
}
