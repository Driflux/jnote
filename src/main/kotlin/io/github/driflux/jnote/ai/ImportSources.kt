package io.github.driflux.jnote.ai

/** 가져올 문서 하나. [read]는 파일을 읽을 수 있으므로 백그라운드에서만 부른다. [name]은 알림에 쓰는 이름이다. */
class ImportSource(val name: String, val read: () -> String)

sealed interface ImportProblem {
    val document: String

    data class Unreadable(override val document: String, val reason: String) : ImportProblem
    data class Rejected(override val document: String, val error: DocumentError) : ImportProblem
    data class Skipped(override val document: String, val item: SkippedItem) : ImportProblem

    /** 서로 다른 문서에 같은 uid가 있어 모두 건너뛴 경우. [document]는 그 문서들의 이름을 이은 것이다. */
    data class DuplicateAcrossDocuments(override val document: String, val uid: String) : ImportProblem
}

/** 읽어서 형식 검사까지 마친 결과. 계획과 반영은 [NoteImporter]가 한다. */
data class PreparedImport(val notes: List<FormatNote>, val problems: List<ImportProblem>)

internal object ImportPreparation {
    /** 문서마다 읽고 해석한다. 같은 uid가 여러 문서에 있으면 어느 것이 맞는지 알 수 없으므로 모두 건너뛴다. */
    fun prepare(sources: List<ImportSource>): PreparedImport {
        val problems = mutableListOf<ImportProblem>()
        val decoded = mutableListOf<Pair<String, FormatNote>>()
        for (source in sources) {
            val text = try {
                source.read()
            } catch (e: Exception) {
                problems += ImportProblem.Unreadable(source.name, e.message ?: e.javaClass.simpleName)
                continue
            }
            when (val result = NoteFormatCodec.decode(text)) {
                is DecodeResult.Rejected -> problems += ImportProblem.Rejected(source.name, result.error)
                is DecodeResult.Decoded -> {
                    problems += result.skipped.map { ImportProblem.Skipped(source.name, it) }
                    decoded += result.notes.map { source.name to it }
                }
            }
        }
        val documentsByUid = decoded.filter { it.second.uid != null }.groupBy({ it.second.uid!! }, { it.first })
        val shared = documentsByUid.filterValues { it.distinct().size > 1 }
        problems += shared.map { (uid, documents) -> ImportProblem.DuplicateAcrossDocuments(documents.distinct().joinToString(", "), uid) }
        return PreparedImport(decoded.map { it.second }.filter { it.uid !in shared }, problems)
    }
}
