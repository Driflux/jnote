package io.github.driflux.jnote.settings

import io.github.driflux.jnote.settings.DbPathChange.Plan
import io.github.driflux.jnote.settings.DbPathChange.Reason
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.driflux.jnote.db.Database
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class DbPathChangeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun path(name: String): Path = tmp.root.toPath().resolve(name)

    private fun file(name: String): Path = path(name).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, "x")
    }

    @Test
    fun missingTargetWithExistingParentCopiesThenSwitches() {
        val target = path("new.db")
        assertEquals(Plan.CopyThenSwitch(target), DbPathChange.plan(path("cur.db"), target))
    }

    @Test
    fun missingParentFoldersThatCanBeCreatedStillCopy() {
        val target = path("a/b/c/new.db")
        assertEquals(Plan.CopyThenSwitch(target), DbPathChange.plan(file("cur.db"), target))
    }

    private fun jnoteDatabase(name: String): Path = path(name).also { p -> Database(p).use { it.submitAndWait { } } }

    private fun sqlite(name: String, vararg statements: String): Path = path(name).also { p ->
        JDBC().connect("jdbc:sqlite:$p", Properties())!!.use { c -> c.createStatement().use { s -> statements.forEach(s::execute) } }
    }

    private fun filesIn(): Set<String> = tmp.root.list()!!.toSet()

    @Test
    fun existingJNoteDatabaseSwitchesToExisting() {
        val target = jnoteDatabase("other.db")
        assertEquals(Plan.SwitchToExisting(target), DbPathChange.plan(path("cur.db"), target))
    }

    @Test
    fun emptyFileAndEmptySqliteDatabaseSwitchToExisting() {
        val empty = path("empty.db").also { Files.createFile(it) }
        assertEquals(Plan.SwitchToExisting(empty), DbPathChange.plan(path("cur.db"), empty))
        val noTables = sqlite("blank.db", "PRAGMA user_version = 3")
        assertEquals(Plan.SwitchToExisting(noTables), DbPathChange.plan(path("cur.db"), noTables))
    }

    @Test
    fun pathWithCharactersSpecialInUrisIsRead() {
        Files.createDirectories(path("a b#%?"))
        val target = sqlite("a b#%?/app.db", "create table item(x)")
        assertEquals(Plan.Invalid(Reason.NOT_JNOTE), DbPathChange.plan(path("cur.db"), target))
    }

    @Test
    fun fileThatIsNotSqliteIsInvalid() {
        val target = file("notes.txt")
        assertEquals(Plan.Invalid(Reason.NOT_SQLITE), DbPathChange.plan(path("cur.db"), target))
    }

    @Test
    fun anotherApplicationsDatabaseIsInvalidAndLeftUntouched() {
        val walMode = sqlite("app-wal.db", "PRAGMA journal_mode = WAL", "create table item(x)", "PRAGMA user_version = 7")
        val rollback = sqlite("app.db", "create table item(x)")
        val before = filesIn()
        val bytes = listOf(walMode, rollback).map { Files.readAllBytes(it).toList() }

        assertEquals(Plan.Invalid(Reason.NOT_JNOTE), DbPathChange.plan(path("cur.db"), walMode))
        assertEquals(Plan.Invalid(Reason.NOT_JNOTE), DbPathChange.plan(path("cur.db"), rollback))

        assertEquals(before, filesIn())
        assertEquals(bytes, listOf(walMode, rollback).map { Files.readAllBytes(it).toList() })
    }

    @Test
    fun databaseOpenInAnotherConnectionIsStillRecognised() {
        val target = jnoteDatabase("live.db")
        Database(target).use { live ->
            live.submitAndWait { }
            val before = filesIn()
            assertEquals(Plan.SwitchToExisting(target), DbPathChange.plan(path("cur.db"), target))
            assertEquals(before, filesIn())
        }
    }

    @Test
    fun samePathIsNoChange() {
        val cur = file("cur.db")
        assertEquals(Plan.NoChange, DbPathChange.plan(cur, cur))
    }

    @Test
    fun sameFileSpelledDifferentlyIsNoChange() {
        val cur = file("dir/cur.db")
        val dotted = path("dir/../dir/./cur.db")
        assertEquals(Plan.NoChange, DbPathChange.plan(cur, dotted))
    }

    @Test
    fun sameFileThroughSymlinkedFolderIsNoChangeEvenWhenMissing() {
        Files.createDirectories(path("real"))
        val link = Files.createSymbolicLink(path("link"), path("real"))
        assertEquals(Plan.NoChange, DbPathChange.plan(path("real/cur.db"), link.resolve("cur.db")))
    }

    @Test
    fun sameFileThroughSymlinkIsNoChangeWhenExisting() {
        val cur = file("real/cur.db")
        val link = Files.createSymbolicLink(path("link.db"), cur)
        assertEquals(Plan.NoChange, DbPathChange.plan(cur, link))
    }

    @Test
    fun directoryTargetIsInvalid() {
        Files.createDirectories(path("folder"))
        assertEquals(Plan.Invalid(Reason.IS_DIRECTORY), DbPathChange.plan(path("cur.db"), path("folder")))
    }

    @Test
    fun parentThatIsAFileCannotBeCreated() {
        val blocker = file("blocker")
        assertEquals(
            Plan.Invalid(Reason.PARENT_NOT_CREATABLE),
            DbPathChange.plan(path("cur.db"), blocker.resolve("sub/new.db")),
        )
    }

    @Test
    fun relativePathIsInvalid() {
        val relative = java.nio.file.Paths.get("rel/new.db")
        assertEquals(Plan.Invalid(Reason.NOT_ABSOLUTE), DbPathChange.plan(path("cur.db"), relative))
    }

    @Test
    fun unwritableParentIsInvalid() {
        val dir = path("ro").also { Files.createDirectories(it) }
        dir.toFile().setWritable(false)
        try {
            assumeFalse("running with privileges that ignore permissions", Files.isWritable(dir))
            assertEquals(Plan.Invalid(Reason.NOT_WRITABLE), DbPathChange.plan(path("cur.db"), dir.resolve("new.db")))
            assertEquals(
                Plan.Invalid(Reason.PARENT_NOT_CREATABLE),
                DbPathChange.plan(path("cur.db"), dir.resolve("deeper/new.db")),
            )
        } finally {
            dir.toFile().setWritable(true)
        }
    }

    @Test
    fun unwritableExistingFileIsInvalid() {
        val target = file("locked.db")
        target.toFile().setWritable(false)
        try {
            assumeFalse("running with privileges that ignore permissions", Files.isWritable(target))
            assertEquals(Plan.Invalid(Reason.NOT_WRITABLE), DbPathChange.plan(path("cur.db"), target))
        } finally {
            target.toFile().setWritable(true)
        }
    }
}
