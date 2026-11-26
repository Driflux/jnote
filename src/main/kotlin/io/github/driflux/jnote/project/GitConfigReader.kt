package io.github.driflux.jnote.project

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

object GitConfigReader {
    private val sectionHeader = Regex("^\\[\\s*([^\\s\\]\"]+)(?:\\s+\"((?:[^\"\\\\]|\\\\.)*)\")?\\s*](.*)$")
    private val keyValue = Regex("^([A-Za-z][A-Za-z0-9-]*)\\s*=(.*)$")

    // 설정 파일은 신뢰할 수 없는 프로젝트에서 오므로 크기를 제한한다. 넘으면 값이 없는 것으로 본다
    private const val MAX_FILE_BYTES = 64 * 1024

    private val userHome: Path? = try {
        System.getProperty("user.home")?.let { Path.of(it).toAbsolutePath().normalize() }
    } catch (e: InvalidPathException) {
        null
    }

    /**
     * home에 닿으면 멈추고 home의 `.git`은 받지 않는다. 홈 전체를 dotfiles 저장소로 쓰는 경우
     * 그 아래 비git 프로젝트가 저장소의 origin을 가져가기 때문이다.
     */
    fun findGitDir(start: Path, home: Path? = userHome): Path? {
        val stop = home?.let { realOrNormalized(it) }
        var dir: Path? = start.toAbsolutePath().normalize()
        while (dir != null && (stop == null || realOrNormalized(dir) != stop)) {
            val dotGit = dir.resolve(".git")
            if (Files.isDirectory(dotGit)) return dotGit
            if (Files.isRegularFile(dotGit)) return readGitDirFile(dotGit)
            dir = dir.parent
        }
        return null
    }

    fun resolveCommonDir(gitDir: Path): Path {
        val commonDirFile = gitDir.resolve("commondir")
        val raw = readText(commonDirFile)?.trim()
        if (raw.isNullOrEmpty()) return gitDir
        // 상대 경로는 gitDir 기준이고, 절대 경로는 resolve가 그대로 돌려준다
        return try {
            gitDir.resolve(raw).normalize()
        } catch (e: InvalidPathException) {
            gitDir
        }
    }

    fun readOriginUrl(commonDir: Path): String? {
        val text = readText(commonDir.resolve("config")) ?: return null
        var inOrigin = false
        for (line in text.lineSequence()) {
            var rest = stripComment(line).trim()
            if (rest.isEmpty()) continue
            val header = sectionHeader.matchEntire(rest)
            if (header != null) {
                inOrigin = header.groupValues[1].equals("remote", ignoreCase = true) &&
                    header.groupValues[2] == "origin"
                // 헤더와 같은 줄에 키가 이어질 수 있다
                rest = header.groupValues[3].trim()
                if (rest.isEmpty()) continue
            }
            if (!inOrigin) continue
            val pair = keyValue.matchEntire(rest) ?: continue
            if (pair.groupValues[1].equals("url", ignoreCase = true)) {
                return unquote(pair.groupValues[2].trim()).takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    // 홈이 심볼릭 링크여도 같은 곳으로 보도록 실제 경로로 비교한다. 풀 수 없으면 정규화한 경로를 쓴다
    private fun realOrNormalized(path: Path): Path = try {
        path.toRealPath()
    } catch (e: IOException) {
        path.toAbsolutePath().normalize()
    }

    private fun readGitDirFile(file: Path): Path? {
        val line = readText(file)?.lineSequence()?.firstOrNull { it.startsWith("gitdir:") } ?: return null
        val target = line.removePrefix("gitdir:").trim()
        if (target.isEmpty()) return null
        return try {
            file.toAbsolutePath().parent.resolve(target).normalize()
        } catch (e: InvalidPathException) {
            null
        }
    }

    private fun readText(file: Path): String? = try {
        if (!Files.isRegularFile(file)) {
            null
        } else {
            Files.newInputStream(file).use { input ->
                val bytes = input.readNBytes(MAX_FILE_BYTES + 1)
                if (bytes.size > MAX_FILE_BYTES) {
                    null
                } else {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                }
            }
        }
    } catch (e: IOException) {
        null
    }

    // 따옴표 밖의 # 또는 ; 부터가 주석이다 (git 설정 규칙)
    private fun stripComment(line: String): String {
        var quoted = false
        var escaped = false
        for ((i, ch) in line.withIndex()) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> quoted = !quoted
                !quoted && (ch == '#' || ch == ';') -> return line.substring(0, i)
            }
        }
        return line
    }

    private fun unquote(value: String): String {
        val out = StringBuilder()
        var escaped = false
        for (ch in value) {
            when {
                escaped -> { out.append(ch); escaped = false }
                ch == '\\' -> escaped = true
                ch == '"' -> {}
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
