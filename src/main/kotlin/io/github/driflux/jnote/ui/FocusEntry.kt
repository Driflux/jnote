package io.github.driflux.jnote.ui

import java.awt.Component
import javax.swing.SwingUtilities

/** 포커스가 [panel] 밖에서 안으로 들어왔는지. 패널 안에서 옮겨 다니는 것(목록과 검색창 사이)은 들어옴이 아니다. */
internal object FocusEntry {
    fun entered(panel: Component, oldOwner: Component?, newOwner: Component?): Boolean =
        inside(panel, newOwner) && !inside(panel, oldOwner)

    fun inside(panel: Component, owner: Component?): Boolean = owner != null && SwingUtilities.isDescendingFrom(owner, panel)
}
