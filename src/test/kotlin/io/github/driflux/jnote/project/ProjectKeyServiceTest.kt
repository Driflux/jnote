package io.github.driflux.jnote.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotEquals
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ProjectKeyServiceTest : BasePlatformTestCase() {
    private fun <T> offEdt(block: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { block() }.get()

    fun testResolvesOnceAndCaches() {
        val calls = AtomicInteger()
        val cache = CachedProjectKey("/some/path/repo", "fallback") {
            calls.incrementAndGet()
            ProjectKey("host/org/repo", "repo")
        }
        assertEquals(ProjectKey("host/org/repo", "repo"), cache.get())
        cache.get()
        assertEquals(1, calls.get())
    }

    fun testCacheDoesNotRereadConfigAfterFirstCall() {
        val repo = Files.createTempDirectory("jnote-key")
        try {
            val git = Files.createDirectories(repo.resolve(".git"))
            Files.writeString(git.resolve("config"), "[remote \"origin\"]\n\turl = git@github.com:org/repo.git\n")
            val cache = CachedProjectKey(repo.toString(), "fallback", ProjectKeyResolver::resolve)
            assertEquals(ProjectKey("github.com/org/repo", "repo"), cache.get())
            Files.delete(git.resolve("config"))
            assertEquals(ProjectKey("github.com/org/repo", "repo"), cache.get())
        } finally {
            repo.toFile().deleteRecursively()
        }
    }

    fun testMissingBasePathFallsBackToName() {
        val cache = CachedProjectKey(null, "default") { error("must not resolve") }
        assertEquals(ProjectKey("default", "default"), cache.get())
    }

    fun testServiceFallsBackForTestProject() {
        val key = offEdt { project.service<ProjectKeyService>().resolve() }
        assertEquals(project.basePath, key.key)
        assertNotEquals("", key.displayName)
    }

    fun testServiceReturnsSameInstanceValueOnRepeatedCalls() {
        val service = project.service<ProjectKeyService>()
        assertEquals(offEdt { service.resolve() }, offEdt { service.resolve() })
    }

    fun testCallOnEdtFailsFast() {
        val service = project.service<ProjectKeyService>()
        assertTrue(ApplicationManager.getApplication().isDispatchThread)
        assertThrows(IllegalStateException::class.java) { service.resolve() }
    }
}
