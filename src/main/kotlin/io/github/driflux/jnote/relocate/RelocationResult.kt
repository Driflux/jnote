package io.github.driflux.jnote.relocate

/** 재배치 결과. 줄 번호는 모두 1-based, 끝 줄은 포함(inclusive)이다. */
sealed interface RelocationResult {
    /** 공백 정규화 후 정확히 일치한 위치 */
    data class Match(val startLine: Int, val endLine: Int) : RelocationResult

    /** 유사도 기준으로 찾은 근사 위치. similarity는 줄별 유사도 평균(0.0..1.0) */
    data class Approx(val startLine: Int, val endLine: Int, val similarity: Double) : RelocationResult

    /** 찾지 못함 */
    data object None : RelocationResult
}
