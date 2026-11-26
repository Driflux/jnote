package io.github.driflux.jnote.db

import java.nio.file.Path
import java.nio.file.Paths

object DatabasePaths {
    fun default(): Path = Paths.get(System.getProperty("user.home"), ".jnote", "jnote.db")
}
