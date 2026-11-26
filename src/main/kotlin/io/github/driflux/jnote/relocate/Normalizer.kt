package io.github.driflux.jnote.relocate

internal class NormalizedLines(val lines: List<String>, val lineNumbers: IntArray) {
    val size: Int get() = lines.size
}

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
