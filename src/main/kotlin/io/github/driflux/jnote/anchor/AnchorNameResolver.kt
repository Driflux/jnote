package io.github.driflux.jnote.anchor

import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner

/** 오프셋을 감싸는 클래스 이름을 구한다. 언어별 구현은 이 인터페이스 뒤에 둔다. 읽기 액션 안에서 호출한다. */
interface AnchorNameResolver {
    fun resolve(file: PsiFile, offset: Int): String?
}

/**
 * 언어에 상관없는 공통 PSI 구현. 오프셋의 요소에서 위로 올라가며 이름 있는 [PsiNameIdentifierOwner]
 * (클래스, 함수 등)의 이름을 모아 바깥쪽부터 `.`로 잇는다. 가장 안쪽부터 [MAX_LEVELS]단계까지만 쓴다.
 * 이름 있는 컨테이너가 없으면 null.
 */
class PsiAnchorNameResolver : AnchorNameResolver {
    override fun resolve(file: PsiFile, offset: Int): String? {
        if (file.textLength == 0) return null
        val leaf = file.findElementAt(offset.coerceIn(0, file.textLength - 1)) ?: return null
        return try {
            joinedNames(leaf)
        } catch (e: IndexNotReadyException) {
            // 인덱스가 필요한 언어의 이름 조회가 인덱싱 중 실패하면 이름 없이 저장한다
            null
        }
    }

    internal fun joinedNames(start: PsiElement): String? {
        val names = ArrayList<String>(MAX_LEVELS)
        var element: PsiElement? = start
        while (element != null && element !is PsiFile && names.size < MAX_LEVELS) {
            if (element is PsiNameIdentifierOwner) element.name?.takeIf { it.isNotBlank() }?.let(names::add)
            element = element.parent
        }
        return if (names.isEmpty()) null else names.asReversed().joinToString(".")
    }

    companion object {
        const val MAX_LEVELS = 3
    }
}
