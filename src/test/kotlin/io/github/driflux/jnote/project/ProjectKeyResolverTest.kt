package io.github.driflux.jnote.project

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ProjectKeyResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root: Path get() = tmp.root.toPath().toRealPath()

    private fun mainRepo(origin: String?): Path {
        val repo = Files.createDirectories(root.resolve("main"))
        val git = Files.createDirectories(repo.resolve(".git"))
        if (origin != null) Files.writeString(git.resolve("config"), "[remote \"origin\"]\n\turl = $origin\n")
        return repo
    }

    private fun linkedWorktree(main: Path, commondir: String): Path {
        val gitdir = Files.createDirectories(main.resolve(".git/worktrees/feature"))
        Files.writeString(gitdir.resolve("commondir"), commondir)
        Files.writeString(gitdir.resolve("HEAD"), "ref: refs/heads/feature\n")
        val wt = Files.createDirectories(root.resolve("wt"))
        Files.writeString(wt.resolve(".git"), "gitdir: $gitdir\n")
        return wt
    }

    private val expected = ProjectKey("github.com/org/repo", "repo")

    @Test fun plainRepo() {
        assertEquals(expected, ProjectKeyResolver.resolve(mainRepo("git@github.com:org/repo.git")))
    }

    @Test fun subdirectoryOfRepo() {
        val sub = Files.createDirectories(mainRepo("https://github.com/org/repo.git").resolve("a/b"))
        assertEquals(expected, ProjectKeyResolver.resolve(sub))
    }

    @Test fun linkedWorktreeWithRelativeCommondir() {
        val main = mainRepo("https://github.com/org/repo.git")
        assertEquals(expected, ProjectKeyResolver.resolve(linkedWorktree(main, "../..\n")))
    }

    @Test fun linkedWorktreeWithAbsoluteCommondir() {
        val main = mainRepo("https://github.com/org/repo.git")
        assertEquals(expected, ProjectKeyResolver.resolve(linkedWorktree(main, "${main.resolve(".git")}\n")))
    }

    @Test fun worktreeAndMainShareKey() {
        val main = mainRepo("ssh://git@github.com:2222/org/repo.git")
        assertEquals(ProjectKeyResolver.resolve(main), ProjectKeyResolver.resolve(linkedWorktree(main, "../..\n")))
    }

    @Test fun nonGitDirectoryFallsBackToPath() {
        val dir = Files.createDirectories(root.resolve("not-git/project-x"))
        assertEquals(ProjectKey(dir.toString(), "project-x"), ProjectKeyResolver.resolve(dir))
    }

    @Test fun repoWithoutOriginFallsBackToPath() {
        val repo = mainRepo(null)
        assertEquals(ProjectKey(repo.toString(), "main"), ProjectKeyResolver.resolve(repo))
    }

    @Test fun garbageConfigFallsBackToPath() {
        val repo = mainRepo(null)
        Files.write(repo.resolve(".git/config"), byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00))
        assertEquals(ProjectKey(repo.toString(), "main"), ProjectKeyResolver.resolve(repo))
    }

    @Test fun unnormalizableOriginFallsBackToPath() {
        val repo = mainRepo("/srv/local/repo.git")
        assertEquals(ProjectKey(repo.toString(), "main"), ProjectKeyResolver.resolve(repo))
    }
}
