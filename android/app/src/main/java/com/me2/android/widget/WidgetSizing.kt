package com.me2.android.widget

import kotlin.math.max
import kotlin.math.min

/** Mantiene el widget 1:1: lado del cuadrado y padding para centrarlo dentro del área asignada. */
object WidgetSizing {
    data class Square(val side: Int, val padH: Int, val padV: Int)

    /** w/h en dp del área real del widget (vertical: minWidth x maxHeight; horizontal: maxWidth x minHeight). */
    fun square(widthDp: Int, heightDp: Int): Square {
        val w = max(1, widthDp)
        val h = max(1, heightDp)
        val side = min(w, h)
        return Square(side, (w - side) / 2, (h - side) / 2)
    }

    fun fromOptions(minW: Int, maxW: Int, minH: Int, maxH: Int, portrait: Boolean): Square =
        if (portrait) square(minW, maxH) else square(maxW, minH)
}
