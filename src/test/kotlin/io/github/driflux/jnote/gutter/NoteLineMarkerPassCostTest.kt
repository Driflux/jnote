package io.github.driflux.jnote.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
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
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path

/**
 * 마커 패스 비용 측정. 시간은 기계마다 달라 단정하지 않고 출력만 하며, JNOTE_PERF=1일 때만 돈다.
 * 실행: JNOTE_PERF=1 ./gradlew test --tests '*NoteLineMarkerPassCostTest*' -i | grep JNOTE-PERF
 */
class NoteLineMarkerPassCostTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val created = mutableListOf<VirtualFile>()
    private val dbService get() = DatabaseService.getInstance()
    private val gutter get() = project.service<GutterNoteService>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-gutter-cost").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
    }

    override fun tearDown() {
        try {
            try {
                awaitUntil("gutter loads drained") { gutter.isIdle }
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
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

    private fun elementsOf(file: VirtualFile): List<PsiElement> = runReadAction {
        SyntaxTraverser.psiTraverser(PsiManager.getInstance(project).findFile(file)!!).toList()
    }

    private fun pass(elements: List<PsiElement>): Int {
        val sink = mutableListOf<LineMarkerInfo<*>>()
        runReadAction { NoteLineMarkerProvider().collectSlowLineMarkers(elements, sink) }
        return sink.size
    }

    // 제공자가 피할 수 없는 바닥: 모든 요소에서 잎인지 보고 잎의 범위를 읽는다
    private fun scanFloor(elements: List<PsiElement>): Int = runReadAction {
        var leaves = 0
        for (element in elements) if (element.firstChild == null && element.textRange.endOffset >= 0) leaves++
        leaves
    }

    /** 라운드마다 [repeats]번 돌린 평균(ms)을 모아 중앙값, 최소, 최대를 돌려준다. */
    private fun measure(rounds: Int, repeats: Int, block: () -> Int): String {
        repeat(repeats) { block() }
        val perRound = (1..rounds).map {
            val start = System.nanoTime()
            repeat(repeats) { block() }
            (System.nanoTime() - start) / 1e6 / repeats
        }.sorted()
        return "median %.4f ms, min %.4f, max %.4f (%d rounds x %d)".format(perRound[rounds / 2], perRound.first(), perRound.last(), rounds, repeats)
    }

    fun testMarkerPassCost() {
        if (System.getenv("JNOTE_PERF") != "1") return
        val key = ApplicationManager.getApplication().executeOnPooledThread<String> { project.service<ProjectKeyService>().resolve().key }.get()
        val repo = NoteRepository(dbService.database)
        val xml = "<root>\n" + (2 until 5_000).joinToString("\n") { "    <item id=\"$it\">value $it</item>" } + "\n</root>"
        val kotlin = "package p\n\nclass Big {\n" + (4 until 5_000).joinToString("\n") { "    fun f$it(x: Int) = x + $it" } + "\n}"
        for ((label, empty, withNotes, text) in listOf(
            listOf("xml", "CostEmpty.xml", "CostNotes.xml", xml),
            listOf("kotlin", "CostEmpty.kt", "CostNotes.kt", kotlin),
        )) {
            val emptyFile = write(empty, text)
            val notesFile = write(withNotes, text)
            repeat(20) { repo.create(NewNote.code(key, "src/$withNotes", 100 + it * 240, 103 + it * 240, "note $it")).get() }
            for ((file, name) in listOf(emptyFile to empty, notesFile to withNotes)) {
                gutter.notesFor(file, "src/$name")
                awaitUntil("cache loaded for $name") { gutter.notesFor(file, "src/$name") != null }
            }
            val emptyElements = elementsOf(emptyFile)
            val noteElements = elementsOf(notesFile)
            assertEquals(0, pass(emptyElements))
            assertEquals(20, pass(noteElements))

            println("JNOTE-PERF $label 5000 lines, ${emptyElements.size} elements, no notes: " + measure(9, 2_000) { pass(emptyElements) })
            println("JNOTE-PERF $label 5000 lines, ${noteElements.size} elements, 20 notes: " + measure(9, 50) { pass(noteElements) })
            println("JNOTE-PERF $label 5000 lines, leaf scan floor: " + measure(9, 50) { scanFloor(noteElements) })
        }
    }
}
