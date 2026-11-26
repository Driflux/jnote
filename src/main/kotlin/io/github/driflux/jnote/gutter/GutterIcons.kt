package io.github.driflux.jnote.gutter

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

internal object GutterIcons {
    val NOTE: Icon = IconLoader.getIcon("/icons/gutterNote.svg", GutterIcons::class.java)
    val APPROX: Icon = IconLoader.getIcon("/icons/gutterNoteApprox.svg", GutterIcons::class.java)
}
