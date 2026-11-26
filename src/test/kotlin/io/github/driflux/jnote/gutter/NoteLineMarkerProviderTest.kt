package io.github.driflux.jnote.gutter

import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NoteSelectRequested
import io.github.driflux.jnote.NoteSelectRequestedListener
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.project.ProjectKeyService
import io.github.driflux.jnote.ui.PendingNoteSelection
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel

class NoteLineMarkerProviderTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val created = mutableListOf<VirtualFile>()
    private val services = mutableListOf<GutterNoteService>()
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)
    private val gutter get() = project.service<GutterNoteService>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-gutter").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
    }

    override fun tearDown() {
        try {
            try {
                // 늦은 읽기가 DB 경로를 바꾼 뒤 기본 경로(홈 디렉터리)의 DB를 열지 않도록 먼저 끝낸다
                settle()
                runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
                project.service<PendingNoteSelection>().take()
            } finally {
                dbService.switchTo(DatabasePaths.default())
                dir.toFile().deleteRecursively()
            }
        } finally {
            super.tearDown()
        }
    }

    private fun projectKey(): String =
        ApplicationManager.getApplication().executeOnPooledThread<String> { project.service<ProjectKeyService>().resolve().key }.get()

    // 다른 테스트의 파일 이름과 겹쳐 캐시 항목을 나누지 않도록 이름 앞에 Gutter를 붙인다
    private fun openFile(name: String, text: String): VirtualFile {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        created += vFile
        myFixture.openFileInEditor(vFile)
        return vFile
    }

    private fun lines(count: Int) = (1..count).joinToString("\n") { "    line $it" }

    private fun note(name: String, start: Int, end: Int, title: String, status: NoteStatus = NoteStatus.OK) =
        repo.create(NewNote.code(projectKey(), "src/$name", start, end, title, status = status)).get()

    /** JUnit3 러너가 test로 시작하는 메서드 안의 람다를 테스트로 오인하므로 람다는 도우미 함수에서 만든다. */
    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: $message" }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    /** 진행 중인 읽기가 끝나고, 그 끝에 EDT로 넘긴 데몬 재시작과 알림까지 처리된 상태로 만든다. */
    private fun settle() {
        // EDT에서 처리한 일이 새 읽기를 시작할 수 있으므로, 이벤트를 비운 뒤에도 읽기가 없을 때까지 되풀이한다
        while (true) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if ((services + gutter).all { it.isIdle }) return
            awaitUntil("gutter loads drained") { (services + gutter).all { it.isIdle } }
        }
    }

    private fun markers(): List<LineMarkerInfo<*>> {
        myFixture.doHighlighting()
        return DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
            .filter { it.icon === GutterIcons.NOTE || it.icon === GutterIcons.APPROX }
    }

    /**
     * 데몬 없이 제공자를 직접 불러 한 번의 패스를 흉내 낸다. 테스트 픽스처의 하이라이팅은 기다리는 동안 EDT 이벤트를
     * 처리하므로, 그 안에서 읽기가 끝나면 데몬 재시작이 하이라이팅 도중에 실행되어 픽스처가 막는다.
     */
    private fun directPass(): List<LineMarkerInfo<*>> {
        val sink = mutableListOf<LineMarkerInfo<*>>()
        runReadAction {
            val psi = PsiDocumentManager.getInstance(project).getPsiFile(myFixture.editor.document)!!
            NoteLineMarkerProvider().collectSlowLineMarkers(SyntaxTraverser.psiTraverser(psi).toList(), sink)
        }
        return sink
    }

    private fun loadedMarkers(): List<LineMarkerInfo<*>> {
        assertTrue(directPass().isEmpty())
        settle()
        return markers()
    }

    private fun lineOf(info: LineMarkerInfo<*>) = myFixture.editor.document.getLineNumber(info.startOffset) + 1

    private fun isDirty(file: VirtualFile): Boolean {
        val daemon = DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl
        val psiFile = runReadAction { PsiManager.getInstance(project).findFile(file)!! }
        return daemon.fileStatusMap.getFileDirtyScope(myFixture.editor.document, psiFile, Pass.UPDATE_ALL) != null
    }

    private fun captureNotifications(): List<Notification> {
        val shown = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            },
        )
        return shown
    }

    private fun click(info: LineMarkerInfo<*>) {
        @Suppress("UNCHECKED_CAST")
        val handler = info.navigationHandler as GutterIconNavigationHandler<PsiElement>
        handler.navigate(MouseEvent(JPanel(), MouseEvent.MOUSE_CLICKED, 0L, 0, 0, 0, 1, false), info.element!!)
    }

    private fun recordSelectionRequests(): List<Long> {
        val requested = mutableListOf<Long>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(NoteSelectRequested.TOPIC, NoteSelectRequestedListener { p, id -> if (p === project) requested += id })
        return requested
    }

    fun testAnOkNoteInAPlainTextFileGetsOneMarkerOnItsStartLine() {
        openFile("GutterPlain.txt", lines(10))
        note("GutterPlain.txt", 3, 5, "check this")

        val found = loadedMarkers()

        assertEquals(listOf(3), found.map { lineOf(it) })
        assertSame(GutterIcons.NOTE, found.single().icon)
        assertEquals("check this", found.single().lineMarkerTooltip)
    }

    fun testAKotlinFileGetsTheMarkerFromTheSameProvider() {
        val file = openFile("GutterK.kt", "package p\n\nclass A {\n    fun b() = 1\n}\n")
        note("GutterK.kt", 4, 4, "kotlin note")
        // Kotlin은 소스 폴더 밖 파일을 하이라이팅하지 않으므로 이 테스트 동안만 소스 폴더로 등록한다
        val sourceRoot = file.parent
        PsiTestUtil.addSourceRoot(module, sourceRoot)
        val found = try {
            loadedMarkers()
        } finally {
            PsiTestUtil.removeSourceRoot(module, sourceRoot)
        }

        assertEquals(listOf(4), found.map { lineOf(it) })
        // 마커는 줄의 첫 글자 위치에 놓인다
        assertEquals("fun", myFixture.editor.document.text.substring(found.single().startOffset, found.single().startOffset + 3))
    }

    fun testAnApproximateNoteUsesADifferentIcon() {
        openFile("GutterA.txt", lines(10))
        note("GutterA.txt", 2, 2, "approx", NoteStatus.APPROX)

        val found = loadedMarkers()

        assertEquals(listOf(2), found.map { lineOf(it) })
        assertSame(GutterIcons.APPROX, found.single().icon)
    }

    fun testOrphanAndNeedsRelocateNotesDrawNoMarker() {
        val file = openFile("GutterA.txt", lines(10))
        note("GutterA.txt", 2, 2, "orphan", NoteStatus.ORPHAN)
        note("GutterA.txt", 4, 4, "lost", NoteStatus.NEEDS_RELOCATE)

        val found = loadedMarkers()

        // 읽기는 끝났고 두 노트가 캐시에 있으므로, 마커가 없는 것은 상태 때문이다
        assertEquals(2, gutter.notesFor(file, "src/GutterA.txt")!!.size)
        assertTrue(found.isEmpty())
    }

    fun testOnlyTheNotesOfThisFileAreShown() {
        openFile("GutterA.txt", lines(10))
        note("GutterA.txt", 6, 6, "mine")
        note("GutterOther.txt", 2, 2, "elsewhere")

        assertEquals(listOf(6), loadedMarkers().map { lineOf(it) })
    }

    fun testANoteBeyondTheEndOfTheFileDrawsNothing() {
        val file = openFile("GutterA.txt", lines(3))
        note("GutterA.txt", 9, 9, "past the end")

        val found = loadedMarkers()

        assertEquals(1, gutter.notesFor(file, "src/GutterA.txt")!!.size)
        assertTrue(found.isEmpty())
    }

    fun testTheTooltipEscapesTheTitle() {
        openFile("GutterA.txt", lines(5))
        note("GutterA.txt", 2, 2, "<b>bold</b> & co")

        assertEquals(StringUtil.escapeXmlEntities("<b>bold</b> & co"), loadedMarkers().single().lineMarkerTooltip)
    }

    private fun dirtyAfterFirstLoad(file: VirtualFile, relPath: String): Boolean {
        markers()
        assertFalse("file should be clean before the load", isDirty(file))
        val fresh = GutterNoteService(project).also {
            Disposer.register(testRootDisposable, it)
            services += it
        }

        assertNull(fresh.notesFor(file, relPath))
        settle()

        assertNotNull(fresh.notesFor(file, relPath))
        return isDirty(file)
    }

    fun testFinishingTheFirstLoadRestartsTheDaemonForThatFile() {
        val file = openFile("GutterA.txt", lines(10))
        note("GutterA.txt", 3, 3, "late")
        loadedMarkers()

        assertTrue(dirtyAfterFirstLoad(file, "src/GutterA.txt"))
    }

    fun testAFileWithoutNotesDoesNotRestartTheDaemon() {
        val file = openFile("GutterEmpty.txt", lines(10))
        note("GutterOther.txt", 2, 2, "elsewhere")
        loadedMarkers()

        assertFalse(dirtyAfterFirstLoad(file, "src/GutterEmpty.txt"))
    }

    fun testNotesChangedRefreshesTheMarkers() {
        openFile("GutterA.txt", lines(10))
        note("GutterA.txt", 3, 3, "first")
        assertEquals(listOf(3), loadedMarkers().map { lineOf(it) })

        note("GutterA.txt", 7, 7, "second")
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
        settle()

        assertEquals(listOf(3, 7), markers().map { lineOf(it) })
    }

    fun testSwitchingTheDatabaseDiscardsTheCache() {
        val file = openFile("GutterA.txt", lines(10))
        note("GutterA.txt", 3, 3, "old database")
        assertEquals(listOf(3), loadedMarkers().map { lineOf(it) })

        val other = Files.createTempDirectory(dir, "other").resolve("jnote.db")
        dbService.switchTo(other)
        note("GutterA.txt", 5, 5, "new database")

        // 바뀐 뒤 첫 패스는 옛 DB의 노트를 그리지 않는다
        assertEquals(listOf(5), loadedMarkers().map { lineOf(it) })
        assertEquals(listOf("new database"), gutter.notesFor(file, "src/GutterA.txt")!!.map { it.title })
    }

    fun testALoadFailureIsReportedOnceWithEscapedText() {
        val shown = captureNotifications()
        val broken = dir.resolve("broken.db")
        Files.writeString(broken, "this is not a database file, it is long enough to have a header")
        dbService.switchTo(broken)
        val second = openFile("GutterB.txt", lines(5))
        openFile("Gutter<A>.txt", lines(5))
        directPass()
        settle()

        // 다른 파일이 같은 이유로 실패해도, 같은 파일을 다시 그려도 알림은 늘지 않는다
        myFixture.openFileInEditor(second)
        directPass()
        settle()
        directPass()
        settle()

        val failures = shown.filter { it.title == JNoteBundle.message("gutter.error.load.title") }
        assertEquals(1, failures.size)
        assertTrue(failures.single().content, failures.single().content.contains("Gutter&lt;A&gt;.txt"))
    }

    fun testClickingAMarkerSelectsTheNote() {
        openFile("GutterA.txt", lines(10))
        val saved = note("GutterA.txt", 4, 4, "click me")
        val requested = recordSelectionRequests()

        click(loadedMarkers().single())

        assertEquals(listOf(saved.id), requested)
        assertEquals(saved.id, project.service<PendingNoteSelection>().peek())
    }

    fun testSeveralNotesOnOneLineShareAMarker() {
        openFile("GutterA.txt", lines(10))
        note("GutterA.txt", 4, 4, "first")
        note("GutterA.txt", 4, 6, "second")

        val found = loadedMarkers()

        assertEquals(listOf(4), found.map { lineOf(it) })
        assertEquals("first<br>second", found.single().lineMarkerTooltip)
    }

    fun testNotesWithTagsAreCachedWithWhatThePreviewNeeds() {
        val file = openFile("GutterA.txt", lines(10))
        val saved = repo.create(
            NewNote.code(projectKey(), "src/GutterA.txt", 2, 8, "tagged", body = "why", snapshot = "l1\nl2", aiSource = "Claude"),
        ).get()
        val tags = TagRepository(dbService.database)
        tags.attach(saved.id, tags.getOrCreate("sql").get().id).get()
        loadedMarkers()

        val cached = gutter.notesFor(file, "src/GutterA.txt")!!.single()

        assertEquals(listOf("sql"), cached.tags)
        assertEquals("why", cached.body)
        assertEquals("Claude", cached.aiSource)
        assertEquals("l1\nl2", cached.snippetHead)
        assertEquals(2..8, cached.startLine..cached.endLine)
    }

    fun testOpeningAFileDoesNotCreateTheDatabaseWhenNoneExists() {
        dbService.switchTo(dir.resolve("never-created.db"))
        val file = openFile("GutterA.txt", lines(5))

        loadedMarkers()

        assertEquals(emptyList<CachedNote>(), gutter.notesFor(file, "src/GutterA.txt"))
        assertFalse(Files.exists(dir.resolve("never-created.db")))
        assertNull(dbService.openDatabaseOrNull())
    }

    fun testALoadThatFinishesAfterDisposeDoesNothing() {
        val file = openFile("GutterA.txt", lines(10))
        note("GutterA.txt", 3, 3, "late")
        loadedMarkers()
        val parent = Disposer.newDisposable()
        val fresh = GutterNoteService(project).also {
            Disposer.register(parent, it)
            services += it
        }

        assertNull(fresh.notesFor(file, "src/GutterA.txt"))
        Disposer.dispose(parent)
        settle()

        assertFalse(isDirty(file))
        assertNull(fresh.notesFor(file, "src/GutterA.txt"))
    }
}
