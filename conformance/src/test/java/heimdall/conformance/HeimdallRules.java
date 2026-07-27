package heimdall.conformance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The v3 architecture, expressed as executable rules.
 *
 * <p>Each rule is a factory taking the package prefixes it applies to. That indirection is not
 * decoration: it lets the test suite point the exact same predicate at a deliberate-violation
 * fixture, proving the rule actually fires before asserting that the real modules are clean. A
 * conformance rule nobody has ever seen fail is indistinguishable from a rule that does not work.
 */
final class HeimdallRules {

    /** Server platform APIs that platform-free code must never touch. */
    static final String[] PLATFORM_PACKAGES = {
        "org.bukkit..", "io.papermc..", "com.destroystokyo..", "com.velocitypowered..", "net.md_5..",
    };

    /** Package prefixes that must stay platform-free. */
    static final String[] PLATFORM_FREE_PACKAGES = {
        "com.heimdall.core..", "com.heimdall.api..", "com.heimdall.module..",
    };

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
            Arrays.asList(
                    "java.util.concurrent.CompletableFuture",
                    "java.util.concurrent.CompletionStage"));

    private static final String EXECUTOR = "java.util.concurrent.Executor";

    private static final DescribedPredicate<JavaMethodCall> COMMON_POOL =
            new DescribedPredicate<JavaMethodCall>("call ForkJoinPool.commonPool()") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return "java.util.concurrent.ForkJoinPool"
                                    .equals(call.getTarget().getOwner().getFullName())
                            && "commonPool".equals(call.getTarget().getName());
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
                    for (JavaClass parameter : call.getTarget().getRawParameterTypes()) {
                        if (EXECUTOR.equals(parameter.getName())) {
                            return false;
                        }
                    }
                    return true;
                }
            };

    private HeimdallRules() {
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
     * No common pool: {@code ForkJoinPool.commonPool()} is shared with the entire JVM, including
     * the server's own parallel work. Heimdall saturating it stalls the server, and a Heimdall
     * task stuck behind someone else's is undiagnosable.
     */
    static ArchRule noCommonPool(String... scopedPackages) {
        return noClasses()
                .that()
                .resideInAnyPackage(scopedPackages)
                .should(ArchConditions.callMethodWhere(COMMON_POOL))
                .as("no class may submit work to the JVM-wide common ForkJoinPool")
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
