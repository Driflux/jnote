package io.github.driflux.jnote.settings

import com.intellij.openapi.diagnostic.thisLogger
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

    /**
     * 기존 파일이 JNote가 열어도 되는 DB인지 본다. 파일에는 쓰지 않는다. 0바이트, 테이블이 없고 user_version이 0인 DB, note 테이블이 있는 DB만 통과한다.
     * 계획과 전환 직전 모두에서 부르지만, 그 사이 파일이 바뀌는 것까지 막지는 못한다.
     */
    internal fun existingFileProblem(target: Path): Reason? {
        val file = target.toRealPath()
        if (Files.size(file) == 0L) return null
        val header = ByteArray(SQLITE_HEADER.size)
        val read = Files.newInputStream(file).use { it.readNBytes(header, 0, header.size) }
        if (read < header.size || !header.contentEquals(SQLITE_HEADER)) return Reason.NOT_SQLITE
        val schema = readSchema(file)
        val usable = if (schema.tables.isEmpty()) schema.userVersion == 0 else NOTE_TABLE in schema.tables
        return if (usable) null else Reason.NOT_JNOTE
    }

    private class Schema(val tables: Set<String>, val userVersion: Int)

    private fun readSchema(file: Path): Schema {
        val wal = sidecar(file, "-wal")
        // -wal이 없으면 본 파일이 내용의 전부라 immutable로 연다. 잠금을 잡지 않고 -wal/-shm 파일도 만들지 않는다
        if (!Files.exists(wal)) return querySchema(immutable(file))
        // -wal에만 있는 내용까지 읽어야 하지만 원본에 잠금이나 -shm을 남기지 않도록 사본을 읽는다.
        // 너무 커서 복사하지 않으면 -wal에 무엇이 있는지 모르므로 통과시키지 않는다
        repeat(COPY_ATTEMPTS) {
            val before = stamps(file, wal)
            if (before.sumOf { it.first } > maxCopyBytes) return UNKNOWN
            val dir = Files.createTempDirectory("jnote-check")
            try {
                val copy = dir.resolve("check.db")
                Files.copy(file, copy)
                Files.copy(wal, sidecar(copy, "-wal"))
                // 두 파일을 따로 복사하는 사이 다른 연결이 쓰거나 체크포인트하면 서로 다른 시점의 사본이 되므로 다시 한다
                if (stamps(file, wal) == before) return querySchema("jdbc:sqlite:" + copy.toUri())
            } finally {
                if (!dir.toFile().deleteRecursively()) thisLogger().warn("Cannot delete the temporary copy in $dir")
            }
        }
        throw IllegalStateException("$file kept changing while it was being checked")
    }

    private fun stamps(vararg files: Path): List<Pair<Long, java.nio.file.attribute.FileTime>> =
        files.map { Files.size(it) to Files.getLastModifiedTime(it) }

    private fun immutable(file: Path): String = "jdbc:sqlite:" + file.toUri() + "?mode=ro&immutable=1"

    private fun sidecar(file: Path, suffix: String): Path = file.resolveSibling(file.fileName.toString() + suffix)

    private fun querySchema(url: String): Schema {
        val connection = JDBC().connect(url, Properties()) ?: error("sqlite-jdbc rejected url $url")
        return connection.use { c ->
            c.createStatement().use { s ->
                val tables = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString(1)) }
                }
                val version = s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                Schema(tables, version)
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
    private const val COPY_ATTEMPTS = 3
    private val UNKNOWN = Schema(emptySet(), -1)

    /** -wal이 있는 DB를 사본으로 읽을 때 복사할 최대 크기(본 파일 + -wal). */
    internal var maxCopyBytes: Long = 64L shl 20
}
