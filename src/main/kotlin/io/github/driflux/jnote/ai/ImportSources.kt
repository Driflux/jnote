package io.github.driflux.jnote.ai

import com.intellij.openapi.progress.ProcessCanceledException

class ImportSource(val name: String, val read: () -> String)

sealed interface ImportProblem {
    val document: String

    data class Unreadable(override val document: String, val reason: String) : ImportProblem
    data class Rejected(override val document: String, val error: DocumentError) : ImportProblem
    data class Skipped(override val document: String, val item: SkippedItem) : ImportProblem

    data class DuplicateAcrossDocuments(override val document: String, val uid: String) : ImportProblem
}

data class PreparedImport(val notes: List<FormatNote>, val problems: List<ImportProblem>)

internal object ImportPreparation {
    fun prepare(sources: List<ImportSource>, checkCanceled: () -> Unit = {}): PreparedImport {
        val problems = mutableListOf<ImportProblem>()
        val decoded = mutableListOf<Pair<Int, FormatNote>>()
        val uidsByDocument = mutableMapOf<String, MutableSet<Int>>()
        sources.forEachIndexed { index, source ->
            checkCanceled()
            val text = try {
                source.read()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                problems += ImportProblem.Unreadable(source.name, e.message ?: e.javaClass.simpleName)
                return@forEachIndexed
            }
            when (val result = NoteFormatCodec.decode(text)) {
                is DecodeResult.Rejected -> problems += ImportProblem.Rejected(source.name, result.error)
                is DecodeResult.Decoded -> {
                    problems += result.skipped.map { ImportProblem.Skipped(source.name, it) }
                    decoded += result.notes.map { index to it }
                    (result.notes.mapNotNull { it.uid } + result.skipped.mapNotNull { it.uid })
                        .forEach { uidsByDocument.getOrPut(it) { mutableSetOf() } += index }
                }
            }
        }
        val shared = uidsByDocument.filterValues { it.size > 1 }
        problems += shared.map { (uid, documents) ->
            ImportProblem.DuplicateAcrossDocuments(documents.sorted().joinToString(", ") { sources[it].name }, uid)
        }
        return PreparedImport(decoded.map { it.second }.filter { it.uid !in shared }, problems)
    }
}
