package com.vboard.app.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow

/**
 * Key preview bubble and long-press alternate selector, per DESIGN_SPEC §2.
 * Rendered as a lightweight PopupWindow anchored above the key.
 */
class KeyPopup(
    private val anchor: View,
    private val theme: KeyboardTheme,
) {
    private val density = anchor.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, anchor.resources.displayMetrics)

    var isSelector = false
        private set

    private var candidates: List<String> = emptyList()
    private var selectedIndex = 0
    private var window: PopupWindow? = null
    private var contentView: PopupView? = null
    private var cellWidth = 0f
    private var popupScreenLeft = 0

    fun showPreview(keyBounds: RectF, label: String) {
        isSelector = false
        candidates = listOf(label)
        val w = dp(56f).toInt()
        val h = dp(68f).toInt()
        show(keyBounds, w, h)
    }

    fun showSelector(keyBounds: RectF, alternates: List<String>) {
        isSelector = true
        candidates = alternates
        selectedIndex = 0
        cellWidth = dp(48f)
        val cols = minOf(alternates.size, 7)
        val w = (cols * cellWidth + dp(8f)).toInt()
        val h = (dp(54f) * ((alternates.size + 6) / 7) + dp(8f)).toInt()
        show(keyBounds, w, h)
    }

    private fun show(keyBounds: RectF, w: Int, h: Int) {
        val view = PopupView()
        contentView = view
        val pw = PopupWindow(view, w, h, false)
        pw.isClippingEnabled = true
        pw.elevation = dp(8f)
        window = pw

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        var x = (loc[0] + keyBounds.centerX() - w / 2f).toInt()
        val maxX = loc[0] + anchor.width - w
        x = x.coerceIn(loc[0], maxOf(loc[0], maxX))
        val y = (loc[1] + keyBounds.top - h - dp(4f)).toInt()
        popupScreenLeft = x
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    fun updateSelection(rawX: Float) {
        if (!isSelector) return
        val idx = ((rawX - popupScreenLeft - dp(4f)) / cellWidth).toInt()
        val clamped = idx.coerceIn(0, candidates.size - 1)
        if (clamped != selectedIndex) {
            selectedIndex = clamped
            contentView?.invalidate()
        }
    }

    fun selectedCandidate(): String? = candidates.getOrNull(selectedIndex)

    fun dismiss() {
        window?.dismiss()
        window = null
    }

    private inner class PopupView : View(anchor.context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.popupSurface }
        private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.keyPressed }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.keyText
            textAlign = Paint.Align.CENTER
            typeface = Typeface.SANS_SERIF
            textSize = sp(KeyboardMetrics.POPUP_CHAR_SP)
        }

        override fun onDraw(canvas: Canvas) {
            val r = dp(12f)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bgPaint)
            if (!isSelector) {
                canvas.drawText(
                    candidates.firstOrNull() ?: "",
                    width / 2f,
                    height / 2f + textPaint.textSize / 3,
                    textPaint,
                )
                return
            }
            val cellH = dp(54f)
            textPaint.textSize = sp(20f)
            for ((i, candidate) in candidates.withIndex()) {
                val col = i % 7
                val row = i / 7
                val left = dp(4f) + col * cellWidth
                val top = dp(4f) + row * cellH
                if (i == selectedIndex) {
                    canvas.drawRoundRect(
                        left, top, left + cellWidth, top + cellH, dp(8f), dp(8f), selPaint,
                    )
                }
                canvas.drawText(
                    candidate,
                    left + cellWidth / 2,
                    top + cellH / 2 + textPaint.textSize / 3,
                    textPaint,
                )
            }
        }
    }
}
