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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeProvider
import android.view.inputmethod.InputMethodManager
import com.vboard.app.R
import com.vboard.core.keyboard.KeyboardHeights

/**
 * Custom-drawn keyboard (all layers except emoji). One view draws every key:
 * no per-key child views, so cold-open and tap latency stay minimal.
 *
 * Interaction: tap commit, long-press popup with slide-to-select alternates,
 * backspace auto-repeat, spacebar horizontal drag moves the cursor,
 * shift tap/double-tap(caps)/shift states.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private var theme: KeyboardTheme,
) : View(context) {

    interface Listener {
        fun onKeyAction(action: KeyAction, shifted: Boolean)
        fun onKeyLongPressText(text: String)
        fun onSpacebarCursorMove(steps: Int)
        fun onShiftChanged(state: ShiftState)
    }

    enum class ShiftState { OFF, SHIFT, CAPS_LOCK }

    var listener: Listener? = null

    var layout: KeyboardLayout = KeyboardLayouts.LETTERS
        set(value) {
            field = value
            keyBounds = emptyList()
            // The whole virtual node tree just changed under any screen reader
            // holding focus in it.
            a11y.reset()
            a11y.notifyContentChanged()
            requestLayout()
            invalidate()
        }

    var shiftState: ShiftState = ShiftState.OFF
        set(value) {
            if (field != value) {
                field = value
                // Letter descriptions and the shift key's own description both
                // change with this.
                a11y.notifyContentChanged()
                invalidate()
            }
        }

    var enterIcon: KeyIcon = KeyIcon.ENTER
        set(value) {
            if (field != value) {
                field = value
                a11y.notifyContentChanged()
                invalidate()
            }
        }

    var spacebarLabel: String = "VBoard · EN"
        set(value) {
            field = value
            invalidate()
        }

    var hapticsEnabled: Boolean = true
    var keyPreviewEnabled: Boolean = true
    var micEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private data class KeyBound(val key: Key, val bounds: RectF, val row: Int)

    private var keyBounds: List<KeyBound> = emptyList()
    private var pressedKey: KeyBound? = null
    private var lastShiftTapTime = 0L

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SANS_SERIF
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.SANS_SERIF
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val handler2 = Handler(Looper.getMainLooper())
    private var popup: KeyPopup? = null
    private var longPressFired = false

    /**
     * Set when a key already emitted its action on ACTION_DOWN (backspace, which
     * fires immediately and then repeats). Without it the release path saw no
     * long-press and ran handleTap as well, so one tap deleted two characters —
     * and an autocorrect revert lost a letter, "the" -> "teh" -> "te".
     */
    private var actionFiredOnDown = false
    private val longPressRunnable = Runnable { onLongPress() }
    private var repeatRunnable: Runnable? = null

    // Spacebar cursor control
    private var spaceDragOriginX = 0f
    private var spaceDragSteps = 0
    private var spaceDragging = false

    fun applyTheme(newTheme: KeyboardTheme) {
        theme = newTheme
        invalidate()
    }

    /**
     * Row height for the current layout. Five-row layouts (the number row is on)
     * are scaled down by [KeyboardHeights.COMPACT_ROW_FACTOR] so the extra row
     * costs under 40dp of keyboard rather than a whole row's worth.
     */
    val rowHeightPx: Float
        get() {
            val screenHeightDp = resources.configuration.screenHeightDp.toFloat()
            val landscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val baseDp = if (landscape) {
                KeyboardMetrics.ROW_HEIGHT_LANDSCAPE_DP
            } else {
                (screenHeightDp * KeyboardMetrics.ROW_HEIGHT_FRACTION)
                    .coerceIn(KeyboardMetrics.ROW_HEIGHT_MIN_DP, KeyboardMetrics.ROW_HEIGHT_MAX_DP)
            }
            return dp(KeyboardHeights.rowHeightDp(baseDp, layout.rows.size))
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = layout.rows.size
        val height = (dp(KeyboardMetrics.TOP_PADDING_DP) +
            rows * rowHeightPx +
            (rows - 1) * dp(KeyboardMetrics.KEY_GAP_V_DP) +
            dp(KeyboardMetrics.BOTTOM_PADDING_DP)).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeKeyBounds()
    }

    private fun computeKeyBounds() {
        if (width == 0) return
        val bounds = mutableListOf<KeyBound>()
        val side = dp(KeyboardMetrics.SIDE_PADDING_DP)
        val gapH = dp(KeyboardMetrics.KEY_GAP_H_DP)
        val gapV = dp(KeyboardMetrics.KEY_GAP_V_DP)
        val rowH = rowHeightPx
        var top = dp(KeyboardMetrics.TOP_PADDING_DP)
        val usable = width - 2 * side
        for ((rowIndex, row) in layout.rows.withIndex()) {
            val units = row.leftPadUnits + row.rightPadUnits + row.keys.sumOf { it.widthUnits.toDouble() }.toFloat()
            val gapTotal = gapH * (row.keys.size - 1)
            val unitW = (usable - gapTotal) / units
            var x = side + row.leftPadUnits * unitW
            for (key in row.keys) {
                val w = key.widthUnits * unitW
                bounds.add(KeyBound(key, RectF(x, top, x + w, top + rowH), rowIndex))
                x += w + gapH
            }
            top += rowH + gapV
        }
        keyBounds = bounds
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.bgKeyboard)
        if (keyBounds.isEmpty()) computeKeyBounds()
        val radius = dp(KeyboardMetrics.KEY_RADIUS_DP)
        val spaceRadius = dp(KeyboardMetrics.SPACE_RADIUS_DP)
        for (kb in keyBounds) {
            val key = kb.key
            val pressed = pressedKey === kb
            val disabledMic = key.action == KeyAction.Mic && !micEnabled
            keyPaint.color = when {
                key.isAccent && !disabledMic -> if (pressed) theme.micPulse else theme.accent
                key.isAccent -> theme.keySurfaceAlt
                pressed && key.isFunction -> theme.keyPressedAlt
                pressed -> theme.keyPressed
                key.action == KeyAction.Shift && shiftState != ShiftState.OFF -> theme.keySurfaceAlt
                key.isFunction -> theme.keySurfaceAlt
                else -> theme.keySurface
            }
            val r = if (key.action == KeyAction.Space) spaceRadius else radius
            canvas.drawRoundRect(kb.bounds, r, r, keyPaint)
            drawKeyContent(canvas, kb, disabledMic)
        }
    }

    private fun drawKeyContent(canvas: Canvas, kb: KeyBound, disabledMic: Boolean) {
        val key = kb.key
        val cx = kb.bounds.centerX()
        val cy = kb.bounds.centerY()
        val onAccent = key.isAccent && !disabledMic
        val contentColor = when {
            onAccent -> theme.onAccent
            disabledMic -> theme.keyTextSecondary
            else -> theme.keyText
        }

        when {
            key.action == KeyAction.Space -> {
                labelPaint.textSize = sp(KeyboardMetrics.SPACEBAR_LABEL_SP)
                labelPaint.color = theme.keyTextSecondary
                labelPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                canvas.drawText(spacebarLabel, cx, cy + labelPaint.textSize / 3, labelPaint)
                labelPaint.typeface = Typeface.SANS_SERIF
            }
            key.icon != KeyIcon.NONE -> drawIcon(canvas, resolveIcon(key), cx, cy, contentColor)
            else -> {
                val small = key.label.length > 1 || layoutIsSymbols()
                labelPaint.textSize =
                    if (small) sp(KeyboardMetrics.KEY_LABEL_SMALL_SP) else sp(KeyboardMetrics.KEY_LABEL_SP)
                labelPaint.color = contentColor
                val label = displayLabel(key)
                canvas.drawText(label, cx, cy + labelPaint.textSize / 3, labelPaint)
                key.hint?.let {
                    hintPaint.textSize = sp(KeyboardMetrics.HINT_SP)
                    hintPaint.color = theme.keyTextSecondary
                    canvas.drawText(it, kb.bounds.right - dp(6f), kb.bounds.top + dp(14f), hintPaint)
                }
            }
        }
    }

    private fun resolveIcon(key: Key): KeyIcon = when (key.action) {
        KeyAction.Shift -> when (shiftState) {
            ShiftState.OFF -> KeyIcon.SHIFT
            ShiftState.SHIFT -> KeyIcon.SHIFT_FILLED
            ShiftState.CAPS_LOCK -> KeyIcon.CAPS_LOCK
        }
        KeyAction.Enter -> enterIcon
        else -> key.icon
    }

    private fun layoutIsSymbols(): Boolean = layout.layer != KeyboardLayer.LETTERS

    private fun displayLabel(key: Key): String {
        val label = key.label
        return if (shiftState != ShiftState.OFF && layout.layer == KeyboardLayer.LETTERS) {
            label.uppercase()
        } else {
            label
        }
    }

    /** Simple vector glyphs drawn with paths — avoids drawable inflation per frame. */
    private fun drawIcon(canvas: Canvas, icon: KeyIcon, cx: Float, cy: Float, color: Int) {
        iconPaint.color = color
        iconFillPaint.color = color
        val s = dp(9f)
        when (icon) {
            KeyIcon.BACKSPACE -> {
                val p = android.graphics.Path()
                p.moveTo(cx - s, cy)
                p.lineTo(cx - s * 0.2f, cy - s * 0.75f)
                p.lineTo(cx + s, cy - s * 0.75f)
                p.lineTo(cx + s, cy + s * 0.75f)
                p.lineTo(cx - s * 0.2f, cy + s * 0.75f)
                p.close()
                canvas.drawPath(p, iconPaint)
                val xs = s * 0.32f
                canvas.drawLine(cx - xs + s * 0.25f, cy - xs, cx + xs + s * 0.25f, cy + xs, iconPaint)
                canvas.drawLine(cx - xs + s * 0.25f, cy + xs, cx + xs + s * 0.25f, cy - xs, iconPaint)
            }
            KeyIcon.SHIFT, KeyIcon.SHIFT_FILLED, KeyIcon.CAPS_LOCK -> {
                val p = android.graphics.Path()
                p.moveTo(cx, cy - s)
                p.lineTo(cx + s * 0.9f, cy + s * 0.1f)
                p.lineTo(cx + s * 0.45f, cy + s * 0.1f)
                p.lineTo(cx + s * 0.45f, cy + s * 0.7f)
                p.lineTo(cx - s * 0.45f, cy + s * 0.7f)
                p.lineTo(cx - s * 0.45f, cy + s * 0.1f)
                p.lineTo(cx - s * 0.9f, cy + s * 0.1f)
                p.close()
                if (icon == KeyIcon.SHIFT) {
                    canvas.drawPath(p, iconPaint)
                } else {
                    canvas.drawPath(p, iconFillPaint)
                }
                if (icon == KeyIcon.CAPS_LOCK) {
                    canvas.drawLine(cx - s * 0.45f, cy + s * 1.05f, cx + s * 0.45f, cy + s * 1.05f, iconPaint)
                }
            }
            KeyIcon.ENTER -> {
                val p = android.graphics.Path()
                p.moveTo(cx + s, cy - s * 0.7f)
                p.lineTo(cx + s, cy + s * 0.35f)
                p.lineTo(cx - s * 0.5f, cy + s * 0.35f)
                canvas.drawPath(p, iconPaint)
                p.reset()
                p.moveTo(cx - s * 0.15f, cy - s * 0.25f)
                p.lineTo(cx - s * 0.75f, cy + s * 0.35f)
                p.lineTo(cx - s * 0.15f, cy + s * 0.95f)
                canvas.drawPath(p, iconPaint)
            }
            KeyIcon.SEARCH -> {
                canvas.drawCircle(cx - s * 0.2f, cy - s * 0.2f, s * 0.6f, iconPaint)
                canvas.drawLine(cx + s * 0.25f, cy + s * 0.25f, cx + s * 0.8f, cy + s * 0.8f, iconPaint)
            }
            KeyIcon.SEND -> {
                val p = android.graphics.Path()
                p.moveTo(cx - s * 0.8f, cy - s * 0.7f)
                p.lineTo(cx + s * 0.9f, cy)
                p.lineTo(cx - s * 0.8f, cy + s * 0.7f)
                p.lineTo(cx - s * 0.4f, cy)
                p.close()
                canvas.drawPath(p, iconFillPaint)
            }
            KeyIcon.MIC -> {
                val mw = s * 0.42f
                canvas.drawRoundRect(
                    cx - mw, cy - s * 0.95f, cx + mw, cy + s * 0.15f, mw, mw, iconFillPaint,
                )
                val arc = RectF(cx - s * 0.75f, cy - s * 0.55f, cx + s * 0.75f, cy + s * 0.5f)
                canvas.drawArc(arc, 0f, 180f, false, iconPaint)
                canvas.drawLine(cx, cy + s * 0.5f, cx, cy + s * 0.95f, iconPaint)
            }
            KeyIcon.EMOJI -> {
                canvas.drawCircle(cx, cy, s * 0.85f, iconPaint)
                iconFillPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx - s * 0.3f, cy - s * 0.25f, dp(1.2f), iconFillPaint)
                canvas.drawCircle(cx + s * 0.3f, cy - s * 0.25f, dp(1.2f), iconFillPaint)
                val arc = RectF(cx - s * 0.45f, cy - s * 0.2f, cx + s * 0.45f, cy + s * 0.5f)
                canvas.drawArc(arc, 20f, 140f, false, iconPaint)
            }
            KeyIcon.GLOBE -> {
                canvas.drawCircle(cx, cy, s * 0.85f, iconPaint)
                canvas.drawLine(cx - s * 0.85f, cy, cx + s * 0.85f, cy, iconPaint)
                val oval = RectF(cx - s * 0.4f, cy - s * 0.85f, cx + s * 0.4f, cy + s * 0.85f)
                canvas.drawOval(oval, iconPaint)
            }
            KeyIcon.NONE -> Unit
        }
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val kb = keyAt(event.x, event.y) ?: return true
                pressedKey = kb
                longPressFired = false
                actionFiredOnDown = false
                invalidate()
                if (hapticsEnabled) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                if (kb.key.action is KeyAction.Text && keyPreviewEnabled && !kb.key.isFunction) {
                    showPreview(kb)
                }
                when {
                    kb.key.action == KeyAction.Backspace -> {
                        // Backspace deletes on press and then auto-repeats, so the
                        // release must not emit it a second time.
                        actionFiredOnDown = true
                        startRepeat()
                    }
                    kb.key.action == KeyAction.Space -> {
                        spaceDragOriginX = event.x
                        spaceDragSteps = 0
                        spaceDragging = false
                        handler2.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                    kb.key.longPress.isNotEmpty() ||
                        kb.key.longPressAction != null ||
                        kb.key.action == KeyAction.Mic ->
                        handler2.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val kb = pressedKey ?: return true
                popup?.let { p ->
                    if (p.isSelector) {
                        p.updateSelection(event.rawX)
                        return true
                    }
                }
                if (kb.key.action == KeyAction.Space) {
                    val deltaX = event.x - spaceDragOriginX
                    val stepPx = dp(14f)
                    val steps = (deltaX / stepPx).toInt()
                    if (steps != spaceDragSteps) {
                        if (!spaceDragging) {
                            spaceDragging = true
                            handler2.removeCallbacks(longPressRunnable)
                        }
                        listener?.onSpacebarCursorMove(steps - spaceDragSteps)
                        spaceDragSteps = steps
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val kb = pressedKey
                cancelTimers()
                val selector = popup?.takeIf { it.isSelector }
                if (selector != null) {
                    val chosen = selector.selectedCandidate()
                    dismissPopup()
                    if (chosen != null) listener?.onKeyLongPressText(chosen)
                } else if (kb != null && !longPressFired && !spaceDragging && !actionFiredOnDown) {
                    dismissPopup()
                    handleTap(kb.key)
                } else {
                    dismissPopup()
                }
                actionFiredOnDown = false
                spaceDragging = false
                pressedKey = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTimers()
                dismissPopup()
                pressedKey = null
                actionFiredOnDown = false
                spaceDragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Nearest-key hit test: touches in gaps are assigned to the closest key. */
    private fun keyAt(x: Float, y: Float): KeyBound? {
        var best: KeyBound? = null
        var bestDist = Float.MAX_VALUE
        for (kb in keyBounds) {
            if (kb.bounds.contains(x, y)) return kb
            val dx = maxOf(kb.bounds.left - x, 0f, x - kb.bounds.right)
            val dy = maxOf(kb.bounds.top - y, 0f, y - kb.bounds.bottom)
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = kb
            }
        }
        // Only accept gap touches reasonably close to a key (within one gap).
        return if (bestDist <= dp(12f) * dp(12f)) best else null
    }

    private fun handleTap(key: Key) {
        when (key.action) {
            KeyAction.Shift -> {
                val now = System.currentTimeMillis()
                shiftState = when {
                    shiftState == ShiftState.OFF && now - lastShiftTapTime < DOUBLE_TAP_MS ->
                        ShiftState.CAPS_LOCK
                    shiftState == ShiftState.OFF -> ShiftState.SHIFT
                    shiftState == ShiftState.SHIFT &&
                        now - lastShiftTapTime < DOUBLE_TAP_MS -> ShiftState.CAPS_LOCK
                    else -> ShiftState.OFF
                }
                lastShiftTapTime = now
                listener?.onShiftChanged(shiftState)
            }
            else -> listener?.onKeyAction(key.action, shiftState != ShiftState.OFF)
        }
    }

    /** Consumes one shot of shift after a letter (unless caps-locked). */
    fun consumeShift() {
        if (shiftState == ShiftState.SHIFT) {
            shiftState = ShiftState.OFF
            listener?.onShiftChanged(shiftState)
        }
    }

    private fun onLongPress() {
        val kb = pressedKey ?: return
        longPressFired = true
        val heldAction = kb.key.longPressAction
        when {
            kb.key.action == KeyAction.Mic -> listener?.onKeyAction(KeyAction.Mic, false)
            kb.key.action == KeyAction.Space -> {
                // Long-press space: no-op for v1 (reserved for IME switcher).
            }
            // A held key with its own action (?123 -> clipboard) fires it instead
            // of opening a candidate popup.
            heldAction != null -> {
                dismissPopup()
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener?.onKeyAction(heldAction, false)
            }
            kb.key.longPress.isNotEmpty() -> {
                dismissPopup()
                val candidates = if (shiftState != ShiftState.OFF && layout.layer == KeyboardLayer.LETTERS) {
                    kb.key.longPress.map { it.uppercase() }
                } else {
                    kb.key.longPress
                }
                popup = KeyPopup(this, theme).also {
                    it.showSelector(kb.bounds, candidates)
                }
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    private fun showPreview(kb: KeyBound) {
        dismissPopup()
        popup = KeyPopup(this, theme).also {
            it.showPreview(kb.bounds, displayLabel(kb.key))
        }
    }

    private fun startRepeat() {
        listener?.onKeyAction(KeyAction.Backspace, false)
        val r = object : Runnable {
            var interval = REPEAT_START_MS
            override fun run() {
                listener?.onKeyAction(KeyAction.Backspace, false)
                interval = maxOf(REPEAT_MIN_MS, interval - 8L)
                handler2.postDelayed(this, interval)
            }
        }
        repeatRunnable = r
        handler2.postDelayed(r, REPEAT_INITIAL_DELAY_MS)
    }

    private fun cancelTimers() {
        handler2.removeCallbacks(longPressRunnable)
        repeatRunnable?.let { handler2.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun dismissPopup() {
        popup?.dismiss()
        popup = null
    }

    override fun onDetachedFromWindow() {
        cancelTimers()
        dismissPopup()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val LONG_PRESS_MS = 350L
        private const val DOUBLE_TAP_MS = 350L
        private const val REPEAT_INITIAL_DELAY_MS = 400L
        private const val REPEAT_START_MS = 90L
        private const val REPEAT_MIN_MS = 34L
    }
}
