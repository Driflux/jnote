package io.github.driflux.jnote.anchor

/** 1-based, 끝 줄 포함 줄 범위. */
data class LineRange(val start: Int, val end: Int)

/** 선택 영역과 경로를 앵커 값으로 바꾸는 순수 계산. 에디터나 PSI에 의존하지 않는다. */
object AnchorLines {
    /** 스냅샷 글자 수 상한. 거대한 선택이 DB와 FTS 인덱스를 키우지 않게 한다. */
    const val MAX_SNAPSHOT_CHARS = 65_536

    /**
     * 두 끝점(0-based 줄, 열)으로 줄 범위를 만든다. 끝점이 앞서도 순서를 바로잡는다.
     * 끝이 줄의 0열이고 시작과 다른 줄이면 그 줄은 선택되지 않은 것이므로 뺀다.
     * 선택이 없으면 두 끝점이 같은 캐럿 위치이므로 캐럿 줄 하나가 된다.
     */
    fun ofSelection(lineA: Int, columnA: Int, lineB: Int, columnB: Int): LineRange {
        val aFirst = lineA < lineB || (lineA == lineB && columnA <= columnB)
        val startLine = if (aFirst) lineA else lineB
        val endLine = if (aFirst) lineB else lineA
        val endColumn = if (aFirst) columnB else columnA
        val lastLine = if (endColumn == 0 && endLine > startLine) endLine - 1 else endLine
        return LineRange(startLine + 1, lastLine + 1)
    }

    /** 프로젝트 루트 아래 파일의 `/` 구분 상대 경로. 루트 밖이거나 루트를 알 수 없으면 null. */
    fun relativePath(basePath: String?, filePath: String): String? {
        if (basePath == null) return null
        val base = basePath.replace('\\', '/').trimEnd('/')
        val file = filePath.replace('\\', '/')
        if (!file.startsWith("$base/")) return null
        return file.substring(base.length + 1).takeIf { it.isNotEmpty() }
    }

    /**
     * [start, end) 구간의 스냅샷. 상한을 넘는 선택을 통째로 복사하지 않도록 상한 + 1글자까지만 읽어
     * (EDT에서 불리므로) 상한 초과 여부를 판단할 만큼만 복사한다.
     */
    fun snapshotOf(text: CharSequence, start: Int, end: Int): String =
        capSnapshot(text.subSequence(start, minOf(end, start + MAX_SNAPSHOT_CHARS + 1)).toString())

    /**
     * 상한을 넘으면 상한 안에서 마지막 줄 경계까지만 남긴다. 줄 경계가 없으면 글자 단위로 자르되
     * 서로게이트 쌍을 가르지 않도록 끝에 남은 앞 절반은 버린다.
     */
    fun capSnapshot(text: String): String {
        if (text.length <= MAX_SNAPSHOT_CHARS) return text
        val cut = text.substring(0, MAX_SNAPSHOT_CHARS)
        val lineEnd = cut.lastIndexOf('\n')
        if (lineEnd > 0) return cut.substring(0, lineEnd)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }
}
