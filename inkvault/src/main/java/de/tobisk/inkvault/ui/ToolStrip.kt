package de.tobisk.inkvault.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** Wrap tools instead of hiding editing actions off the edge of a narrow canvas. */
class ToolStrip(context: Context) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        var x = 0
        var y = 0
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > available) {
                y += lineHeight
                x = 0
                lineHeight = 0
            }
            x += child.measuredWidth
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        setMeasuredDimension(available, resolveSize(y + lineHeight, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var x = 0
        var y = 0
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            if (x > 0 && x + child.measuredWidth > width) {
                y += lineHeight
                x = 0
                lineHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }
}
