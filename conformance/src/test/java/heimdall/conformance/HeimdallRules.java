package heimdall.conformance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The v3 architecture, expressed as executable rules.
 *
 * <p>Each rule is a factory taking the package prefixes it applies to. That indirection is not
 * decoration: it lets the test suite point the exact same predicate at a deliberate-violation
 * fixture, proving the rule actually fires before asserting that the real modules are clean. A
 * conformance rule nobody has ever seen fail is indistinguishable from a rule that does not work.
 *
 * <p><strong>Limitation worth knowing:</strong> these rules read bytecode, so they see direct
 * references only. {@code Class.forName("org.bukkit.Bukkit")} in platform-free code, or an executor
 * obtained reflectively, is invisible to every rule here. Reflective platform access is a
 * deliberate technique in this codebase (see {@code PaperSupport}) — it just has to stay confined
 * to the platform modules, and that confinement is a review responsibility, not something ArchUnit
 * can enforce.
 */
final class HeimdallRules {

    /**
     * Server platform and game-internal APIs that platform-free code must never touch.
     *
     * <p>{@code net.kyori.adventure.platform..} is listed while the rest of Adventure is not: the
     * platform bindings (BukkitAudiences and friends) drag in server types, whereas
     * {@code net.kyori.adventure.text..} is pure model code. The v3 design gives core a message
     * renderer built on Adventure's platform-free API, so banning all of {@code net.kyori} would
     * ban the design.
     */
    static final String[] PLATFORM_PACKAGES = {
        "org.bukkit..",
        "io.papermc..",
        "com.destroystokyo..",
        "com.velocitypowered..",
        "net.md_5..",
        "com.mojang..",
        "net.minecraft..",
        "org.spigotmc..",
        "net.kyori.adventure.platform..",
    };

    /** Package prefixes that must stay platform-free. */
    static final String[] PLATFORM_FREE_PACKAGES = {
        "com.heimdall.core..", "com.heimdall.api..", "com.heimdall.module..",
    };

    private static final String EXECUTOR = "java.util.concurrent.Executor";
    private static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";
    private static final String FORK_JOIN_POOL = "java.util.concurrent.ForkJoinPool";

    private static final Set<String> ASYNC_METHODS = new HashSet<String>(
            Arrays.asList(
                    "supplyAsync",
                    "runAsync",
                    "thenApplyAsync",
                    "thenAcceptAsync",
                    "thenRunAsync",
                    "thenComposeAsync",
                    "thenCombineAsync",
                    "thenAcceptBothAsync",
                    "runAfterBothAsync",
                    "applyToEitherAsync",
                    "acceptEitherAsync",
                    "runAfterEitherAsync",
                    "whenCompleteAsync",
                    "handleAsync",
                    "exceptionallyAsync",
                    "exceptionallyComposeAsync",
                    "completeAsync"));

    private static final Set<String> COMPLETION_TYPES = new HashSet<String>(
            Arrays.asList(COMPLETABLE_FUTURE, "java.util.concurrent.CompletionStage"));

    /** {@code java.util.Arrays} helpers that fan out onto the common pool. */
    private static final Set<String> PARALLEL_ARRAY_METHODS = new HashSet<String>(
            Arrays.asList("parallelSort", "parallelPrefix", "parallelSetAll"));

    private static final DescribedPredicate<JavaMethodCall> USES_COMMON_POOL =
            new DescribedPredicate<JavaMethodCall>("reach the common ForkJoinPool") {
                @Override
                public boolean test(JavaMethodCall call) {
                    String owner = call.getTarget().getOwner().getFullName();
                    String name = call.getTarget().getName();
                    List<JavaClass> parameters = call.getTarget().getRawParameterTypes();

                    // The explicit handle on it.
                    if (FORK_JOIN_POOL.equals(owner) && "commonPool".equals(name)) {
                        return true;
                    }
                    // Collection.parallelStream() / List.parallelStream() / ...
                    if ("parallelStream".equals(name) && parameters.isEmpty()) {
                        return true;
                    }
                    // Stream/IntStream/LongStream/DoubleStream.parallel()
                    if ("parallel".equals(name)
                            && parameters.isEmpty()
                            && owner.startsWith("java.util.stream.")) {
                        return true;
                    }
                    // Arrays.parallelSort / parallelPrefix / parallelSetAll
                    if ("java.util.Arrays".equals(owner) && PARALLEL_ARRAY_METHODS.contains(name)) {
                        return true;
                    }
                    // CompletableFuture.delayedExecutor(long, TimeUnit) — the two-arg
                    // overload schedules onto the common pool. The three-arg one takes
                    // an Executor and is fine.
                    if (COMPLETABLE_FUTURE.equals(owner)
                            && "delayedExecutor".equals(name)
                            && !hasExecutorParameter(parameters)) {
                        return true;
                    }
                    return false;
                }
            };

