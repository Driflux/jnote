package io.github.driflux.jnote.model

import org.junit.Assert.assertThrows
import org.junit.Test

class AnchorUpdateTest {
    @Test
    fun rejectsRangesANoteCannotHold() {
        // 이런 값이 DB에 들어가면 그 노트를 읽는 목록 전체가 Note의 init 검사에서 실패한다
        for ((start, end) in listOf(0 to 1, 5 to 4)) {
            assertThrows(IllegalArgumentException::class.java) { AnchorUpdate("u", "src/A.kt", start, end, NoteStatus.OK) }
        }
        assertThrows(IllegalArgumentException::class.java) { AnchorUpdate("u", " ", 1, 1, NoteStatus.OK) }
        AnchorUpdate("u", "src/A.kt", 1, 1, NoteStatus.OK)
    }
}
