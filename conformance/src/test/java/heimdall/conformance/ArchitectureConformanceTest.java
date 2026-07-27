package heimdall.conformance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Runs the {@link HeimdallRules} over every shipped module.
 *
 * <p>The suite has two halves. {@code RulesFire} points each rule at a deliberate violation and
 * asserts it reports one — without this, a rule that silently matches nothing would look identical
 * to a clean codebase. {@code ModulesAreClean} then asserts the real modules pass.
 */
class ArchitectureConformanceTest {

    /** Every shipped Heimdall class on the test runtime classpath. */
    private static final JavaClasses PRODUCTION =
            new ClassFileImporter().importPackages("com.heimdall");

    /** The deliberate-violation fixtures, which live outside {@code com.heimdall} on purpose. */
    private static final JavaClasses FIXTURES =
            new ClassFileImporter().importPackages("heimdall.conformance.fixtures");

    private static final String[] FIXTURE_PACKAGE = {"heimdall.conformance.fixtures.."};

    @Test
    @DisplayName("the importer actually sees the shipped modules")
    void importerSeesTheModules() {
        assertFalse(PRODUCTION.isEmpty(), "no com.heimdall classes on the conformance classpath");

        List<String> expected = Arrays.asList(
                "com.heimdall.core.CoreSanity",
                "com.heimdall.core.BuildConstants",
                "com.heimdall.api.HeimdallTunnel",
                "com.heimdall.module.whitelist.HeimdallWhitelistModule",
                "com.heimdall.module.rolesync.HeimdallRoleSyncModule",
                "com.heimdall.module.offenses.HeimdallOffensesModule",
                "com.heimdall.module.console.HeimdallConsoleModule",
                "com.heimdall.platform.bukkit.HeimdallBukkitPlugin",
                "com.heimdall.platform.bukkit.paper.PaperSupport",
                "com.heimdall.platform.velocity.HeimdallVelocityPlugin");
        for (String className : expected) {
            assertTrue(
                    PRODUCTION.contain(className),
                    className + " missing from the conformance import — classpath regression");
        }
    }

    @Nested
    @DisplayName("the rules fire when violated")
    class RulesFire {

        @Test
        void platformIsolationFires() {
            assertFires(HeimdallRules.platformIsolation(FIXTURE_PACKAGE), "PlatformLeakFixture");
        }

        @Test
        void noCommonPoolFires() {
            assertFires(HeimdallRules.noCommonPool(FIXTURE_PACKAGE), "CommonPoolFixture");
        }

        @Test
        void executorsAreAlwaysNamedFires() {
            assertFires(
                    HeimdallRules.executorsAreAlwaysNamed(FIXTURE_PACKAGE),
                    "ExecutorlessAsyncFixture");
        }

        private void assertFires(ArchRule rule, String expectedFixture) {
            EvaluationResult result = rule.evaluate(FIXTURES);
            assertTrue(
                    result.hasViolation(),
                    "rule reported no violation against its own fixture: " + rule.getDescription());
            String report = result.getFailureReport().toString();
            assertTrue(
                    report.contains(expectedFixture),
                    "expected " + expectedFixture + " in the failure report but got:\n" + report);
        }
    }

    @Nested
    @DisplayName("the shipped modules are clean")
    class ModulesAreClean {

        @Test
        void coreApiAndModulesArePlatformFree() {
            HeimdallRules.platformIsolation(HeimdallRules.PLATFORM_FREE_PACKAGES).check(PRODUCTION);
        }

        @Test
        void nothingUsesTheCommonPool() {
            HeimdallRules.noCommonPool("com.heimdall..").check(PRODUCTION);
        }

        @Test
        void everyAsyncStageNamesItsExecutor() {
            HeimdallRules.executorsAreAlwaysNamed("com.heimdall..").check(PRODUCTION);
        }
    }
}
