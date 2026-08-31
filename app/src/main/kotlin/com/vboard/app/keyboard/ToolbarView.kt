package com.vboard.app.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.animation.LinearInterpolator
import com.vboard.core.correct.FixButtonState

/**
 * The action bar that sits above the suggestion strip (VB-230).
 *
 * Drawn the way every other surface in this keyboard is drawn: one canvas, no
 * child views, so it costs a single measure/draw pass and cannot slow the cold
 * open. It hosts a list of [Action]s — "AI fix" is the first, and clipboard and
 * settings are meant to land beside it — each one a pill with an icon, a label,
 * and its own accessibility node.
 *
 * **Height: 40dp.** That is 4dp shorter than the suggestion strip so the row
 * reads as secondary to it, and it is the smallest height that still leaves a
 * comfortable target given each pill is ~90dp wide (Fitts' law does the work the
 * missing 8dp of height would have). Portrait keyboard body goes from 298dp to
 * 338dp with it. If that proves too expensive, the cheap fix is to show this row
 * only while the suggestion strip is empty — the two never carry information at
 * the same time — which costs zero height and no redesign.
 *
 * Accessibility: the view exposes one virtual node per action through an
 * [AccessibilityNodeProvider], so TalkBack announces and activates each pill
 * individually rather than reading "toolbar" and stopping there.
 */
