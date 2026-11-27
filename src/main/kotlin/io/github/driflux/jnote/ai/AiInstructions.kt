package io.github.driflux.jnote.ai

/** 웹 AI에 붙여 넣을 형식 안내. 글은 모두 빌드 때 리소스로 들어간 docs/ai-format.md에서 온다. */
object AiInstructions {
    private const val SPEC_RESOURCE = "/ai/ai-format.md"
    private const val WEB_AI_HEADING = "### For a web AI"

    /** 리소스를 읽으므로 EDT 밖에서 부른다. */
    fun text(): String {
        val spec = checkNotNull(AiInstructions::class.java.getResourceAsStream(SPEC_RESOURCE)) { "missing $SPEC_RESOURCE" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        return build(spec)
    }

    /** 명세의 웹 AI용 프롬프트 예시를 앞에 두고 명세 전체를 붙인다. 예시가 없으면 명세만 돌려준다. */
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
