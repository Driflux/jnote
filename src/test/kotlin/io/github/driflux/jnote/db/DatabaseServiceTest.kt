package io.github.driflux.jnote.db

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun currentReadsThePathTogetherWithTheDatabaseMadeForIt() {
        val service = DatabaseService()
        val first = tmp.root.toPath().resolve("first.db")
        service.switchTo(first)
        assertEquals(DatabaseService.Current(first, null), service.current())

        val database = service.database
        assertEquals(DatabaseService.Current(first, database), service.current())

        val second = tmp.root.toPath().resolve("second.db")
        service.switchTo(second)
        assertEquals(DatabaseService.Current(second, null), service.current())
    }
}
