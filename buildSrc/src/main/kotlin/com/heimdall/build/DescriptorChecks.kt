package com.heimdall.build

/**
 * Assertions about `plugin.yml`'s contents, and the fixtures that prove they fire.
 *
 * # Why this is not a regex any more
 *
 * The first version of the announcement-node check was one multiline regex, and it was wrong in
 * both directions at once:
 *
 * ```
 * (?ms)^\s*heimdall\.admin:.*?^\s*children:\s*$(.*?)(?=^\s{2}\S)
 * ```
 *
 * `.*?` was free to cross node boundaries, so a `children:` belonging to some *later* permission
 * satisfied a `heimdall.admin` that declared none - the check passed on exactly the descriptor it
 * exists to reject. And the trailing lookahead required another two-space-indented key
 * afterwards, so moving `heimdall.admin` to the end of the permissions map made the block
 * unfindable and the check failed on a descriptor that was correct.
 *
 * A regex is the wrong tool for a block-structured format. [blockOf] reads a block the way YAML
 * scopes one: the lines under a key, up to the next content line at or left of that key's own
 * indentation.
 *
 * # Why the fixtures live here
 *
 * `buildSrc` has no test source set, and cannot usefully be given one: Gradle stopped running
 * `buildSrc:build` in 8.0 (it produces only what the classpath needs, which is the jar), the
 * `kotlin-dsl` plugin compiles a test source set against that same jar so `jar dependsOn test` is
 * a cycle, and `gradle.includedBuild("buildSrc")` is not addressable from any script in this
 * build. A test there would run exactly never, on anybody's machine and in CI, while looking for
 * all the world like it was covered.
 *
 * So the fixtures run inside the check, which does run, every build. That is the same bargain
 * `:conformance` states in `HeimdallRules`: a rule nobody has ever seen fail is indistinguishable
 * from a rule that does not work, so the rule is exercised against a deliberate violation before
 * it is trusted about the real thing. [selfCheckProblems] is called by [VerifyShadowJar] before
 * the jar's own descriptor is read, and a failure there is a failure of the check itself rather
 * than of the artifact.
 */
internal object DescriptorChecks {

    private val ANNOUNCEMENT_NODES = listOf(
        "heimdall.punishments.notify",
        "heimdall.punishments.silent",
    )

    private const val ADMIN_NODE = "heimdall.admin"

    /**
     * Everything wrong with how a descriptor declares the two announcement nodes.
     *
     * Empty means correct. Each string is a whole sentence, because it is printed to somebody
     * looking at a failed build rather than at this file.
     */
    fun announcementNodeProblems(pluginYml: String): List<String> {
        val problems = mutableListOf<String>()
        for (node in ANNOUNCEMENT_NODES) {
            if (blockOf(pluginYml, node) == null) {
                problems += "plugin.yml does not declare $node, so Bukkit gives it no default " +
                    "and nobody but the console holds it"
            }
        }
        val admin = blockOf(pluginYml, ADMIN_NODE)
        if (admin == null) {
            problems += "plugin.yml does not declare $ADMIN_NODE at all"
            return problems
        }
        val children = blockOf(admin, "children")
        if (children == null) {
            problems += "$ADMIN_NODE declares no children, but the code treats it as granting " +
                ANNOUNCEMENT_NODES.joinToString(" and ")
            return problems
        }
        for (node in ANNOUNCEMENT_NODES) {
            if (!Regex("(?m)^\\s*${Regex.escape(node)}:\\s*true\\s*$").containsMatchIn(children)) {
                problems += "$ADMIN_NODE does not list $node as a child, so the descriptor " +
                    "disagrees with the audience check the plugin actually runs"
            }
        }
        return problems
    }

