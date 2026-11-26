package io.github.driflux.jnote.project

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** git 설정 파일만 읽는다. 프로세스를 띄우지 않으며 모든 읽기 실패는 null로 돌려준다. */
object GitConfigReader {
    private val sectionHeader = Regex("^\\[\\s*([^\\s\\]\"]+)(?:\\s+\"((?:[^\"\\\\]|\\\\.)*)\")?\\s*](.*)$")
    private val keyValue = Regex("^([A-Za-z][A-Za-z0-9-]*)\\s*=(.*)$")

    /** start부터 위로 올라가며 첫 `.git`을 찾아 git 디렉터리를 돌려준다. 워크트리의 `.git` 파일이면 gitdir 대상이다. */
    fun findGitDir(start: Path): Path? {
        var dir: Path? = start.toAbsolutePath().normalize()
        while (dir != null) {
            val dotGit = dir.resolve(".git")
            if (Files.isDirectory(dotGit)) return dotGit
            if (Files.isRegularFile(dotGit)) return readGitDirFile(dotGit)
            dir = dir.parent
        }
        return null
    }

    /** 워크트리의 gitdir에는 `commondir` 파일이 있고 메인 `.git`을 가리킨다. 없으면 gitDir 자체가 공용 디렉터리다. */
    fun resolveCommonDir(gitDir: Path): Path {
        val commonDirFile = gitDir.resolve("commondir")
        val raw = readText(commonDirFile)?.trim()
        if (raw.isNullOrEmpty()) return gitDir
        // 상대 경로는 gitDir 기준이고, 절대 경로는 resolve가 그대로 돌려준다
        return gitDir.resolve(raw).normalize()
    }

    /** commonDir/config의 `[remote "origin"]` 첫 url. 없거나 읽을 수 없으면 null. */
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

    private fun readGitDirFile(file: Path): Path? {
        val line = readText(file)?.lineSequence()?.firstOrNull { it.startsWith("gitdir:") } ?: return null
        val target = line.removePrefix("gitdir:").trim()
        if (target.isEmpty()) return null
        return file.toAbsolutePath().parent.resolve(target).normalize()
    }

    private fun readText(file: Path): String? = try {
        Files.readString(file)
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
