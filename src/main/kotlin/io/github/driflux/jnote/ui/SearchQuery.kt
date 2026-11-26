package io.github.driflux.jnote.ui

import io.github.driflux.jnote.db.SearchRepository

object SearchQuery {
    /** 앞뒤 공백을 뺀다. SearchRepository도 같은 방식으로 다듬는다. */
    fun normalize(text: String): String = text.trim()

    /** 최소 길이(코드 포인트 기준) 이상이어야 검색한다. 그보다 짧으면 목록을 그대로 둔다. */
    fun isSearchable(text: String): Boolean {
        val q = normalize(text)
        return q.codePointCount(0, q.length) >= SearchRepository.MIN_QUERY_LENGTH
    }
}