    private static final DescribedPredicate<JavaConstructorCall> CONSTRUCTS_FORK_JOIN_POOL =
            new DescribedPredicate<JavaConstructorCall>("construct a ForkJoinPool directly") {
                @Override
                public boolean test(JavaConstructorCall call) {
                    return FORK_JOIN_POOL.equals(call.getTarget().getOwner().getFullName());
                }
            };

    private static final DescribedPredicate<JavaMethodCall> EXECUTORLESS_ASYNC =
            new DescribedPredicate<JavaMethodCall>(
                    "call an async CompletableFuture/CompletionStage overload without an Executor") {
                @Override
                public boolean test(JavaMethodCall call) {
                    if (!COMPLETION_TYPES.contains(call.getTarget().getOwner().getFullName())) {
                        return false;
                    }
                    if (!ASYNC_METHODS.contains(call.getTarget().getName())) {
                        return false;
                    }
                    return !hasExecutorParameter(call.getTarget().getRawParameterTypes());
                }
            };

    private HeimdallRules() {
    }

    private static boolean hasExecutorParameter(List<JavaClass> parameters) {
        for (JavaClass parameter : parameters) {
            if (EXECUTOR.equals(parameter.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Platform isolation: core, the public API and the feature modules describe behaviour in terms
     * of Heimdall's own abstractions. If a Bukkit or Velocity type leaks in, the module stops being
     * portable and the single-jar, three-platform design quietly dies.
     */
    static ArchRule platformIsolation(String... scopedPackages) {
        return noClasses()
                .that()
                .resideInAnyPackage(scopedPackages)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(PLATFORM_PACKAGES)
                .as("platform-free code must not depend on server platform APIs")
                .because(
                        "core, api and the feature modules have to compile and run on Spigot, Paper"
                                + " and Velocity alike");
    }

    /**
     * No shared parallelism: every route onto {@code ForkJoinPool.commonPool()} is closed, not just
     * the obvious one. The common pool is shared with the whole JVM including the server's own
     * parallel work, so Heimdall saturating it stalls the server, and Heimdall work stuck behind
     * someone else's is undiagnosable. {@code parallelStream()}, {@code Stream.parallel()} and the
     * {@code Arrays.parallel*} helpers all land there without ever naming it, which is precisely
     * why they have to be in the rule rather than left to reviewer memory.
     *
     * <p>Constructing a {@code ForkJoinPool} by hand is banned too — not because it touches the
     * common pool, but because an unnamed, unowned, never-shut-down pool is the same
     * unaccountability by another route.
     */
    static ArchRule noSharedParallelism(String... scopedPackages) {
        ArchCondition<JavaClass> reachesCommonPool =
                ArchConditions.callMethodWhere(USES_COMMON_POOL)
                        .or(ArchConditions.callConstructorWhere(CONSTRUCTS_FORK_JOIN_POOL));
        return noClasses()
                .that()
                .resideInAnyPackage(scopedPackages)
                .should(reachesCommonPool)
                .as("no class may reach the JVM-wide common ForkJoinPool or build its own")
                .because("Heimdall owns and names its executors so its work can be bounded, "
                        + "instrumented and shut down cleanly");
    }

    /**
     * Owned executors: the executor-less {@code *Async} overloads on {@link
     * java.util.concurrent.CompletableFuture} silently fall back to the common pool (or, worse, run
     * inline on the caller's thread). Every async hop has to name the executor it runs on.
     */
    static ArchRule executorsAreAlwaysNamed(String... scopedPackages) {
        return noClasses()
                .that()
                .resideInAnyPackage(scopedPackages)
                .should(ArchConditions.callMethodWhere(EXECUTORLESS_ASYNC))
                .as("async CompletableFuture stages must name their Executor")
                .because("the executor-less overloads default to the common pool, which is exactly "
                        + "what the previous rule forbids");
    }
}
