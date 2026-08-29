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

    interface Listener {
        fun onSuggestionPicked(suggestion: Suggestion)

        /** The clipboard chip in the left slot was tapped. */
        fun onClipboardChipPicked()
    }

    var listener: Listener? = null

    /** Display order: [1]=center/best, [0]=left, [2]=right. */
    private var slots: List<Suggestion?> = listOf(null, null, null)
    private var autocorrectInCenter = false
    private var pressedSlot = -1

    /**
     * Preview of a just-copied clip, shown in the left slot. Non-null only when
     * the host has confirmed there is no composing text: the typed literal must
     * always stay reachable in the strip, so the chip may never displace it.
     */
    private var clipboardChip: String? = null

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

    /**
     * Shows (or with null, hides) the clipboard chip. The host only passes text
     * here when nothing is composing, so this never costs the user a slot that
     * would otherwise hold their literal input.
     */
    fun setClipboardChip(preview: String?) {
        if (clipboardChip == preview) return
        clipboardChip = preview
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

        clipboardChip?.let { preview ->
            drawChip(canvas, preview, slotW)
        }

        for (i in 0..2) {
            if (i == 0 && clipboardChip != null) continue
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

    /**
     * Paperclip glyph plus a short preview, drawn as a filled pill so it reads
     * as a different kind of thing from the word suggestions beside it.
     */
    private fun drawChip(canvas: Canvas, preview: String, slotW: Float) {
        if (pressedSlot == 0) {
            pressPaint.color = theme.keyPressed.withAlphaFraction(0.6f)
        } else {
            pressPaint.color = theme.keySurface.withAlphaFraction(if (theme.isDark) 0.7f else 1f)
        }
        canvas.drawRoundRect(
            dp(6f), dp(6f), slotW - dp(6f), height - dp(6f), dp(14f), dp(14f), pressPaint,
        )

        textPaint.typeface = Typeface.SANS_SERIF
        textPaint.color = theme.suggestionText
        val glyphWidth = textPaint.measureText(CLIP_GLYPH) + dp(4f)
        val available = slotW - dp(24f) - glyphWidth
        val label = CLIP_GLYPH + " " + ellipsize(preview.take(CHIP_PREVIEW_CHARS), available)
        canvas.drawText(label, slotW / 2, height / 2f + textPaint.textSize / 3, textPaint)
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
                if (slot == 0 && clipboardChip != null || slots[slot] != null) {
                    pressedSlot = slot
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasPressed = pressedSlot
                val picked = slots.getOrNull(wasPressed)
                pressedSlot = -1
                invalidate()
                if (wasPressed == 0 && clipboardChip != null) {
                    listener?.onClipboardChipPicked()
                } else {
                    picked?.let { listener?.onSuggestionPicked(it) }
                }
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

    private companion object {
        /** U+1F4CE PAPERCLIP. Rendered by the system emoji font. */
        const val CLIP_GLYPH = "\uD83D\uDCCE"

        /** Roughly one strip slot's worth before ellipsizing takes over. */
        const val CHIP_PREVIEW_CHARS = 18
    }
}
