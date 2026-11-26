package io.github.driflux.jnote.relocate

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.gutter.GutterNoteService
import io.github.driflux.jnote.gutter.NoteLineMarkerProvider
import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 파일을 열 때 검증의 비용 측정. 시간은 기계마다 달라 단정하지 않고 출력만 하며, JNOTE_PERF=1일 때만 돈다.
 * 실행: JNOTE_PERF=1 ./gradlew test --tests '*OpenValidationCostTest*' -i | grep JNOTE-PERF
 */
class OpenValidationCostTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val created = mutableListOf<VirtualFile>()
    private val dbService get() = DatabaseService.getInstance()
    private val gutter get() = project.service<GutterNoteService>()
    private val validation get() = project.service<OpenValidationService>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-open-validation-cost").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
    }

    override fun tearDown() {
        try {
            try {
                awaitUntil("loads and validations drained") { gutter.isIdle && validation.isIdle }
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                awaitUntil("loads and validations drained") { gutter.isIdle && validation.isIdle }
                runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
            } finally {
                dbService.switchTo(DatabasePaths.default())
                dir.toFile().deleteRecursively()
            }
        } finally {
            super.tearDown()
        }
    }

    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: $message" }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun write(name: String, text: String): VirtualFile {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!.also { created += it }
    }

    private fun loadCache(file: VirtualFile, relPath: String) {
        gutter.notesFor(file, relPath)
        awaitUntil("cache loaded for $relPath") { gutter.notesFor(file, relPath) != null }
    }

    private fun elementsOf(file: VirtualFile): List<PsiElement> = runReadAction {
        SyntaxTraverser.psiTraverser(PsiManager.getInstance(project).findFile(file)!!).toList()
    }

    private fun pass(elements: List<PsiElement>): Int {
        val sink = mutableListOf<LineMarkerInfo<*>>()
        runReadAction { NoteLineMarkerProvider().collectSlowLineMarkers(elements, sink) }
        return sink.size
    }

    private fun measure(rounds: Int, repeats: Int, block: () -> Unit): String {
        repeat(repeats) { block() }
        val perRound = (1..rounds).map {
            val start = System.nanoTime()
            repeat(repeats) { block() }
            (System.nanoTime() - start) / 1e6 / repeats
        }
        return summary(perRound) + " x $repeats"
    }

    private fun summary(samples: List<Double>): String {
        val sorted = samples.sorted()
        return "median %.4f ms, min %.4f, max %.4f (%d runs)".format(sorted[sorted.size / 2], sorted.first(), sorted.last(), sorted.size)
    }

    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1e6

    private fun line(n: Int, variable: String = "x") = "    fun f$n($variable: Int) = $variable + $n"

    fun testOpenValidationCost() {
        if (System.getenv("JNOTE_PERF") != "1") return
        val key = ApplicationManager.getApplication().executeOnPooledThread<String> { project.service<ProjectKeyService>().resolve().key }.get()
        val repo = NoteRepository(dbService.database)
        // 5,000줄 그대로라 유사도 검색까지 켜진다
        val text = (1..5_000).joinToString("\n") { line(it) }
        val starts = (0 until 20).map { 100 + it * 240 }

        // 노트가 없는 파일은 에디터가 열려 있어도 검증 판단에 닿지 않는다
        val emptyFile = write("CostOpenEmpty.kt", text)
        myFixture.openFileInEditor(emptyFile)
        loadCache(emptyFile, "src/CostOpenEmpty.kt")
        assertEquals(0, pass(elementsOf(emptyFile)))
        assertNull(myFixture.editor.getUserData(OpenValidationService.VALIDATED))

        // 노트가 있는 파일: 첫 패스가 표시하고 검증을 한 번 예약한다. 이후 패스는 표시만 확인한다
        val notesFile = write("CostOpen.kt", text)
        for (start in starts) {
            val snapshot = (start..start + 3).joinToString("\n") { line(it) }
            repo.create(NewNote.code(key, "src/CostOpen.kt", start, start + 3, "note $start", snapshot = snapshot)).get()
        }
        myFixture.openFileInEditor(notesFile)
        val document = myFixture.editor.document
        loadCache(notesFile, "src/CostOpen.kt")
        val noteElements = elementsOf(notesFile)
        assertEquals(20, pass(noteElements))
        awaitUntil("first validation finished") { validation.isIdle }
        assertEquals(true, myFixture.editor.getUserData(OpenValidationService.VALIDATED))

        val decide = { runReadAction { validation.validateOncePerOpen(document, "src/CostOpen.kt") } }
        println("JNOTE-PERF in-pass decision, 1 editor open: " + measure(9, 2_000, decide))
        val factory = EditorFactory.getInstance()
        val others = (1..50).map { factory.createEditor(factory.createDocument("other $it"), project) }
        try {
            println("JNOTE-PERF in-pass decision, 51 editors open: " + measure(9, 2_000, decide))
        } finally {
            others.forEach { factory.releaseEditor(it) }
        }
        assertTrue(validation.isIdle)
        println("JNOTE-PERF whole marker pass, ${noteElements.size} elements, 20 notes: " + measure(9, 50) { pass(noteElements) })

        measureValidation(repo, key, starts, text)
    }

    private class Scenario(val label: String, val relPath: String, val document: Document, val displaced: List<AnchorUpdate>, val expected: NoteStatus) {
        val total = mutableListOf<Double>()
        val read = mutableListOf<Double>()
        val compute = mutableListOf<Double>()
        val write = mutableListOf<Double>()
    }

    private fun measureValidation(repo: NoteRepository, key: String, starts: List<Int>, text: String) {
        val scenarios = listOf("exact" to "x", "similar" to "y").map { (label, variable) ->
            val relPath = "src/CostValidate_$label.kt"
            val file = write(relPath.removePrefix("src/"), text)
            val displaced = starts.map { start ->
                val snapshot = (start..start + 3).joinToString("\n") { line(it, variable) }
                val note = repo.create(NewNote.code(key, relPath, start - 7, start - 4, "note $start", snapshot = snapshot)).get()
                AnchorUpdate(note.uid, relPath, start - 7, start - 4, NoteStatus.OK)
            }
            val document = runReadAction { FileDocumentManager.getInstance().getDocument(file)!! }
            Scenario(label, relPath, document, displaced, if (label == "exact") NoteStatus.OK else NoteStatus.APPROX)
        }

        val warmup = 3
        val rounds = 15
        for (round in 0 until warmup + rounds) {
            // 두 경우를 번갈아 돌려 예열과 잡음이 한쪽에만 몰리지 않게 한다
            for (s in scenarios) {
                repo.updateAnchors(s.displaced).get()
                var start = System.nanoTime()
                val changed = validation.validate(s.document, s.relPath).get(30, TimeUnit.SECONDS)
                val total = elapsedMs(start)
                assertEquals(20, changed)

                // 같은 일을 단계별로 나눠 재서 무엇이 시간을 차지하는지 본다
                repo.updateAnchors(s.displaced).get()
                start = System.nanoTime()
                val notes = repo.listByPath(key, s.relPath).get()
                val read = elapsedMs(start)
                start = System.nanoTime()
                val lines = runReadAction { s.document.text }.lines()
                val updates = FileRelocation.relocate(notes, lines)!!
                val compute = elapsedMs(start)
                start = System.nanoTime()
                assertEquals(20, repo.updateAnchors(updates).get())
                val write = elapsedMs(start)

                if (round >= warmup) {
                    s.total += total
                    s.read += read
                    s.compute += compute
                    s.write += write
                }
            }
        }

        for (s in scenarios) {
            val found = repo.listByPath(key, s.relPath).get()
            assertEquals(List(20) { s.expected }, found.map { it.status })
            assertEquals(starts, found.map { it.startLine })
            println("JNOTE-PERF validation 5000 lines, 20 notes, ${s.label}: total " + summary(s.total))
            println("JNOTE-PERF   ${s.label} DB read " + summary(s.read))
            println("JNOTE-PERF   ${s.label} text + relocate " + summary(s.compute))
            println("JNOTE-PERF   ${s.label} DB write " + summary(s.write))
        }
    }
}
