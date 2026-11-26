package io.github.driflux.jnote.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepoUrlTest {
    private fun assertNormalized(raw: String, expected: String = "github.com/org/repo") =
        assertEquals(expected, RepoUrl.normalize(raw))

    @Test fun scpLike() = assertNormalized("git@github.com:org/repo.git")
    @Test fun sshWithPort() = assertNormalized("ssh://git@github.com:2222/org/repo.git")
    @Test fun httpsWithCredentials() = assertNormalized("https://user:token@github.com/org/repo.git")
    @Test fun httpsTrailingSlash() = assertNormalized("https://github.com/org/repo/")
    @Test fun gitProtocol() = assertNormalized("git://github.com/org/repo")
    @Test fun httpsWithPort() = assertNormalized("https://github.com:8443/org/repo")
    @Test fun trailingSlashAfterGitSuffix() = assertNormalized("https://github.com/org/repo.git/")
    @Test fun surroundingWhitespace() = assertNormalized("  git@github.com:org/repo.git\n")

    @Test fun hostIsLowercasedPathCaseKept() =
        assertNormalized("https://GitHub.COM/Org/Repo.git", "github.com/Org/Repo")

    @Test fun nestedGroupPath() =
        assertNormalized("git@gitlab.com:group/sub/repo.git", "gitlab.com/group/sub/repo")

    @Test fun scpWithoutUser() = assertNormalized("github.com:org/repo.git")

    @Test fun passwordWithSlashDoesNotLeakIntoKey() {
        assertNull(RepoUrl.normalize("https://u:p/ss@h/o/r"))
        assertNull(RepoUrl.normalize("https://user:pa/ss@github.com/org/repo.git"))
    }

    @Test fun atSignInPathIsKept() = assertNormalized("https://github.com/org/re@po", "github.com/org/re@po")

    @Test fun lastAtOfAuthorityStripsUserInfo() = assertNormalized("https://a@b:pw@github.com/org/repo")

    @Test fun invalidInputReturnsNull() {
        listOf(
            "", "   ", "not a url", "/local/path/repo", "../repo", "C:\\work\\repo",
            "file:///local/repo.git", "https://", "https://github.com", "https://github.com/",
            "git@github.com:", "://github.com/org/repo",
        ).forEach { assertNull("input: '$it'", RepoUrl.normalize(it)) }
    }
}
