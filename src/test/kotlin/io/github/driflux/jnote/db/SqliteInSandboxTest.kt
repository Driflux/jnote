package io.github.driflux.jnote.db

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

/** IDE 클래스로더 아래에서 sqlite-jdbc와 네이티브 라이브러리가 실제로 로드되는지 확인한다. */
class SqliteInSandboxTest : BasePlatformTestCase() {
    fun testSqliteJdbcWorksUnderIdeClassLoader() {
        val dir = Files.createTempDirectory("jnote-sandbox")
        val db = Database(dir.resolve("jnote.db"))
        try {
            val version = db.submitAndWait { c ->
                c.createStatement().use { s -> s.executeQuery("select sqlite_version()").use { it.next(); it.getString(1) } }
            }
            assertTrue(version.isNotEmpty())
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }
}
