package io.github.driflux.jnote.ui

import javax.swing.JComponent
import javax.swing.plaf.basic.BasicHTML

internal fun <T : JComponent> T.plainText(): T = apply { putClientProperty("html.disable", true) }

internal fun plainPopupText(text: String): String = if (BasicHTML.isHTMLString(text)) "\u200B$text" else text
