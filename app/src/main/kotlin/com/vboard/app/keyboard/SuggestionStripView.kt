package com.vboard.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.vboard.core.suggest.Suggestion

/**
 * Three-slot suggestion strip (DESIGN_SPEC §6): center slot is the best
 * candidate (bold + accent when it will autocorrect), sides are alternates.
 */
@SuppressLint("ViewConstructor")
class SuggestionStripView(
    context: Context,
    private var theme: KeyboardTheme,
) : View(context) {

    fun interface Listener {
        fun onSuggestionPicked(suggestion: Suggestion)
    }

    var listener: Listener? = null

    /** Display order: [1]=center/best, [0]=left, [2]=right. */
    private var slots: List<Suggestion?> = listOf(null, null, null)
    private var autocorrectInCenter = false
    private var pressedSlot = -1

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, KeyboardMetrics.SUGGESTION_SP, resources.displayMetrics,
        )
    }
    private val dividerPaint = Paint().apply { strokeWidth = dp(1f) }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun applyTheme(newTheme: KeyboardTheme) {
        theme = newTheme
        invalidate()
    }

    fun setSuggestions(ranked: List<Suggestion>, autocorrect: Boolean) {
        slots = listOf(ranked.getOrNull(1), ranked.getOrNull(0), ranked.getOrNull(2))
        autocorrectInCenter = autocorrect && ranked.isNotEmpty()
        invalidate()
    }

    fun clearSuggestions() {
        slots = listOf(null, null, null)
        autocorrectInCenter = false
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            dp(KeyboardMetrics.STRIP_HEIGHT_DP).toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.suggestionBg)
        val slotW = width / 3f
        dividerPaint.color = theme.keyTextSecondary.withAlphaFraction(0.25f)
        canvas.drawLine(slotW, dp(10f), slotW, height - dp(10f), dividerPaint)
        canvas.drawLine(2 * slotW, dp(10f), 2 * slotW, height - dp(10f), dividerPaint)

        for (i in 0..2) {
            val suggestion = slots[i] ?: continue
            if (i == pressedSlot) {
                pressPaint.color = theme.keyPressed.withAlphaFraction(0.6f)
                canvas.drawRoundRect(
                    i * slotW + dp(4f), dp(4f), (i + 1) * slotW - dp(4f), height - dp(4f),
                    dp(16f), dp(16f), pressPaint,
                )
            }
            val isCenter = i == 1
            textPaint.typeface = if (isCenter) {
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            } else {
                Typeface.SANS_SERIF
            }
            textPaint.color = when {
                isCenter && autocorrectInCenter -> theme.suggestionAutocorrect
                else -> theme.suggestionText
            }
            val text = ellipsize(suggestion.text, slotW - dp(16f))
            canvas.drawText(text, i * slotW + slotW / 2, height / 2f + textPaint.textSize / 3, textPaint)
        }
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && textPaint.measureText("$t…") > maxWidth) {
            t = t.dropLast(1)
        }
        return "$t…"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val slot = (event.x / (width / 3f)).toInt().coerceIn(0, 2)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (slots[slot] != null) {
                    pressedSlot = slot
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val picked = slots.getOrNull(pressedSlot)
                pressedSlot = -1
                invalidate()
                picked?.let { listener?.onSuggestionPicked(it) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedSlot = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
