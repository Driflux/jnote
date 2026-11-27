package io.github.driflux.jnote.ui

import java.awt.Component
import javax.swing.SwingUtilities

internal object FocusEntry {
    fun entered(panel: Component, oldOwner: Component?, newOwner: Component?): Boolean =
        inside(panel, newOwner) && !inside(panel, oldOwner)

    fun inside(panel: Component, owner: Component?): Boolean = owner != null && SwingUtilities.isDescendingFrom(owner, panel)
}
