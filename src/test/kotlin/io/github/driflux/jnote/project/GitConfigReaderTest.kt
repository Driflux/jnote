package io.github.driflux.jnote.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class GitConfigReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(vararg parts: String): Path {
        val p = parts.fold(tmp.root.toPath().toRealPath()) { acc, s -> acc.resolve(s) }
        return Files.createDirectories(p)
    }

    private fun originOf(text: String): String? {
        val d = dir("cfg-" + text.hashCode())
        Files.writeString(d.resolve("config"), text)
        return GitConfigReader.readOriginUrl(d)
    }

    @Test fun readsOrigin() {
        assertEquals("git@github.com:org/repo.git", originOf("[remote \"origin\"]\n\turl = git@github.com:org/repo.git\n"))
    }

    @Test fun skipsCommentsAndOddSpacing() {
        val text = """
            # leading comment
            ; another
            [core]
                bare = false
            [  remote   "origin"  ]   # trailing comment
               fetch=+refs/heads/*:refs/remotes/origin/*
               url=https://github.com/org/repo.git   ; inline comment
        """.trimIndent()
        assertEquals("https://github.com/org/repo.git", originOf(text))
    }

    @Test fun picksOriginAmongMultipleRemotes() {
        val text = """
            [remote "upstream"]
                url = https://github.com/other/repo.git
            [remote "origin"]
                url = https://github.com/me/repo.git
            [remote "backup"]
                url = https://example.com/backup.git
        """.trimIndent()
        assertEquals("https://github.com/me/repo.git", originOf(text))
    }

    @Test fun ignoresUrlInOtherSectionsAndRemotes() {
        val text = """
            [remote "upstream"]
                url = https://github.com/other/repo.git
            [url "git@github.com:"]
                insteadOf = gh:
            [remote "origin-mirror"]
                url = https://example.com/mirror.git
        """.trimIndent()
        assertNull(originOf(text))
    }

    @Test fun sectionNameIsCaseInsensitiveButSubsectionIsNot() {
        assertEquals("a/b", originOf("[REMOTE \"origin\"]\nURL = a/b\n"))
        assertNull(originOf("[remote \"Origin\"]\nurl = a/b\n"))
    }

    @Test fun quotedValueAndHashInsideQuotes() {
        assertEquals("https://h/x#y.git", originOf("[remote \"origin\"]\n url = \"https://h/x#y.git\"\n"))
    }

    @Test fun keyOnSameLineAsHeader() {
        assertEquals("https://h/o/r", originOf("[remote \"origin\"] url = https://h/o/r\n"))
    }

    @Test fun missingOriginReturnsNull() {
        assertNull(originOf("[core]\n\tbare = false\n"))
    }

    @Test fun garbageConfigReturnsNull() {
        assertNull(originOf("@@@ not = [a config\n]]]\n==\n"))
        assertNull(originOf("[remote \"origin\"\nurl\n"))
    }

    @Test fun invalidEncodingReturnsNull() {
        val d = dir("bad-encoding")
        Files.write(d.resolve("config"), byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte()))
        assertNull(GitConfigReader.readOriginUrl(d))
    }

    @Test fun missingOrUnreadableConfigReturnsNull() {
        assertNull(GitConfigReader.readOriginUrl(dir("empty")))
        val d = dir("config-is-dir")
        Files.createDirectories(d.resolve("config"))
        assertNull(GitConfigReader.readOriginUrl(d))
    }

    @Test fun findGitDirWalksUpFromSubdirectory() {
        val git = dir("repo", ".git")
        val sub = dir("repo", "src", "main")
        assertEquals(git, GitConfigReader.findGitDir(sub))
    }

    @Test fun findGitDirReturnsNullWithoutGit() {
        assertNull(GitConfigReader.findGitDir(dir("plain", "deep")))
    }

    @Test fun findGitDirFollowsGitFileRelativeAndAbsolute() {
        val gitdir = dir("main", ".git", "worktrees", "wt")
        val relative = dir("wt-rel")
        Files.writeString(relative.resolve(".git"), "gitdir: ../main/.git/worktrees/wt\n")
        assertEquals(gitdir, GitConfigReader.findGitDir(relative))

        val absolute = dir("wt-abs")
        Files.writeString(absolute.resolve(".git"), "gitdir: $gitdir\n")
        assertEquals(gitdir, GitConfigReader.findGitDir(absolute))
    }

    @Test fun findGitDirReturnsNullForBrokenGitFile() {
        val d = dir("broken")
        Files.writeString(d.resolve(".git"), "garbage\n")
        assertNull(GitConfigReader.findGitDir(d))
    }

    @Test fun resolveCommonDirWithoutCommondirFileIsItself() {
        val git = dir("plain-repo", ".git")
        assertEquals(git, GitConfigReader.resolveCommonDir(git))
    }

    @Test fun commondirWithNulFallsBackToGitDir() {
        val gitDir = dir("nul-common", ".git")
        Files.writeString(gitDir.resolve("commondir"), "a" + 0.toChar() + "b\n")
        assertEquals(gitDir, GitConfigReader.resolveCommonDir(gitDir))
    }

    @Test fun gitFileWithNulReturnsNull() {
        val d = dir("nul-gitfile")
        Files.writeString(d.resolve(".git"), "gitdir: a" + 0.toChar() + "b\n")
        assertNull(GitConfigReader.findGitDir(d, null))
    }

    @Test fun oversizedConfigCountsAsNoValue() {
        val d = dir("big-config")
        val head = "[remote \"origin\"]\n url = https://h/o/r\n"
        Files.writeString(d.resolve("config"), head + "#".repeat(64 * 1024))
        assertNull(GitConfigReader.readOriginUrl(d))
        Files.writeString(d.resolve("config"), head + "#".repeat(64 * 1024 - head.length))
        assertEquals("https://h/o/r", GitConfigReader.readOriginUrl(d))
    }

    @Test fun oversizedCommondirCountsAsNoValue() {
        val gitDir = dir("big-common", ".git")
        Files.writeString(gitDir.resolve("commondir"), "x".repeat(64 * 1024 + 1))
        assertEquals(gitDir, GitConfigReader.resolveCommonDir(gitDir))
    }

    @Test fun oversizedGitFileReturnsNull() {
        val d = dir("big-gitfile")
        Files.writeString(d.resolve(".git"), "gitdir: ../x" + " ".repeat(64 * 1024))
        assertNull(GitConfigReader.findGitDir(d, null))
    }

    @Test fun findGitDirStopsAtHome() {
        val home = dir("home")
        dir("home", ".git")
        val project = dir("home", "plain-project", "sub")
        assertNull(GitConfigReader.findGitDir(project, home))
        assertNull(GitConfigReader.findGitDir(home, home))
    }

    @Test fun homeIsMatchedByRealPathWhenItIsASymlink() {
        val real = dir("real-root")
        val home = dir("real-root", "home")
        dir("real-root", "home", ".git")
        val project = dir("real-root", "home", "plain-project")
        val link = tmp.root.toPath().toRealPath().resolve("link-root")
        try {
            Files.createSymbolicLink(link, real)
        } catch (e: UnsupportedOperationException) {
            Assume.assumeNoException(e)
        } catch (e: java.io.IOException) {
            Assume.assumeNoException(e)
        }
        assertNull(GitConfigReader.findGitDir(project, link.resolve("home")))
        assertNull(GitConfigReader.findGitDir(home, link.resolve("home")))
    }

    @Test fun findGitDirStillFindsRepoInsideHome() {
        val home = dir("home2")
        dir("home2", ".git")
        val git = dir("home2", "repo", ".git")
        assertEquals(git, GitConfigReader.findGitDir(dir("home2", "repo", "src"), home))
    }

    @Test fun resolveCommonDirRelativeAndAbsolute() {
        val mainGit = dir("main", ".git")
        val wtGitDir = dir("main", ".git", "worktrees", "wt")
        Files.writeString(wtGitDir.resolve("commondir"), "../..\n")
        assertEquals(mainGit, GitConfigReader.resolveCommonDir(wtGitDir))

        Files.writeString(wtGitDir.resolve("commondir"), "$mainGit\n")
        assertEquals(mainGit, GitConfigReader.resolveCommonDir(wtGitDir))
    }
}