@SuppressLint("ViewConstructor")
class ToolbarView(
    context: Context,
    private var theme: KeyboardTheme,
) : View(context) {

    /** Identity of a toolbar action. Add cases as the row grows. */
    enum class ActionId { AI_FIX, CLIPBOARD, SETTINGS }

    /** How an action is drawn right now. Mirrors the core-side [FixButtonState]. */
    enum class ActionState { IDLE, RUNNING, ACTIVE, DISABLED }

    enum class Glyph { SPARKLE, UNDO, CLIPBOARD, SETTINGS }

    /**
     * One pill. [contentDescription] is required, not optional: this view has no
     * child views for TalkBack to find, so an action without one is invisible to
     * anybody using a screen reader.
     */
    class Action(
        val id: ActionId,
        val glyph: Glyph,
        val label: String,
        val contentDescription: String,
        val state: ActionState = ActionState.IDLE,
    )

    fun interface Listener {
        fun onToolbarAction(id: ActionId)
    }

    var listener: Listener? = null
    var hapticsEnabled: Boolean = true

    private var actions: List<Action> = emptyList()
    private var cells: List<RectF> = emptyList()
    private var pressedIndex = -1

    /** Transient status line drawn in place of the pills; null when idle. */
    private var message: String? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = sp(LABEL_SP)
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SANS_SERIF
        textSize = sp(MESSAGE_SP)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPath = Path()
    private val arcBounds = RectF()

    private var spinnerSweep = 0f
    private var spinner: ValueAnimator? = null

    private val clearMessage = Runnable {
        message = null
        invalidate()
    }

    // --------------------------------------------------------------- public API

    fun setActions(newActions: List<Action>) {
        actions = newActions
        cells = emptyList()
        syncSpinner()
        requestLayout()
        invalidate()
    }

    /** Replaces one action in place, keeping the others and the layout. */
    fun updateAction(action: Action) {
        val index = actions.indexOfFirst { it.id == action.id }
        if (index < 0) return
        actions = actions.toMutableList().also { it[index] = action }
        cells = emptyList()
        syncSpinner()
        requestLayout()
        invalidate()
    }

    fun applyTheme(newTheme: KeyboardTheme) {
        theme = newTheme
        invalidate()
    }

    /**
     * Shows [text] across the row for a couple of seconds. This is the keyboard's
     * only status surface — an IME has no toast that lands above the input
     * method window — and it is why "AI fix" is never silent about what it did.
     */
    fun showMessage(text: String) {
        message = text
        removeCallbacks(clearMessage)
        postDelayed(clearMessage, MESSAGE_MS)
        invalidate()
        announceForAccessibility(text)
    }

    fun clearMessageNow() {
        removeCallbacks(clearMessage)
        message = null
        invalidate()
    }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            dp(HEIGHT_DP).toInt(),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cells = emptyList()
    }

    private fun ensureCells() {
        if (cells.size == actions.size && actions.isNotEmpty()) return
        // Height is what positions a pill, so there is nothing to compute before
        // the first layout pass — and the accessibility tree may ask early.
        if (actions.isEmpty() || width == 0 || height == 0) {
            cells = emptyList()
            return
        }
        val pillTop = dp(PILL_INSET_DP)
        val pillBottom = height - dp(PILL_INSET_DP)
        var x = dp(SIDE_PADDING_DP)
        cells = actions.map { action ->
            val labelWidth = labelPaint.measureText(action.label)
            val pillWidth =
                dp(ICON_SIZE_DP) + dp(ICON_GAP_DP) + labelWidth + 2 * dp(PILL_PADDING_DP)
            val rect = RectF(x, pillTop, x + pillWidth, pillBottom)
            x += pillWidth + dp(PILL_GAP_DP)
            rect
        }
    }

    // -------------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.bgKeyboard)
        val status = message
        if (status != null) {
            messagePaint.color = theme.keyTextSecondary
            canvas.drawText(
                ellipsize(status, width - 2 * dp(SIDE_PADDING_DP), messagePaint),
                width / 2f,
                height / 2f + messagePaint.textSize / 3,
                messagePaint,
            )
            return
        }
        ensureCells()
        for ((index, action) in actions.withIndex()) {
            val rect = cells.getOrNull(index) ?: continue
            drawPill(canvas, action, rect, index == pressedIndex)
        }
    }

    private fun drawPill(canvas: Canvas, action: Action, rect: RectF, pressed: Boolean) {
        val disabled = action.state == ActionState.DISABLED
        val active = action.state == ActionState.ACTIVE
        pillPaint.color = when {
            disabled -> theme.keySurfaceAlt.withAlphaFraction(0.4f)
            active -> theme.accent
            pressed -> theme.keyPressed
            else -> theme.keySurface
        }
        val radius = rect.height() / 2f
        canvas.drawRoundRect(rect, radius, radius, pillPaint)

        val foreground = when {
            disabled -> theme.keyTextSecondary.withAlphaFraction(0.45f)
            active -> theme.onAccent
            else -> theme.keyText
        }
        val iconCenterX = rect.left + dp(PILL_PADDING_DP) + dp(ICON_SIZE_DP) / 2f
        val centerY = rect.centerY()
        if (action.state == ActionState.RUNNING) {
            drawSpinner(canvas, iconCenterX, centerY, foreground)
        } else {
            drawGlyph(canvas, action.glyph, iconCenterX, centerY, foreground)
        }

        labelPaint.color = foreground
        canvas.drawText(
            action.label,
            rect.left + dp(PILL_PADDING_DP) + dp(ICON_SIZE_DP) + dp(ICON_GAP_DP),
            centerY + labelPaint.textSize / 3,
            labelPaint,
        )
    }

    private fun drawGlyph(canvas: Canvas, glyph: Glyph, cx: Float, cy: Float, color: Int) {
        when (glyph) {
            Glyph.SPARKLE -> {
                // Two four-point stars: the universal "this was cleaned up" mark.
                fillPaint.color = color
                drawStar(canvas, cx - dp(1.5f), cy - dp(1f), dp(6f))
                drawStar(canvas, cx + dp(4.5f), cy + dp(4.5f), dp(3f))
            }
            Glyph.CLIPBOARD -> {
                strokePaint.color = color
                val w = dp(4.5f)
                val h = dp(6.5f)
                // Board, then the clip sitting on its top edge.
                canvas.drawRoundRect(
                    cx - w, cy - h + dp(1.5f), cx + w, cy + h, dp(1.5f), dp(1.5f), strokePaint,
                )
                canvas.drawRoundRect(
                    cx - dp(2.5f), cy - h, cx + dp(2.5f), cy - h + dp(3f),
                    dp(1f), dp(1f), strokePaint,
                )
            }
            Glyph.SETTINGS -> {
                strokePaint.color = color
                // Two sliders: cheaper to draw than a cog and reads the same at
                // 18dp, where a cog's teeth turn into noise.
                for ((row, knobX) in listOf(-dp(3f) to dp(2f), dp(3f) to -dp(2f))) {
                    canvas.drawLine(cx - dp(6f), cy + row, cx + dp(6f), cy + row, strokePaint)
                    fillPaint.color = color
                    canvas.drawCircle(cx + knobX, cy + row, dp(2f), fillPaint)
                }
            }
            Glyph.UNDO -> {
                strokePaint.color = color
                val r = dp(5.5f)
                arcBounds.set(cx - r, cy - r, cx + r, cy + r)
                // Three quarters of a circle, opening at the arrow head.
                canvas.drawArc(arcBounds, 150f, 250f, false, strokePaint)
                iconPath.reset()
                iconPath.moveTo(cx - r - dp(2.5f), cy - dp(1.5f))
                iconPath.lineTo(cx - r + dp(1.5f), cy - dp(4.5f))
                iconPath.lineTo(cx - r + dp(3.5f), cy - dp(0.5f))
                canvas.drawPath(iconPath, strokePaint)
            }
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val waist = r * 0.28f
        iconPath.reset()
        iconPath.moveTo(cx, cy - r)
        iconPath.quadTo(cx + waist, cy - waist, cx + r, cy)
        iconPath.quadTo(cx + waist, cy + waist, cx, cy + r)
        iconPath.quadTo(cx - waist, cy + waist, cx - r, cy)
        iconPath.quadTo(cx - waist, cy - waist, cx, cy - r)
        iconPath.close()
        canvas.drawPath(iconPath, fillPaint)
    }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        strokePaint.color = color
        val r = dp(5.5f)
        arcBounds.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arcBounds, spinnerSweep, 100f, false, strokePaint)
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    // -------------------------------------------------------------- animation

    private fun syncSpinner() {
        val wanted = actions.any { it.state == ActionState.RUNNING } && isAttachedToWindow
        if (wanted && spinner == null) {
            spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = SPINNER_PERIOD_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    spinnerSweep = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!wanted) {
            spinner?.cancel()
            spinner = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncSpinner()
    }

    override fun onDetachedFromWindow() {
        // Nothing here stops on its own: an infinite animator on a detached view
        // is a heartbeat that keeps redrawing forever.
        spinner?.cancel()
        spinner = null
        removeCallbacks(clearMessage)
        super.onDetachedFromWindow()
    }

    // -------------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (actions.isEmpty()) return false
        ensureCells()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (message != null) {
                    // The pills are not on screen right now; dismiss the status
                    // line rather than firing an action the user cannot see.
                    clearMessageNow()
                    return true
                }
                val index = indexAt(event.x, event.y)
                if (index >= 0) {
                    // A disabled pill still takes the tap: it answers with the
                    // reason it is off, which is the whole point of drawing it
                    // greyed out instead of hiding it.
                    pressedIndex = index
                    if (actions[index].state != ActionState.DISABLED) {
                        if (hapticsEnabled) {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedIndex >= 0 && indexAt(event.x, event.y) != pressedIndex) {
                    pressedIndex = -1
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = pressedIndex
                pressedIndex = -1
                invalidate()
                actions.getOrNull(index)?.let { fire(it) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Hands the tap to the listener — including for a disabled action, so it can
     * say why. A running action is swallowed here so a second tap cannot start a
     * second fix even if the listener forgets to guard.
     */
    private fun fire(action: Action) {
        if (action.state == ActionState.RUNNING) return
        clearMessageNow()
        listener?.onToolbarAction(action.id)
    }

    /**
     * The pill under [x], within a small horizontal slop so a tap landing in the
     * gap between two pills still hits one. The whole row height counts, so the
     * 32dp pill has a 40dp target.
     */
    private fun indexAt(x: Float, y: Float): Int {
        val slop = dp(TOUCH_SLOP_DP)
        cells.forEachIndexed { index, rect ->
            if (x >= rect.left - slop && x <= rect.right + slop && y >= 0 && y <= height) {
                return index
            }
        }
        return -1
    }

    // ------------------------------------------------------------ accessibility

    private val nodeProvider = object : AccessibilityNodeProvider() {

        @Suppress("DEPRECATION") // obtain()/setBoundsInParent(): no framework-only successor at minSdk 29.
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            ensureCells()
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                val host = AccessibilityNodeInfo.obtain(this@ToolbarView)
                onInitializeAccessibilityNodeInfo(host)
                for (index in actions.indices) host.addChild(this@ToolbarView, index)
                return host
            }
            val action = actions.getOrNull(virtualViewId) ?: return null
            val rect = cells.getOrNull(virtualViewId) ?: return null
            val node = AccessibilityNodeInfo.obtain(this@ToolbarView, virtualViewId)
            node.className = "android.widget.Button"
            node.packageName = context.packageName
            node.contentDescription = action.contentDescription
            node.isEnabled = action.state != ActionState.DISABLED
            node.isClickable = action.state != ActionState.DISABLED
            node.isFocusable = true
            node.isVisibleToUser = true
            node.setParent(this@ToolbarView)
            if (node.isEnabled) node.addAction(AccessibilityNodeInfo.ACTION_CLICK)

            val bounds = Rect(
                rect.left.toInt(),
                rect.top.toInt(),
                rect.right.toInt(),
                rect.bottom.toInt(),
            )
            node.setBoundsInParent(bounds)
            val offset = IntArray(2)
            getLocationOnScreen(offset)
            bounds.offset(offset[0], offset[1])
            node.setBoundsInScreen(bounds)
            return node
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val target = actions.getOrNull(virtualViewId) ?: return false
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            if (target.state == ActionState.DISABLED) return false
            sendAccessibilityEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            fire(target)
            return true
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = nodeProvider

    private fun sendAccessibilityEventForVirtualView(virtualViewId: Int, eventType: Int) {
        val parent = parent ?: return
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(eventType)
        event.packageName = context.packageName
        event.className = "android.widget.Button"
        event.setSource(this, virtualViewId)
        actions.getOrNull(virtualViewId)?.let { event.text.add(it.contentDescription) }
        parent.requestSendAccessibilityEvent(this, event)
    }

    companion object {
        /** See the class docs: deliberately 4dp under the suggestion strip. */
        const val HEIGHT_DP = 40f

        private const val SIDE_PADDING_DP = 6f
        private const val PILL_INSET_DP = 4f
        private const val PILL_PADDING_DP = 12f
        private const val PILL_GAP_DP = 6f
        private const val ICON_SIZE_DP = 16f
        private const val ICON_GAP_DP = 7f
        private const val TOUCH_SLOP_DP = 3f
        private const val LABEL_SP = 14f
        private const val MESSAGE_SP = 13f
        private const val MESSAGE_MS = 2_800L
        private const val SPINNER_PERIOD_MS = 900L

        /** Maps the core button state onto how this view draws it. */
        fun stateFor(state: FixButtonState): ActionState = when (state) {
            FixButtonState.IDLE -> ActionState.IDLE
            FixButtonState.RUNNING -> ActionState.RUNNING
            FixButtonState.UNDO -> ActionState.ACTIVE
            FixButtonState.DISABLED -> ActionState.DISABLED
        }
    }
}
