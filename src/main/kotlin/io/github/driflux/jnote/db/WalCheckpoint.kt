package io.github.driflux.jnote.db

import java.sql.Connection

/** wal_checkpoint(TRUNCATE)가 끝까지 수행되지 않으면(읽는 쪽이 있을 때 busy=1) 복사본이 불완전하므로 실패로 다룬다. */
internal object WalCheckpoint {
    fun truncateOrFail(connection: Connection, attempts: Int = 3, waitMillis: Long = 100) {
        repeat(attempts) { attempt ->
            val busy = connection.createStatement().use { s ->
                s.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            if (busy == 0) return
            if (attempt < attempts - 1) Thread.sleep(waitMillis)
        }
        error("WAL checkpoint could not finish because another connection is reading the database")
    }
}
