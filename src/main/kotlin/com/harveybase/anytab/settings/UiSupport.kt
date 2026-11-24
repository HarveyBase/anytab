package com.harveybase.anytab.settings

import com.intellij.ui.ColoredListCellRenderer
import javax.swing.JList

/** 下拉框/列表用的纯文本渲染器：选中态的颜色由 [ColoredListCellRenderer] 处理 */
internal fun <T> textRenderer(text: (T) -> String): ColoredListCellRenderer<T> =
    object : ColoredListCellRenderer<T>() {
        override fun customizeCellRenderer(list: JList<out T>, value: T, index: Int, selected: Boolean, hasFocus: Boolean) {
            append(text(value))
        }
    }
