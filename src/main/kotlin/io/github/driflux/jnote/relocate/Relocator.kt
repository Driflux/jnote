package io.github.driflux.jnote.relocate

/** @property isCancelled 구간마다, 유사도 구간 안에서는 16줄마다 확인한다. true가 되면 검색을 멈추고 None을 돌려준다. */
data class RelocationOptions(
    val similarityThreshold: Double = 0.8,
    val isCancelled: () -> Boolean = { false },
) {
    init {
        require(similarityThreshold in 0.0..1.0) { "similarityThreshold must be in 0.0..1.0: $similarityThreshold" }
    }
}

/** 스냅샷이 문서에서 어디로 옮겨졌는지 찾는다. 순수 로직이며 스레드와 I/O를 쓰지 않는다. */
object Relocator {
    /**
     * @param originalStartLine 노트에 저장된 1-based 시작 줄. 후보가 여럿일 때 가까운 쪽을 고른다.
     */
    fun locate(
        documentLines: List<String>,
        snapshotLines: List<String>,
        originalStartLine: Int,
        options: RelocationOptions = RelocationOptions(),
    ): RelocationResult {
        val snapshot = Normalizer.normalize(snapshotLines)
        if (snapshot.size == 0) return RelocationResult.None
        val document = Normalizer.normalize(documentLines)
        if (snapshot.size > document.size) return RelocationResult.None

        ExactMatcher.find(document, snapshot, originalStartLine, options.isCancelled)?.let { return it }
        if (options.isCancelled()) return RelocationResult.None
        return SimilarityMatcher(options.similarityThreshold, options.isCancelled)
            .find(document, snapshot, originalStartLine) ?: RelocationResult.None
    }
}
