package io.github.driflux.jnote.relocate

/** 재배치 결과. 줄 번호는 모두 1-based, 끝 줄은 포함(inclusive)이다. */
sealed interface RelocationResult {
    data class Match(val startLine: Int, val endLine: Int) : RelocationResult

    /** similarity는 줄별 유사도 평균(0.0..1.0)이다. */
    data class Approx(val startLine: Int, val endLine: Int, val similarity: Double) : RelocationResult

    data object None : RelocationResult
}
