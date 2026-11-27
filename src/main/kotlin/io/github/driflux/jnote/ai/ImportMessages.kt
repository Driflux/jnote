package io.github.driflux.jnote.ai

import io.github.driflux.jnote.JNoteBundle

internal object ImportMessages {
    private const val MAX_PROBLEMS = 10

    fun summary(plan: ImportPlan, skipped: Int): String =
        JNoteBundle.message("dialog.importPreview.summary", plan.creates.size, plan.updates.size, plan.unchanged.size, skipped)

    fun rows(plan: ImportPlan): List<String> =
        plan.creates.map { JNoteBundle.message("dialog.importPreview.row.new", it.note.title) } +
            plan.updates.map { update ->
                val key = when {
                    update.emptiedBody && update.emptiedTags -> "dialog.importPreview.row.updated.clearsBoth"
                    update.emptiedBody -> "dialog.importPreview.row.updated.clearsBody"
                    update.emptiedTags -> "dialog.importPreview.row.updated.clearsTags"
                    else -> "dialog.importPreview.row.updated"
                }
                JNoteBundle.message(key, update.note.title)
            }

    fun describe(problem: ImportProblem): String = when (problem) {
        is ImportProblem.Unreadable -> JNoteBundle.message("import.problem.unreadable", problem.document, problem.reason)
        is ImportProblem.Rejected -> JNoteBundle.message("import.document.${problem.error.name}", problem.document)
        is ImportProblem.Skipped -> {
            val reason = JNoteBundle.message("import.problem.${problem.item.problem.name}")
            val uid = problem.item.uid
            if (uid == null) {
                JNoteBundle.message("import.problem.item", problem.document, problem.item.index + 1, problem.item.field, reason)
            } else {
                JNoteBundle.message("import.problem.itemWithUid", problem.document, problem.item.index + 1, uid, problem.item.field, reason)
            }
        }
        is ImportProblem.DuplicateAcrossDocuments -> JNoteBundle.message("import.problem.sharedUid", problem.uid, problem.document)
    }

    fun problemList(problems: List<ImportProblem>): String {
        if (problems.isEmpty()) return ""
        val shown = problems.take(MAX_PROBLEMS).map { escape(describe(it)) }
        val more = problems.size - shown.size
        val lines = listOf(JNoteBundle.message("notification.import.skipped", problems.size)) + shown +
            (if (more > 0) listOf(JNoteBundle.message("notification.import.skipped.more", more)) else emptyList())
        return lines.joinToString("<br>")
    }

    fun escape(text: String): String = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text)
}
