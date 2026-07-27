package heimdall.conformance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Runs the {@link HeimdallRules} over every shipped module.
 *
 * <p>The module list is not written down here. The Gradle build derives it from the project graph
 * and hands over the compiled class directories, so a new {@code module-punishments} falls under
 * every rule the moment it exists, with no edit to this file. {@link #everyCompiledClassIsScanned}
 * holds that promise to account: if a directory contains a {@code com.heimdall} class the importer
 * did not pick up, the suite fails rather than quietly shrinking its own scope.
 *
 * <p>The rest of the suite has two halves. {@code RulesFire} points each rule at a deliberate
 * violation and asserts it reports one — without this, a rule that silently matches nothing looks
 * exactly like a clean codebase. {@code ModulesAreClean} then asserts the real modules pass.
 */
class ArchitectureConformanceTest {

    /** Class output directories of every scanned module, supplied by the Gradle build. */
    private static final List<Path> SCANNED_DIRS = readScannedDirs();

    /** Every shipped Heimdall class, imported from exactly those directories. */
    private static final JavaClasses PRODUCTION =
            new ClassFileImporter().importPaths(SCANNED_DIRS);

    /** The deliberate-violation fixtures, which live outside {@code com.heimdall} on purpose. */
    private static final JavaClasses FIXTURES =
            new ClassFileImporter().importPackages("heimdall.conformance.fixtures");

    private static final String[] FIXTURE_PACKAGE = {"heimdall.conformance.fixtures.."};

    private static List<Path> readScannedDirs() {
        String raw = System.getProperty("heimdall.conformance.classDirs", "");
        if (raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> dirs = new ArrayList<Path>();
        for (String entry : raw.split(java.io.File.pathSeparator)) {
            if (!entry.isEmpty()) {
                dirs.add(Paths.get(entry));
            }
        }
        return dirs;
    }

    @Test
    @DisplayName("the Gradle build actually handed over some modules to scan")
    void modulesWereHandedOver() {
        assertFalse(
                SCANNED_DIRS.isEmpty(),
                "heimdall.conformance.classDirs was empty — the Gradle wiring that derives the "
                        + "scanned modules from the project graph is broken, and every rule below "
                        + "would pass vacuously");
        assertFalse(PRODUCTION.isEmpty(), "no classes imported from " + SCANNED_DIRS);

        String modules = System.getProperty("heimdall.conformance.moduleNames", "");
        assertTrue(modules.contains("core"), "core missing from the scanned module list: " + modules);
        assertTrue(modules.contains("api"), "api missing from the scanned module list: " + modules);
    }

    @Test
    @DisplayName("every compiled com.heimdall class is in the imported set")
    void everyCompiledClassIsScanned() {
        Set<String> onDisk = new TreeSet<String>();
        for (Path dir : SCANNED_DIRS) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                onDisk.addAll(
                        walk.filter(Files::isRegularFile)
                                .map(dir::relativize)
                                .map(Path::toString)
                                .map(p -> p.replace('\\', '/'))
                                .filter(p -> p.startsWith("com/heimdall/") && p.endsWith(".class"))
                                .map(p -> p.substring(0, p.length() - ".class".length()))
                                .map(p -> p.replace('/', '.'))
                                .collect(Collectors.toSet()));
            } catch (IOException e) {
                throw new UncheckedIOException("could not walk " + dir, e);
            }
        }

        assertFalse(onDisk.isEmpty(), "no compiled com.heimdall classes found under " + SCANNED_DIRS);

        List<String> missing = onDisk.stream()
                .filter(name -> !PRODUCTION.contain(name))
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            fail("compiled classes the conformance import missed — the rules below do not cover "
                    + "them: " + missing);
        }
    }

    @Nested
    @DisplayName("the rules fire when violated")
    class RulesFire {

        @Test
        void platformIsolationFiresOnServerApis() {
            assertFires(HeimdallRules.platformIsolation(FIXTURE_PACKAGE), "PlatformLeakFixture");
        }

        @Test
        void platformIsolationFiresOnAdventurePlatformBindings() {
            assertFires(
                    HeimdallRules.platformIsolation(FIXTURE_PACKAGE), "AdventurePlatformFixture");
        }

        @Test
        @DisplayName("but platform-free Adventure stays allowed")
        void platformIsolationAllowsPlatformFreeAdventure() {
            String report = HeimdallRules.platformIsolation(FIXTURE_PACKAGE)
                    .evaluate(FIXTURES)
                    .getFailureReport()
                    .toString();
            assertFalse(
                    report.contains("PlatformFreeAdventureFixture"),
                    "the rule flagged Adventure's platform-free text API, which core is designed "
                            + "to build messages with:\n" + report);
        }

        @Test
        void noSharedParallelismFiresOnCommonPool() {
            assertFires(HeimdallRules.noSharedParallelism(FIXTURE_PACKAGE), "CommonPoolFixture");
        }

        @Test
        void noSharedParallelismFiresOnParallelStreamAndConstructor() {
            EvaluationResult result =
                    HeimdallRules.noSharedParallelism(FIXTURE_PACKAGE).evaluate(FIXTURES);
            assertTrue(result.hasViolation(), "the parallelism rule reported nothing at all");

            List<String> violations = result.getFailureReport().getDetails();
            assertViolationMentions(violations, "viaParallelStream");
            assertViolationMentions(violations, "viaStreamParallel");
            assertViolationMentions(violations, "viaArraysParallelSort");
            assertViolationMentions(violations, "viaConstructor");
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

        private void assertViolationMentions(List<String> violations, String needle) {
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains(needle)),
                    "no violation mentioned " + needle + "; the rule does not cover that route "
                            + "onto the common pool. Violations were:\n"
                            + String.join("\n", violations));
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
        void nothingReachesTheCommonPool() {
            HeimdallRules.noSharedParallelism("com.heimdall..").check(PRODUCTION);
        }

        @Test
        void everyAsyncStageNamesItsExecutor() {
            HeimdallRules.executorsAreAlwaysNamed("com.heimdall..").check(PRODUCTION);
        }
    }
}
