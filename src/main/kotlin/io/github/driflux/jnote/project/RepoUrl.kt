package io.github.driflux.jnote.project

object RepoUrl {
    private val schemeUrl = Regex("^[A-Za-z][A-Za-z0-9+.-]*://([^/]*)(/.*)?$")

    // scp 형식(git@host:org/repo)은 ':' 앞이 호스트이고 뒤는 포트가 아니라 경로다.
    // 호스트를 2자 이상으로 제한해 윈도우 드라이브 경로(C:\work)를 URL로 오인하지 않는다
    private val scpLike = Regex("^(?:[^@/:]+@)?([^@/:]{2,}):(.+)$")

    fun normalize(raw: String): String? {
        val input = raw.trim()
        val (authority, path) = split(input) ?: return null
        // 사용자 정보는 authority 안의 마지막 @까지만 떼고, 남은 포트가 숫자가 아니면 경로의 @ 때문에 잘못 나뉜 것이다
        val hostPort = authority.substringAfterLast('@')
        if (':' in hostPort && !hostPort.substringAfter(':').all { it.isDigit() }) return null
        // 사용자 정보 없이 포트만 있는데 경로에 @가 있으면, 숫자로 시작하는 비밀번호가 "/"에서 잘린 것일 수 있다
        if ('@' !in authority && ':' in hostPort && '@' in path) return null
        val host = hostPort.substringBefore(':').lowercase()
        val repoPath = path.trim('/').removeSuffix(".git").trimEnd('/')
        if (host.isEmpty() || repoPath.isEmpty()) return null
        return "$host/$repoPath"
    }

    private fun split(input: String): Pair<String, String>? {
        schemeUrl.matchEntire(input)?.let { return it.groupValues[1] to it.groupValues[2] }
        if ("://" in input) return null
        scpLike.matchEntire(input)?.let { return it.groupValues[1] to it.groupValues[2] }
        return null
    }
}
