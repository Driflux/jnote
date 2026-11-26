package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.Tag

/** 태그 이름을 바꿀 때 입력이 허용되지 않는 이유. 번들 키만 들고 있어 Swing 없이 테스트한다. */
enum class TagNameProblem(val messageKey: String) {
    BLANK("toolWindow.tag.rename.invalid.blank"),
    COMMA("toolWindow.tag.rename.invalid.comma"),
    EXISTS("toolWindow.tag.rename.invalid.exists"),
}

object TagNameRules {
    /**
     * [input]을 [current]의 새 이름으로 쓸 수 있는지. 쓸 수 있으면 null.
     * 저장소의 규칙(앞뒤 공백 제거, 쉼표 금지, 대소문자 구분 유일성)과 같다. 자기 자신과 같은 이름은 문제가 아니다.
     */
    fun check(input: String, current: Tag, all: List<Tag>): TagNameProblem? {
        val name = input.trim()
        return when {
            name.isEmpty() -> TagNameProblem.BLANK
            ',' in name -> TagNameProblem.COMMA
            all.any { it.id != current.id && it.name == name } -> TagNameProblem.EXISTS
            else -> null
        }
    }
}
