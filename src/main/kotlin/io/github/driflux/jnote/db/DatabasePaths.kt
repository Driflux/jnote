package io.github.driflux.jnote.db

import java.nio.file.Path
import java.nio.file.Paths

/** DB 파일 위치 제공자. 경로 설정 기능은 이슈 #15에서 추가한다. */
object DatabasePaths {
    fun default(): Path = Paths.get(System.getProperty("user.home"), ".jnote", "jnote.db")
}
