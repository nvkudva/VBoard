package com.vboard.app.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider
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

    /**
     * Invoked when a candidate is chosen through accessibility — a double-tap on
     * one of the popup's virtual nodes, or an explore-by-touch lift over it.
     * The sighted slide-and-lift gesture is resolved by [KeyboardView] instead,
     * from [selectedCandidate] on its own ACTION_UP.
     */
    var onCandidateChosen: ((String) -> Unit)? = null

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
        val cols = minOf(alternates.size, COLUMNS)
        val w = (cols * cellWidth + dp(8f)).toInt()
        val h = (dp(54f) * ((alternates.size + COLUMNS - 1) / COLUMNS) + dp(8f)).toInt()
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

    /** Test seam: the popup's content view, which carries the node provider. */
    internal fun contentViewForTest(): View? = contentView

    private inner class PopupView : View(anchor.context) {
        init {
            // A preview bubble is decoration over the key the user just touched;
            // only the alternates grid is navigable.
            importantForAccessibility = if (isSelector) {
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }

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
            textPaint.textSize = sp(20f)
            for ((i, candidate) in candidates.withIndex()) {
                val cell = candidateBounds(i) ?: continue
                if (i == selectedIndex) {
                    canvas.drawRoundRect(cell, dp(8f), dp(8f), selPaint)
                }
                canvas.drawText(
                    candidate,
                    cell.centerX(),
                    cell.centerY() + textPaint.textSize / 3,
                    textPaint,
                )
            }
        }

        /** Grid geometry, shared by [onDraw] and the accessibility nodes. */
        private fun candidateBounds(index: Int): RectF? {
            if (!isSelector || index !in candidates.indices) return null
            val cellH = dp(54f)
            val left = dp(4f) + (index % COLUMNS) * cellWidth
            val top = dp(4f) + (index / COLUMNS) * cellH
            return RectF(left, top, left + cellWidth, top + cellH)
        }

        // -------------------------------------------------------- accessibility

        /**
         * One node per alternate, so a screen-reader user can move through the
         * candidates and pick one. Without this the popup is a blank rectangle
         * and long-press is unreachable without sight.
         */
        private val a11y = object : VirtualCells(this) {
            override fun count(): Int = if (isSelector) candidates.size else 0

            override fun boundsOf(id: Int): RectF? = candidateBounds(id)

            override fun descriptionOf(id: Int): CharSequence? = candidates.getOrNull(id)

            override fun click(id: Int): Boolean {
                val candidate = candidates.getOrNull(id) ?: return false
                onCandidateChosen?.invoke(candidate)
                return true
            }

            override fun onHoverChanged(id: Int) {
                if (id == NO_CELL) return
                if (id != selectedIndex) {
                    selectedIndex = id
                    invalidate()
                }
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            override fun onAccessibilityFocusChanged(id: Int) {
                if (id != selectedIndex) {
                    selectedIndex = id
                    invalidate()
                }
            }

            /** Lift-to-type over the alternates grid, matching the key grid. */
            override fun onHoverLift(id: Int) {
                candidates.getOrNull(id)?.let { onCandidateChosen?.invoke(it) }
            }
        }

        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = a11y.provider

        override fun onHoverEvent(event: MotionEvent): Boolean =
            if (a11y.onHover(event)) true else super.onHoverEvent(event)
    }

    private companion object {
        /** Candidates per popup row; must match [showSelector]'s width maths. */
        const val COLUMNS = 7
    }
}
