package io.github.driflux.jnote.settings

import com.intellij.openapi.diagnostic.thisLogger
import io.github.driflux.jnote.db.Migrations
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Properties

object DbPathChange {
    enum class Reason(val messageKey: String) {
        NOT_ABSOLUTE("settings.dbPath.invalid.notAbsolute"),
        PARENT_NOT_CREATABLE("settings.dbPath.invalid.parentNotCreatable"),
        IS_DIRECTORY("settings.dbPath.invalid.isDirectory"),
        NOT_WRITABLE("settings.dbPath.invalid.notWritable"),
        NOT_SQLITE("settings.dbPath.invalid.notSqlite"),
        NOT_JNOTE("settings.dbPath.invalid.notJNote"),
        HAS_JOURNAL("settings.dbPath.invalid.hasJournal"),
    }

    sealed interface Plan {
        data object NoChange : Plan

        data class CopyThenSwitch(val target: Path) : Plan

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

    internal fun existingFileProblem(target: Path): Reason? {
        val file = target.toRealPath()
        val journal = sidecar(file, "-journal")
        if (Files.exists(journal) && Files.size(journal) > 0L) return Reason.HAS_JOURNAL
        if (Files.size(file) == 0L) return null
        val header = ByteArray(SQLITE_HEADER.size)
        val read = Files.newInputStream(file).use { it.readNBytes(header, 0, header.size) }
        if (read < header.size || !header.contentEquals(SQLITE_HEADER)) return Reason.NOT_SQLITE
        val schema = readSchema(file)
        val usable = if (!schema.hasObjects) schema.userVersion == 0 else hasJNoteSchema(schema)
        return if (usable) null else Reason.NOT_JNOTE
    }

    private fun hasJNoteSchema(schema: Schema): Boolean {
        if (schema.userVersion < 1) return false
        val expected = expectedTables(minOf(schema.userVersion, Migrations.ALL.size))
        return expected.all { (table, columns) -> schema.tables[table]?.containsAll(columns) == true }
    }

    private fun expectedTables(version: Int): Map<String, Set<String>> =
        JDBC().connect("jdbc:sqlite::memory:", Properties())!!.use { c ->
            Migrations.run(c, Migrations.ALL.take(version))
            tableColumns(c)
        }

    private class Schema(val tables: Map<String, Set<String>>, val hasObjects: Boolean, val userVersion: Int)

    private fun readSchema(file: Path): Schema {
        val wal = sidecar(file, "-wal")
        if (!Files.exists(wal)) return querySchema(immutable(file))
        repeat(COPY_ATTEMPTS) {
            val before = stamps(file, wal)
            if (before.sumOf { it.first } > maxCopyBytes) return UNKNOWN
            val dir = Files.createTempDirectory("jnote-check")
            try {
                val copy = dir.resolve("check.db")
                Files.copy(file, copy)
                Files.copy(wal, sidecar(copy, "-wal"))
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
                val tables = tableColumns(c)
                val hasObjects = s.executeQuery("SELECT 1 FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' LIMIT 1").use { it.next() }
                val version = s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                Schema(tables, hasObjects, version)
            }
        }
    }

    private fun tableColumns(c: Connection): Map<String, Set<String>> = c.createStatement().use { s ->
        val names = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }
        names.associateWith { name ->
            s.executeQuery("PRAGMA table_info(\"" + name.replace("\"", "\"\"") + "\")").use { rows ->
                buildSet { while (rows.next()) add(rows.getString("name")) }
            }
        }
    }

    private fun nearestExisting(path: Path): Path? {
        var p: Path? = path
        while (p != null && !Files.exists(p)) p = p.parent
        return p
    }

    private fun canonical(path: Path): Path {
        val abs = path.toAbsolutePath().normalize()
        val existing = nearestExisting(abs) ?: return abs
        return existing.toRealPath().resolve(existing.relativize(abs)).normalize()
    }

    private val SQLITE_HEADER = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0.toByte()
    private const val COPY_ATTEMPTS = 3
    private val UNKNOWN = Schema(emptyMap(), true, -1)

    internal var maxCopyBytes: Long = 64L shl 20
}
