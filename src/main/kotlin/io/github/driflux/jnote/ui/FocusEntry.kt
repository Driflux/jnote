package io.github.driflux.jnote.ui

import java.awt.Component
import javax.swing.SwingUtilities

/**
 * 포커스가 패널 밖에서 안으로 들어왔는지 판단한다.
 * 포커스 이동이 list -> null -> searchField처럼 null을 거쳐 알려질 수 있어서, null 이전의 마지막 위치를 기억한다.
 */
internal class FocusEntry(private val panel: Component) {
    private var lastInside = false

    fun entered(oldOwner: Component?, newOwner: Component?): Boolean {
        val wasInside = if (oldOwner == null) lastInside else inside(panel, oldOwner)
        val isInside = inside(panel, newOwner)
        lastInside = if (newOwner == null) wasInside else isInside
        return isInside && !wasInside
    }

    companion object {
        fun inside(panel: Component, owner: Component?): Boolean = owner != null && SwingUtilities.isDescendingFrom(owner, panel)
    }
}
