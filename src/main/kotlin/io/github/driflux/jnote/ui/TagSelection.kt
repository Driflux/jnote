package io.github.driflux.jnote.ui

/**
 * 다이얼로그에서 고른 태그 이름. 입력 문자열을 태그로 나누는 규칙이 여기 있어 Swing 없이 테스트한다.
 * 이름은 앞뒤 공백을 자르고 대소문자를 구분하며(저장소와 같은 규칙), 쉼표는 구분자라 이름에 들어가지 않는다.
 */
class TagSelection(initial: List<String> = emptyList()) {
    private val chosen = LinkedHashSet<String>()

    init {
        initial.forEach { add(it) }
    }

    /** 고른 순서대로. */
    val names: List<String> get() = chosen.toList()

    fun contains(name: String): Boolean = name in chosen

    /** [raw]를 쉼표로 나눠 모두 고른다. 새로 고른 이름이 있으면 true. */
    fun add(raw: String): Boolean = parse(raw).fold(false) { changed, name -> chosen.add(name) || changed }

    fun toggle(name: String) {
        if (!chosen.remove(name)) chosen.add(name)
    }

    companion object {
        fun parse(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
