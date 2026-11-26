package io.github.driflux.jnote.db

import io.github.driflux.jnote.settings.DbPathSettings
import java.nio.file.Path
import java.nio.file.Paths

object DatabasePaths {
    fun builtInDefault(): Path = Paths.get(System.getProperty("user.home"), ".jnote", "jnote.db")

    fun resolve(text: String): Path {
        val trimmed = text.trim()
        val home = System.getProperty("user.home")
        return when {
            trimmed.isEmpty() -> builtInDefault()
            trimmed == "~" -> Paths.get(home)
            trimmed.startsWith("~/") -> Paths.get(home, trimmed.substring(2))
            else -> Paths.get(trimmed)
        }
    }

    fun default(): Path = resolve(DbPathSettings.getInstance().state.dbPath)
}
