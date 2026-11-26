package io.github.driflux.jnote.gutter

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** 거터 아이콘. 일반(OK)과 근사 위치(APPROX)를 구분하고, 어두운 테마에서는 _dark 파일을 쓴다. */
internal object GutterIcons {
    val NOTE: Icon = IconLoader.getIcon("/icons/gutterNote.svg", GutterIcons::class.java)
    val APPROX: Icon = IconLoader.getIcon("/icons/gutterNoteApprox.svg", GutterIcons::class.java)
}
