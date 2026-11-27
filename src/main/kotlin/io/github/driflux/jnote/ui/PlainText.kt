package io.github.driflux.jnote.ui

import javax.swing.JComponent
import javax.swing.plaf.basic.BasicHTML

/** 노트 텍스트(제목, 태그 이름 등)는 신뢰할 수 없는 입력이라 html 태그로 시작해도 마크업으로 그리지 않는다. */
internal fun <T : JComponent> T.plainText(): T = apply { putClientProperty("html.disable", true) }

/** 팝업 목록은 렌더러를 바꿀 수 없어, HTML 문서로 읽히는 글 앞에 보이지 않는 글자를 붙여 막는다. */
internal fun plainPopupText(text: String): String = if (BasicHTML.isHTMLString(text)) "\u200B$text" else text
