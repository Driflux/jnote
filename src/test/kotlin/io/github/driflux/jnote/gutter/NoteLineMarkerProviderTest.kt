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
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.UiInterceptors
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
import io.github.driflux.jnote.relocate.OpenValidationService
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
    private val validation get() = project.service<OpenValidationService>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-gutter").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
    }

    override fun tearDown() {
        try {
            try {
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

    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: $message" }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun settle() {
        while (true) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if ((services + gutter).all { it.isIdle } && validation.isIdle) return
            awaitUntil("gutter loads drained") { (services + gutter).all { it.isIdle } && validation.isIdle }
        }
    }

    private fun markers(): List<LineMarkerInfo<*>> {
        myFixture.doHighlighting()
        return DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
            .filter { it.icon === GutterIcons.NOTE || it.icon === GutterIcons.APPROX }
    }

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
        directPass()
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

    fun testClickingAnIconWhileAPlusDragIsInProgressSelectsNothing() {
        openFile("GutterDragGuard.txt", lines(10))
        note("GutterDragGuard.txt", 3, 5, "guarded")
        val info = loadedMarkers().single()
        val requested = recordSelectionRequests()
        val gutterComponent = JPanel()
        GutterDragging.mark(MouseEvent(gutterComponent, MouseEvent.MOUSE_PRESSED, 0L, 0, 0, 0, 1, false), true)

        @Suppress("UNCHECKED_CAST")
        val handler = info.navigationHandler as GutterIconNavigationHandler<PsiElement>
        handler.navigate(MouseEvent(gutterComponent, MouseEvent.MOUSE_RELEASED, 0L, 0, 0, 0, 1, false), info.element!!)

        assertTrue(requested.isEmpty())
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
        val sourceRoot = file.parent
        PsiTestUtil.addSourceRoot(module, sourceRoot)
        val found = try {
            loadedMarkers()
        } finally {
            PsiTestUtil.removeSourceRoot(module, sourceRoot)
        }

        assertEquals(listOf(4), found.map { lineOf(it) })
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

    private fun interceptPopups(): List<ListPopup> {
        val shown = mutableListOf<ListPopup>()
        UiInterceptors.register(
            object : UiInterceptors.UiInterceptor<ListPopup>(ListPopup::class.java) {
                override fun doIntercept(component: ListPopup) {
                    shown += component
                }
            },
        )
        return shown
    }

    fun testClickingAMarkerWithSeveralNotesOpensAChooserThatSelectsTheChosenNote() {
        openFile("GutterA.txt", lines(10))
        note("GutterA.txt", 4, 4, "first")
        val second = note("GutterA.txt", 4, 6, "second", NoteStatus.APPROX)
        val requested = recordSelectionRequests()
        val popups = interceptPopups()

        click(loadedMarkers().single())

        @Suppress("UNCHECKED_CAST")
        val step = popups.single().listStep as ListPopupStep<CachedNote>
        assertEquals(listOf("first", "second"), step.values.map { step.getTextFor(it) })
        assertSame(GutterIcons.APPROX, step.getIconFor(step.values[1]))
        assertEquals(emptyList<Long>(), requested)

        assertSame(PopupStep.FINAL_CHOICE, step.onChosen(step.values[1], true))
        step.finalRunnable!!.run()

        assertEquals(listOf(second.id), requested)
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