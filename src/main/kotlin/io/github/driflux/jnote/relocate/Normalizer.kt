package io.github.driflux.jnote.relocate

/**
 * 정규화된 줄 목록. 빈 줄은 제거되어 있고 [lineNumbers]가 원본 문서의 1-based 줄 번호를 가리킨다.
 */
internal class NormalizedLines(val lines: List<String>, val lineNumbers: IntArray) {
    val size: Int get() = lines.size
}

/**
 * 줄 비교용 공백 정규화.
 * 각 줄을 trim하고 내부 공백 연속을 한 칸으로 줄인다. 빈 줄은 비교에서 제외한다.
 */
internal object Normalizer {
    fun normalizeLine(line: String): String {
        val sb = StringBuilder(line.length)
        var pendingSpace = false
        for (c in line) {
            if (c.isWhitespace()) {
                pendingSpace = sb.isNotEmpty()
            } else {
                if (pendingSpace) sb.append(' ')
                pendingSpace = false
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun normalize(lines: List<String>): NormalizedLines {
        val kept = ArrayList<String>(lines.size)
        val numbers = IntArray(lines.size)
        for ((index, raw) in lines.withIndex()) {
            val normalized = normalizeLine(raw)
            if (normalized.isEmpty()) continue
            numbers[kept.size] = index + 1
            kept.add(normalized)
        }
        return NormalizedLines(kept, numbers.copyOf(kept.size))
    }
}
