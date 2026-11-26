package io.github.driflux.jnote.project

import java.nio.file.Path

/** origin URL이 있으면 정규화한 URL을, 아니면 프로젝트 경로를 키로 쓴다. 블로킹 I/O이므로 EDT 밖에서 호출한다. */
object ProjectKeyResolver {
    fun resolve(basePath: Path): ProjectKey {
        val normalized = GitConfigReader.findGitDir(basePath)
            ?.let { GitConfigReader.readOriginUrl(GitConfigReader.resolveCommonDir(it)) }
            ?.let { RepoUrl.normalize(it) }
        if (normalized != null) return ProjectKey(normalized, normalized.substringAfterLast('/'))
        // 루트 경로는 fileName이 null이다
        val name = basePath.fileName?.toString() ?: basePath.toString()
        return ProjectKey(basePath.toString(), name)
    }
}