    /**
     * The lines nested under `key:`, or null when the key is not there at all.
     *
     * Blank lines and comments never end a block: a comment is conventionally written at the
     * parent's indentation, and treating one as the end would cut the block short in exactly the
     * descriptors that bother to explain themselves. End of file ends a block, so the last key in
     * a file is as findable as any other.
     */
    fun blockOf(yaml: String, key: String): String? {
        val opens = Regex("^(\\s*)${Regex.escape(key)}:\\s*(#.*)?$")
        val lines = yaml.lines()
        var start = -1
        var indent = -1
        for (i in lines.indices) {
            val match = opens.matchEntire(lines[i]) ?: continue
            start = i + 1
            indent = match.groupValues[1].length
            break
        }
        if (start < 0) {
            return null
        }
        val block = StringBuilder()
        for (i in start until lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                block.append(line).append('\n')
                continue
            }
            if (indentOf(line) <= indent) {
                break
            }
            block.append(line).append('\n')
        }
        return block.toString()
    }

    /**
     * Runs [announcementNodeProblems] over fixtures whose right answer is known.
     *
     * Every fixture is a descriptor the old regex got wrong, plus one that is simply correct so a
     * check that rejected everything would be caught as well. A non-empty return means the check
     * itself is broken, which is a different failure from a bad descriptor and is reported as
     * one.
     */
    fun selfCheckProblems(): List<String> {
        val failures = mutableListOf<String>()

        expect(failures, "a correct descriptor", CORRECT, shouldPass = true)
        // The old regex's `.*?` ran past heimdall.admin into the next node's children and passed.
        expect(failures, "children on a later node", CHILDREN_ON_A_LATER_NODE, shouldPass = false)
        // The old regex needed a following two-space key, so this correct descriptor failed.
        expect(failures, "heimdall.admin last in the file", ADMIN_LAST, shouldPass = true)
        // Bukkit gives an unlisted node no default, so a child entry alone is not a declaration.
        expect(failures, "a child that is never declared", CHILD_BUT_UNDECLARED, shouldPass = false)
        expect(
            failures,
            "a child set to false",
            CORRECT.replace(
                "      heimdall.punishments.silent: true",
                "      heimdall.punishments.silent: false",
            ),
            shouldPass = false,
        )

        if (blockOf(CORRECT, "heimdall.nope") != null) {
            failures += "blockOf answers a block for a key that is not in the file"
        }
        val adminBlock = blockOf(CORRECT, ADMIN_NODE).orEmpty()
        if (!adminBlock.contains("# a comment at the parent's indentation")) {
            failures += "blockOf lets a comment end a block, which truncates any descriptor that " +
                "explains itself"
        }
        if (adminBlock.contains("heimdall.offend")) {
            failures += "blockOf runs past the end of a node's own block"
        }
        return failures
    }

    private fun expect(
        failures: MutableList<String>,
        named: String,
        fixture: String,
        shouldPass: Boolean,
    ) {
        val problems = announcementNodeProblems(fixture)
        if (shouldPass && problems.isNotEmpty()) {
            failures += "the descriptor check rejects '$named', which is correct: $problems"
        }
        if (!shouldPass && problems.isEmpty()) {
            failures += "the descriptor check accepts '$named', which it exists to reject"
        }
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart().length

    private val CORRECT = """
        permissions:
          heimdall.admin:
            description: Use Heimdall's administrative commands
            default: op
        # a comment at the parent's indentation

            children:
              heimdall.punishments.notify: true
              heimdall.punishments.silent: true
          heimdall.offend:
            description: Record offences
            default: op
          heimdall.punishments.notify:
            default: op
          heimdall.punishments.silent:
            default: op
    """.trimIndent()

    private val CHILDREN_ON_A_LATER_NODE = """
        permissions:
          heimdall.admin:
            description: Use Heimdall's administrative commands
            default: op
          heimdall.offend:
            default: op
            children:
              heimdall.punishments.notify: true
              heimdall.punishments.silent: true
          heimdall.punishments.notify:
            default: op
          heimdall.punishments.silent:
            default: op
    """.trimIndent()

    private val ADMIN_LAST = """
        permissions:
          heimdall.punishments.notify:
            default: op
          heimdall.punishments.silent:
            default: op
          heimdall.admin:
            default: op
            children:
              heimdall.punishments.notify: true
              heimdall.punishments.silent: true
    """.trimIndent()

    private val CHILD_BUT_UNDECLARED = """
        permissions:
          heimdall.admin:
            default: op
            children:
              heimdall.punishments.notify: true
              heimdall.punishments.silent: true
          heimdall.punishments.silent:
            default: op
    """.trimIndent()
}
