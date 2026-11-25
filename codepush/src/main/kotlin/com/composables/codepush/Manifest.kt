package com.composables.codepush

/**
 * A code update: which installed version ([base]) it applies to, its number, and the jars it swaps in.
 *
 * [published] is when the update was published, in seconds since the epoch, so apps can apply licensing rules
 * such as "updates released within your update period". It is part of the signed text, so it can't be altered.
 *
 * Each [Replacement] names one of the installed app's jars by its [Replacement.module], the start of its file name
 * (for example `analytics` for `analytics-1.0.0-3a64.jar`), and the new jar that takes its place.
 * Everything else on the installed classpath, such as the runtime and third-party libraries, stays as installed.
 *
 * The text form is what gets signed, so it is written and read in exactly one way:
 * ```
 * codepush 1
 * app com.example.app
 * base 1.4.0
 * patch 3
 * published 1794003000
 * replace analytics <sha256 hex> <size>
 * ```
 */
data class Manifest(val app: String, val base: String, val patch: Int, val published: Long, val replacements: List<Replacement>) {
    data class Replacement(val module: String, val sha256: String, val size: Long)

    init {
        require(patch > 0) { "Patch numbers start at 1" }
        require(published > 0) { "Missing the publish time" }
        require(replacements.isNotEmpty()) { "A code update replaces at least one jar" }
        require(replacements.map { it.module }.toSet().size == replacements.size) { "Each module is replaced once" }
        listOf(app, base).plus(replacements.map { it.module }).forEach { require(Word.matches(it)) { "Not a valid name: $it" } }
        replacements.forEach { require(Sha256.matches(it.sha256) && it.size > 0) { "Not a valid file: $it" } }
    }

    fun text(): String = buildString {
        append("codepush 1\n")
        append("app $app\n")
        append("base $base\n")
        append("patch $patch\n")
        append("published $published\n")
        replacements.forEach { append("replace ${it.module} ${it.sha256} ${it.size}\n") }
    }

    companion object {
        private val Word = Regex("[A-Za-z0-9._+-]+")
        private val Sha256 = Regex("[0-9a-f]{64}")

        fun parse(text: String): Manifest {
            val lines = text.lines().dropLastWhile { it.isEmpty() }
            require(lines.firstOrNull() == "codepush 1") { "Not a codepush manifest" }
            fun field(name: String) = lines.singleOrNull { it.startsWith("$name ") }?.removePrefix("$name ")
                ?: throw IllegalArgumentException("Missing $name")
            val replacements = lines.filter { it.startsWith("replace ") }.map { line ->
                val parts = line.split(' ')
                require(parts.size == 4) { "Malformed line: $line" }
                Replacement(parts[1], parts[2], parts[3].toLong())
            }
            return Manifest(field("app"), field("base"), field("patch").toInt(), field("published").toLong(), replacements)
                .also { require(it.text() == text) { "Manifest is not in its canonical form" } }
        }
    }
}
