package com.vboard.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vboard.app.R
import com.vboard.core.clipboard.ClipEntry

/**
 * The clipboard panel, swapped into the content frame the same way the emoji
 * panel is: "Pinned" and "Recent" sections drawn as a two-column card grid, with
 * an ABC/backspace bar underneath.
 *
 * Drawn on one canvas rather than as child views, matching [EmojiPanelView] —
 * a panel of 45 cards would otherwise inflate 45 views on every open.
 *
 * Nothing here logs, and nothing renders more of a clip than the card it fits.
 */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(
    context: Context,
    private var theme: KeyboardTheme,
    private val panelHeightPx: Int,
) : LinearLayout(context) {

    interface Listener {
        fun onClipPicked(entry: ClipEntry)
        fun onClipHeld(entry: ClipEntry)
        fun onBackToLetters()
        fun onBackspace()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val grid = ClipGrid()
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val bottomBar = BottomBar()

    /** Flattened render list: section headers interleaved with card rows. */
    private var cells: List<Cell> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.bgKeyboard)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46f).toInt()))
    }

    fun applyTheme(newTheme: KeyboardTheme) {
        theme = newTheme
        setBackgroundColor(theme.bgKeyboard)
        grid.invalidate()
        bottomBar.invalidate()
    }

    /** Rebuilds the panel from the current history. Cheap enough to call on any change. */
    fun setClips(pinned: List<ClipEntry>, recent: List<ClipEntry>) {
        val built = mutableListOf<Cell>()
        if (pinned.isNotEmpty()) {
            built.add(Cell.Header(resources.getString(R.string.clipboard_pinned)))
            pinned.chunked(COLUMNS).forEach { built.add(Cell.Row(it)) }
        }
        if (recent.isNotEmpty()) {
            built.add(Cell.Header(resources.getString(R.string.clipboard_recent)))
            recent.chunked(COLUMNS).forEach { built.add(Cell.Row(it)) }
        }
        cells = built
        grid.requestLayout()
        grid.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(panelHeightPx, MeasureSpec.EXACTLY),
        )
    }

    private sealed interface Cell {
        data class Header(val title: String) : Cell
        data class Row(val entries: List<ClipEntry>) : Cell
    }

    // ------------------------------------------------------------------- grid

    private inner class ClipGrid : View(context) {

        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
        }
        private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.SANS_SERIF
        }

        private val handler2 = Handler(Looper.getMainLooper())
        private var pressed: ClipEntry? = null
        private var longPressFired = false
        private var downX = 0f
        private var downY = 0f
        private val longPressRunnable = Runnable {
            val entry = pressed ?: return@Runnable
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onClipHeld(entry)
        }

        private val cardHeight get() = dp(CARD_HEIGHT_DP)
        private val headerHeight get() = dp(HEADER_HEIGHT_DP)
        private val gap get() = dp(CARD_GAP_DP)
        private val side get() = dp(PANEL_SIDE_PADDING_DP)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val height = if (cells.isEmpty()) {
                panelHeightPx - dp(46f).toInt()
            } else {
                (contentHeight() + gap).toInt()
            }
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), maxOf(height, 0))
        }

        private fun contentHeight(): Float {
            var y = gap
            for (cell in cells) {
                y += when (cell) {
                    is Cell.Header -> headerHeight
                    is Cell.Row -> cardHeight + gap
                }
            }
            return y
        }

        override fun onDraw(canvas: Canvas) {
            if (cells.isEmpty()) {
                drawEmptyState(canvas)
                return
            }
            val cardWidth = (width - 2 * side - gap) / COLUMNS
            var y = gap
            for (cell in cells) {
                when (cell) {
                    is Cell.Header -> {
                        headerPaint.textSize = sp(HEADER_SP)
                        headerPaint.color = theme.keyTextSecondary
                        canvas.drawText(
                            cell.title,
                            side,
                            y + headerHeight * 0.7f,
                            headerPaint,
                        )
                        y += headerHeight
                    }
                    is Cell.Row -> {
                        for ((column, entry) in cell.entries.withIndex()) {
                            val left = side + column * (cardWidth + gap)
                            drawCard(canvas, entry, RectF(left, y, left + cardWidth, y + cardHeight))
                        }
                        y += cardHeight + gap
                    }
                }
            }
        }

        private fun drawCard(canvas: Canvas, entry: ClipEntry, bounds: RectF) {
            cardPaint.color = if (pressed == entry) theme.keyPressed else theme.keySurface
            canvas.drawRoundRect(bounds, dp(10f), dp(10f), cardPaint)

            textPaint.textSize = sp(CARD_TEXT_SP)
            textPaint.color = theme.keyText
            val innerLeft = bounds.left + dp(10f)
            val innerWidth = bounds.width() - dp(20f)
            // A clip may be thousands of characters; only ever measure the few
            // that could possibly fit, and never touch the rest.
            val preview = entry.text.replace('\n', ' ').take(CARD_PREVIEW_CHARS)
            var top = bounds.top + dp(16f)
            var offset = 0
            for (line in 0 until CARD_LINES) {
                if (offset >= preview.length) break
                val isLast = line == CARD_LINES - 1
                val remaining = preview.substring(offset)
                val fits = textPaint.breakText(remaining, true, innerWidth, null)
                val shown = if (isLast && offset + fits < preview.length) {
                    ellipsizeTo(remaining, innerWidth)
                } else {
                    remaining.substring(0, fits)
                }
                canvas.drawText(shown, innerLeft, top, textPaint)
                top += textPaint.textSize * 1.3f
                offset += if (fits > 0) fits else remaining.length
            }

            if (entry.pinned) {
                textPaint.textSize = sp(PIN_SP)
                textPaint.color = theme.accent
                canvas.drawText(
                    PIN_GLYPH,
                    bounds.right - dp(20f),
                    bounds.top + dp(16f),
                    textPaint,
                )
            }
        }

        private fun ellipsizeTo(text: String, maxWidth: Float): String {
            var t = text.substring(0, textPaint.breakText(text, true, maxWidth, null))
            while (t.isNotEmpty() && textPaint.measureText("$t…") > maxWidth) {
                t = t.dropLast(1)
            }
            return "$t…"
        }

        private fun drawEmptyState(canvas: Canvas) {
            emptyPaint.textSize = sp(EMPTY_SP)
            emptyPaint.color = theme.keyTextSecondary
            val cx = width / 2f
            var y = height / 2f - emptyPaint.textSize
            val lines = listOf(
                resources.getString(R.string.clipboard_empty_line1),
                resources.getString(R.string.clipboard_empty_line2),
            )
            for (line in lines) {
                canvas.drawText(line, cx, y, emptyPaint)
                y += emptyPaint.textSize * 1.5f
            }
        }

        private fun entryAt(x: Float, y: Float): ClipEntry? {
            val cardWidth = (width - 2 * side - gap) / COLUMNS
            var top = gap
            for (cell in cells) {
                when (cell) {
                    is Cell.Header -> top += headerHeight
                    is Cell.Row -> {
                        if (y >= top && y <= top + cardHeight) {
                            for ((column, entry) in cell.entries.withIndex()) {
                                val left = side + column * (cardWidth + gap)
                                if (x >= left && x <= left + cardWidth) return entry
                            }
                            return null
                        }
                        top += cardHeight + gap
                    }
                }
            }
            return null
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = entryAt(event.x, event.y)
                    longPressFired = false
                    downX = event.x
                    downY = event.y
                    if (pressed != null) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        invalidate()
                        handler2.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // A scroll gesture must not fire a long-press on the card
                    // the finger started on.
                    val slop = dp(12f)
                    if (kotlin.math.abs(event.x - downX) > slop ||
                        kotlin.math.abs(event.y - downY) > slop
                    ) {
                        cancelPress()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    handler2.removeCallbacks(longPressRunnable)
                    val entry = pressed
                    pressed = null
                    invalidate()
                    if (entry != null && !longPressFired) listener?.onClipPicked(entry)
                    longPressFired = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelPress()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun cancelPress() {
            handler2.removeCallbacks(longPressRunnable)
            if (pressed != null) {
                pressed = null
                invalidate()
            }
        }

        override fun onDetachedFromWindow() {
            handler2.removeCallbacks(longPressRunnable)
            super.onDetachedFromWindow()
        }
    }

    // ------------------------------------------------------------- bottom bar

    private inner class BottomBar : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = sp(15f)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(theme.suggestionBg)
            paint.color = theme.keyText
            canvas.drawText("ABC", width * 0.15f, height / 2f + paint.textSize / 3, paint)
            canvas.drawText("⌫", width * 0.85f, height / 2f + paint.textSize / 3, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (event.x < width / 2f) listener?.onBackToLetters() else listener?.onBackspace()
            }
            return true
        }
    }

    private companion object {
        const val COLUMNS = 2
        const val CARD_HEIGHT_DP = 78f
        const val CARD_GAP_DP = 8f
        const val HEADER_HEIGHT_DP = 30f
        const val PANEL_SIDE_PADDING_DP = 10f
        const val CARD_TEXT_SP = 14f
        const val HEADER_SP = 12f
        const val EMPTY_SP = 13f
        const val PIN_SP = 12f
        const val CARD_LINES = 3

        /** Far more than three lines can hold; the rest is never measured. */
        const val CARD_PREVIEW_CHARS = 240
        const val LONG_PRESS_MS = 400L

        /** U+1F4CC PUSHPIN. */
        const val PIN_GLYPH = "📌"
    }
}
