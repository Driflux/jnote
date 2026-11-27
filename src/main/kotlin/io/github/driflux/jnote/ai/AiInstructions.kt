package io.github.driflux.jnote.ai

object AiInstructions {
    private const val SPEC_RESOURCE = "/ai/ai-format.md"
    private const val WEB_AI_HEADING = "### For a web AI"

    fun text(): String {
        val spec = checkNotNull(AiInstructions::class.java.getResourceAsStream(SPEC_RESOURCE)) { "missing $SPEC_RESOURCE" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        return build(spec)
    }

    internal fun build(spec: String): String {
        val prompt = spec.lineSequence()
            .dropWhile { !it.startsWith(WEB_AI_HEADING) }
            .drop(1)
            .dropWhile { it.isBlank() }
            .takeWhile { it.startsWith(">") }
            .joinToString("\n") { it.removePrefix(">").removePrefix(" ") }
        return if (prompt.isBlank()) spec else prompt + "\n\n" + spec
    }
}
