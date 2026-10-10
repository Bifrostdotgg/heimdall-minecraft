package com.heimdall.build

/**
 * The rule that keeps the hot-swap shell and core apart, as a pure function over class names.
 *
 * <p>Parent-first class loading makes any class present in both jars resolve from the shell, every
 * time. The core's copy of such a class is dead code that no swap can ever upgrade, and nothing at
 * runtime says so: the server runs the old class forever. So the split is asserted on the built
 * artifacts, and it is an allowlist in both directions:
 *
 * <ul>
 *   <li>every class in the shell lives under one of the shell-only prefixes;
 *   <li>no class in the core does;
 *   <li>and, explicitly, no class name appears in both.
 * </ul>
 *
 * <p>Kept separate from the task so the rule can be pointed at fixtures that must fail, the same
 * way [DescriptorChecks] is: a check that has never been seen rejecting anything cannot be told
 * apart from one that does not work.
 */
object JarSplit {

    fun problems(
        shellClasses: Collection<String>,
        coreClasses: Collection<String>,
        shellOnlyPrefixes: List<String>,
    ): List<String> {
        val problems = mutableListOf<String>()
        val strays = shellClasses.filterNot { name -> shellOnlyPrefixes.any { name.startsWith(it) } }
        if (strays.isNotEmpty()) {
            problems += "${strays.size} class(es) in the shell jar are not shell classes, so a " +
                "swap can never replace them: ${strays.sorted().take(5).joinToString(", ")}"
        }
        val leaked = coreClasses.filter { name -> shellOnlyPrefixes.any { name.startsWith(it) } }
        if (leaked.isNotEmpty()) {
            problems += "${leaked.size} shell class(es) are also in the core jar, where the " +
                "shell's copy always wins and the core's is dead: " +
                leaked.sorted().take(5).joinToString(", ")
        }
        val both = shellClasses.toSet().intersect(coreClasses.toSet())
        if (both.isNotEmpty()) {
            problems += "${both.size} class(es) are in both jars: " +
                both.sorted().take(5).joinToString(", ")
        }
        return problems
    }

    /** Runs [problems] on fixtures with known answers and reports any it got wrong. */
    fun selfCheckProblems(): List<String> {
        val prefixes = listOf("com/heimdall/shell/", "com/heimdall/api/")
        val wrong = mutableListOf<String>()
        if (problems(listOf("com/heimdall/shell/A.class"), listOf("com/heimdall/core/B.class"),
                prefixes).isNotEmpty()
        ) {
            wrong += "a clean split was reported as broken"
        }
        if (problems(listOf("com/heimdall/core/B.class"), emptyList(), prefixes).isEmpty()) {
            wrong += "a core class in the shell jar was not reported"
        }
        if (problems(listOf("com/heimdall/shell/A.class"), listOf("com/heimdall/api/X.class"),
                prefixes).isEmpty()
        ) {
            wrong += "a shell class in the core jar was not reported"
        }
        return wrong
    }
}
