package io.github.driflux.jnote.settings

import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

object DbPathChange {
    enum class Reason(val messageKey: String) {
        NOT_ABSOLUTE("settings.dbPath.invalid.notAbsolute"),
        PARENT_NOT_CREATABLE("settings.dbPath.invalid.parentNotCreatable"),
        IS_DIRECTORY("settings.dbPath.invalid.isDirectory"),
        NOT_WRITABLE("settings.dbPath.invalid.notWritable"),
        NOT_SQLITE("settings.dbPath.invalid.notSqlite"),
        NOT_JNOTE("settings.dbPath.invalid.notJNote"),
    }

    sealed interface Plan {
        data object NoChange : Plan

        data class CopyThenSwitch(val target: Path) : Plan

        /** 새 경로에 파일이 이미 있다: 확인을 받고 그 DB로 전환한다(병합하지 않는다). */
        data class SwitchToExisting(val target: Path) : Plan

        data class Invalid(val reason: Reason) : Plan
    }

    fun plan(current: Path, requested: Path): Plan {
        if (!requested.isAbsolute) return Plan.Invalid(Reason.NOT_ABSOLUTE)
        val target = requested.normalize()
        if (Files.isDirectory(target)) return Plan.Invalid(Reason.IS_DIRECTORY)
        if (canonical(current) == canonical(target)) return Plan.NoChange

        if (Files.exists(target)) {
            if (!Files.isRegularFile(target) || !Files.isWritable(target)) return Plan.Invalid(Reason.NOT_WRITABLE)
            return existingFileProblem(target)?.let { Plan.Invalid(it) } ?: Plan.SwitchToExisting(target)
        }
        val ancestor = nearestExisting(target)
        return when {
            ancestor == null || !Files.isDirectory(ancestor) -> Plan.Invalid(Reason.PARENT_NOT_CREATABLE)
            !Files.isWritable(ancestor) ->
                Plan.Invalid(if (ancestor == target.parent) Reason.NOT_WRITABLE else Reason.PARENT_NOT_CREATABLE)
            else -> Plan.CopyThenSwitch(target)
        }
    }

    // 전환 때 여는 검사는 마이그레이션까지 돌려 다른 앱의 DB에 JNote 테이블을 만들므로, 확인창 전에 쓰기 없이 거른다
    private fun existingFileProblem(target: Path): Reason? {
        if (Files.size(target) == 0L) return null
        val header = ByteArray(SQLITE_HEADER.size)
        val read = Files.newInputStream(target).use { it.readNBytes(header, 0, header.size) }
        if (read < header.size || !header.contentEquals(SQLITE_HEADER)) return Reason.NOT_SQLITE
        val tables = tableNames(target)
        return if (tables.isEmpty() || NOTE_TABLE in tables) null else Reason.NOT_JNOTE
    }

    private fun tableNames(target: Path): Set<String> {
        // -wal이 없으면 본 파일이 내용의 전부라 immutable로 연다. 그래야 잠금을 잡지 않고 -wal/-shm 파일도 만들지 않는다
        val wal = target.resolveSibling(target.fileName.toString() + "-wal")
        val query = if (Files.exists(wal)) "?mode=ro" else "?mode=ro&immutable=1"
        val connection = JDBC().connect("jdbc:sqlite:" + target.toUri() + query, Properties())
            ?: error("sqlite-jdbc rejected url for $target")
        return connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }
    }

    private fun nearestExisting(path: Path): Path? {
        var p: Path? = path
        while (p != null && !Files.exists(p)) p = p.parent
        return p
    }

    // 심볼릭 링크와 대소문자 무시 파일시스템에서도 같은 파일을 같게 보도록, 존재하는 상위 경로만 실제 경로로 푼다
    private fun canonical(path: Path): Path {
        val abs = path.toAbsolutePath().normalize()
        val existing = nearestExisting(abs) ?: return abs
        return existing.toRealPath().resolve(existing.relativize(abs)).normalize()
    }

    private val SQLITE_HEADER = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0.toByte()
    private const val NOTE_TABLE = "note"
}
