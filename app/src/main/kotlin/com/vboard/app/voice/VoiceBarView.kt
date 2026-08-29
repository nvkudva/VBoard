package com.vboard.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.vboard.app.R
import com.vboard.app.keyboard.KeyboardTheme
import com.vboard.app.keyboard.withAlphaFraction
import kotlin.math.min
import kotlin.math.sin

/**
 * The compact Gboard-style voice bar (DESIGN_SPEC §4): a 120dp surface with a
 * two-line live transcript above a control row hosting the amplitude-reactive
 * orb. States: listening / finalizing / refining ("Cleaning ✨") / error.
 */
@SuppressLint("ViewConstructor")
class VoiceBarView(
    context: Context,
    private var theme: KeyboardTheme,
) : View(context) {

    interface Listener {
        fun onOrbTapped()
        fun onBackToKeyboard()
        fun onErrorAction(kind: ErrorActionKind)
    }

    enum class ErrorActionKind { OPEN_PERMISSION, OPEN_DOWNLOAD, DISMISS }

    private enum class Mode { LISTENING, FINALIZING, REFINING, ERROR }

    var listener: Listener? = null

    private var mode = Mode.LISTENING
    private var partialText: String = ""
    private var committedTail: String = ""
    private var errorMessage: String = ""
    private var errorAction: ErrorActionKind = ErrorActionKind.DISMISS

    /** Smoothed RMS amplitude 0..1 driving the orb halo. */
    private var amplitude = 0f
    private var haloLevel = 0f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val transcriptPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(18f)
        typeface = Typeface.SANS_SERIF
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        typeface = Typeface.SANS_SERIF
        textAlign = Paint.Align.CENTER
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val breathAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    fun applyTheme(newTheme: KeyboardTheme) {
        theme = newTheme
        invalidate()
    }

    // ------------------------------------------------------------ public API

    fun showListening() {
        mode = Mode.LISTENING
        errorMessage = ""
        startBreathing()
        invalidate()
    }

    fun showPartial(text: String) {
        partialText = text
        if (mode != Mode.LISTENING) mode = Mode.LISTENING
        invalidate()
    }

    fun showCommitted(text: String) {
        committedTail = text
        partialText = ""
        invalidate()
    }

    fun showFinalizing() {
        mode = Mode.FINALIZING
        invalidate()
    }

    fun showRefining() {
        mode = Mode.REFINING
        invalidate()
    }

    fun showError(message: String, action: ErrorActionKind) {
        mode = Mode.ERROR
        errorMessage = message
        errorAction = action
        stopBreathing()
        invalidate()
    }

    fun setAmplitude(rms: Float) {
        amplitude = rms.coerceIn(0f, 1f)
        // 50ms attack, 300ms release feel: fast up, slow decay.
        haloLevel = if (amplitude > haloLevel) {
            amplitude
        } else {
            haloLevel * 0.92f
        }
        invalidate()
    }

    fun resetTranscript() {
        partialText = ""
        committedTail = ""
        invalidate()
    }

    /**
     * Called when a session starts or ends. The bar is long-lived and merely
     * hidden between sessions, so without this two things persist that must not:
     * the previous app's dictated text is still on screen when the bar opens
     * somewhere else, and the infinite breathing animator keeps posting
     * Choreographer frames for the life of the process (nothing else reaches
     * stopBreathing except the error path and detach, and teardown does neither).
     */
    fun resetForSession() {
        stopBreathing()
        mode = Mode.LISTENING
        errorMessage = ""
        amplitude = 0f
        haloLevel = 0f
        resetTranscript()
    }

    private fun startBreathing() {
        if (!breathAnimator.isStarted) breathAnimator.start()
    }

    private fun stopBreathing() {
        breathAnimator.cancel()
    }

    override fun onDetachedFromWindow() {
        stopBreathing()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.bgKeyboard)
        val controlRowH = dp(56f)
        val transcriptBottom = height - controlRowH

        drawTranscript(canvas, transcriptBottom)
        drawControls(canvas, transcriptBottom, controlRowH)
    }

    private fun drawTranscript(canvas: Canvas, bottom: Float) {
        val padding = dp(16f)
        val maxWidth = width - 2 * padding
        if (mode == Mode.ERROR) {
            hintPaint.color = theme.error
            canvas.drawText(errorMessage, width / 2f, bottom / 2f + sp(5f), hintPaint)
            return
        }

        val committedColor = theme.transcriptFinal
        val partialColor = theme.transcriptPartial
        val text = buildString {
            if (committedTail.isNotEmpty()) append(committedTail)
            if (partialText.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(partialText)
            }
        }
        if (text.isEmpty()) {
            hintPaint.color = theme.transcriptPartial
            val hint = if (mode == Mode.LISTENING) {
                context.getString(R.string.voice_listening)
            } else {
                context.getString(R.string.voice_tap_to_speak)
            }
            canvas.drawText(hint, width / 2f, bottom / 2f + sp(5f), hintPaint)
            return
        }

        // Last ~2 lines, committed text solid, partial muted.
        val committedLen = committedTail.length
        val lineHeight = dp(24f)
        val lines = layoutLastTwoLines(text, maxWidth)
        var y = bottom - dp(10f) - (lines.size - 1) * lineHeight
        var charIndex = text.length - lines.sumOf { it.length } - (lines.size - 1)
        for (line in lines) {
            var x = padding
            for (ch in line) {
                transcriptPaint.color =
                    if (charIndex < committedLen) committedColor else partialColor
                canvas.drawText(ch.toString(), x, y, transcriptPaint)
                x += transcriptPaint.measureText(ch.toString())
                charIndex++
            }
            charIndex++ // the space between lines
            y += lineHeight
        }
    }

    private fun layoutLastTwoLines(text: String, maxWidth: Float): List<String> {
        val words = text.split(' ')
        val lines = mutableListOf<StringBuilder>(StringBuilder())
        for (word in words) {
            val current = lines.last()
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (transcriptPaint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            } else {
                lines.add(StringBuilder(word))
            }
        }
        return lines.takeLast(2).map { it.toString() }
    }

    private fun drawControls(canvas: Canvas, top: Float, rowH: Float) {
        val cy = top + rowH / 2f
        val orbR = dp(28f)
        val cx = width / 2f

        // Halo (amplitude + breathing).
        if (mode == Mode.LISTENING) {
            val breath = (sin(breathAnimator.animatedFraction * 2 * Math.PI) * 0.5 + 0.5).toFloat()
            val haloR = orbR + dp(4f) + dp(10f) * haloLevel + dp(3f) * breath
            haloPaint.color = theme.micPulse.withAlphaFraction(0.24f)
            canvas.drawCircle(cx, cy, haloR, haloPaint)
        }

        // Orb.
        orbPaint.color = when (mode) {
            Mode.ERROR -> theme.error
            else -> theme.accent
        }
        canvas.drawCircle(cx, cy, orbR, orbPaint)

        // Mic glyph (or stop square while finalizing).
        val onColor = if (mode == Mode.ERROR) theme.onError else theme.onAccent
        iconPaint.color = onColor
        iconFillPaint.color = onColor
        if (mode == Mode.FINALIZING || mode == Mode.REFINING) {
            val s = dp(7f)
            canvas.drawRoundRect(cx - s, cy - s, cx + s, cy + s, dp(2f), dp(2f), iconFillPaint)
        } else {
            val s = dp(11f)
            val mw = s * 0.42f
            canvas.drawRoundRect(cx - mw, cy - s * 0.95f, cx + mw, cy + s * 0.15f, mw, mw, iconFillPaint)
            val arc = android.graphics.RectF(cx - s * 0.75f, cy - s * 0.55f, cx + s * 0.75f, cy + s * 0.5f)
            canvas.drawArc(arc, 0f, 180f, false, iconPaint)
            canvas.drawLine(cx, cy + s * 0.5f, cx, cy + s * 0.95f, iconPaint)
        }

        // Keyboard-return button (left).
        val kbCx = dp(36f)
        iconPaint.color = theme.keyTextSecondary
        val kw = dp(11f)
        val kh = dp(8f)
        canvas.drawRoundRect(kbCx - kw, cy - kh, kbCx + kw, cy + kh, dp(2f), dp(2f), iconPaint)
        for (i in 0..2) {
            val ky = cy - kh + dp(3f) + i * dp(4.5f)
            canvas.drawLine(kbCx - kw + dp(3f), ky, kbCx + kw - dp(3f), ky, iconPaint)
        }

        // Refining chip.
        if (mode == Mode.REFINING) {
            val label = context.getString(R.string.voice_cleaning)
            hintPaint.color = theme.onAccent
            val tw = hintPaint.measureText(label)
            val chipCx = width - dp(24f) - tw / 2f
            chipPaint.color = theme.accent.withAlphaFraction(0.9f)
            canvas.drawRoundRect(
                chipCx - tw / 2 - dp(10f), cy - dp(13f), chipCx + tw / 2 + dp(10f), cy + dp(13f),
                dp(13f), dp(13f), chipPaint,
            )
            canvas.drawText(label, chipCx, cy + sp(5f), hintPaint)
        }

        // Error action label (right).
        if (mode == Mode.ERROR) {
            val label = when (errorAction) {
                ErrorActionKind.OPEN_PERMISSION -> context.getString(R.string.voice_error_no_permission_action)
                ErrorActionKind.OPEN_DOWNLOAD -> context.getString(R.string.voice_error_no_model_action)
                ErrorActionKind.DISMISS -> "OK"
            }
            hintPaint.color = theme.accent
            canvas.drawText(label, width - dp(48f), cy + sp(5f), hintPaint)
        }
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val controlTop = height - dp(56f)
        val cx = width / 2f
        val cy = controlTop + dp(28f)
        val dx = event.x - cx
        val dy = event.y - cy
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when {
            mode == Mode.ERROR && event.x > width - dp(96f) && event.y > controlTop ->
                listener?.onErrorAction(errorAction)
            dx * dx + dy * dy <= dp(40f) * dp(40f) && event.y > controlTop - dp(12f) ->
                if (mode == Mode.ERROR) listener?.onErrorAction(ErrorActionKind.DISMISS)
                else listener?.onOrbTapped()
            event.x < dp(72f) && event.y > controlTop ->
                listener?.onBackToKeyboard()
        }
        return true
    }
}
