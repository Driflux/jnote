package io.github.driflux.jnote.settings

import com.intellij.util.xmlb.XmlSerializer
import io.github.driflux.jnote.db.DatabasePaths
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class DbPathSettingsTest {
    @Test
    fun defaultsToEmptyMeaningBuiltInPath() {
        assertEquals("", DbPathSettings().state.dbPath)
    }

    @Test
    fun loadStateReplacesState() {
        val settings = DbPathSettings()
        settings.loadState(DbPathSettings.State("/data/jnote.db"))
        assertEquals("/data/jnote.db", settings.state.dbPath)
    }

    @Test
    fun stateSurvivesXmlRoundTrip() {
        val element = XmlSerializer.serialize(DbPathSettings.State("/data/jnote.db"))
        val restored = XmlSerializer.deserialize(element, DbPathSettings.State::class.java)
        assertEquals("/data/jnote.db", restored.dbPath)
    }

    @Test
    fun storingTheDefaultPathKeepsStateEmpty() {
        val settings = DbPathSettings()
        settings.store(DatabasePaths.builtInDefault())
        assertEquals("", settings.state.dbPath)
        settings.store(Paths.get("/data/jnote.db"))
        assertEquals(Paths.get("/data/jnote.db").toString(), settings.state.dbPath)
    }

    @Test
    fun resolveHandlesEmptyHomeAndAbsolute() {
        val home = System.getProperty("user.home")
        assertEquals(DatabasePaths.builtInDefault(), DatabasePaths.resolve(""))
        assertEquals(DatabasePaths.builtInDefault(), DatabasePaths.resolve("  "))
        assertEquals(Paths.get(home, "x", "a.db"), DatabasePaths.resolve("~/x/a.db"))
        assertEquals(Paths.get("/data/a.db"), DatabasePaths.resolve("/data/a.db"))
    }
}
