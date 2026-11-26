package io.github.driflux.jnote.gutter

import com.intellij.icons.AllIcons
import javax.swing.Icon

/** 거터 아이콘. 일반(OK)과 근사 위치(APPROX)를 구분한다. */
internal object GutterIcons {
    val NOTE: Icon = AllIcons.Gutter.JavadocRead
    val APPROX: Icon = AllIcons.Gutter.JavadocEdit
}
