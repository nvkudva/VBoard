package com.vboard.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Emoji panel: category tabs on top, scrollable grid below, ABC/backspace row
 * handled by the host. Drawn as a single canvas grid per category for speed.
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(
    context: Context,
    private var theme: KeyboardTheme,
    private val panelHeightPx: Int,
) : LinearLayout(context) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onBackToLetters()
        fun onBackspace()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val tabs = TabBar()
    private val grid = EmojiGrid()
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val bottomBar = BottomBar()

    private var categoryIndex = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.bgKeyboard)
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f).toInt()))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46f).toInt()))
    }

    fun applyTheme(newTheme: KeyboardTheme) {
        theme = newTheme
        setBackgroundColor(theme.bgKeyboard)
        tabs.invalidate()
        grid.invalidate()
        bottomBar.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(panelHeightPx, MeasureSpec.EXACTLY),
        )
    }

    private inner class TabBar : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
        }
        private val underline = Paint().apply { }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(theme.suggestionBg)
            val w = width.toFloat() / EmojiData.categories.size
            for ((i, cat) in EmojiData.categories.withIndex()) {
                paint.alpha = if (i == categoryIndex) 255 else 140
                canvas.drawText(cat.icon, i * w + w / 2, height / 2f + paint.textSize / 3, paint)
                if (i == categoryIndex) {
                    underline.color = theme.accent
                    canvas.drawRect(i * w + w * 0.25f, height - dp(3f), i * w + w * 0.75f, height.toFloat(), underline)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val idx = (event.x / (width / EmojiData.categories.size)).toInt()
                    .coerceIn(0, EmojiData.categories.size - 1)
                if (idx != categoryIndex) {
                    categoryIndex = idx
                    scroll.scrollTo(0, 0)
                    grid.requestLayout()
                    grid.invalidate()
                    invalidate()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            return true
        }
    }

    private inner class EmojiGrid : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26f, resources.displayMetrics)
        }
        private val columns = 8
        private val cell get() = dp(46f)

        private fun emojis() = EmojiData.categories[categoryIndex].emojis

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val rows = (emojis().size + columns - 1) / columns
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                (rows * cell + dp(8f)).toInt(),
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat() / columns
            for ((i, emoji) in emojis().withIndex()) {
                val col = i % columns
                val row = i / columns
                canvas.drawText(
                    emoji,
                    col * w + w / 2,
                    row * cell + cell / 2 + paint.textSize / 3,
                    paint,
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val w = width.toFloat() / columns
                val col = (event.x / w).toInt().coerceIn(0, columns - 1)
                val row = (event.y / cell).toInt()
                val idx = row * columns + col
                emojis().getOrNull(idx)?.let {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener?.onEmoji(it)
                }
            }
            return true
        }
    }

    private inner class BottomBar : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
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
}

/** Curated common-emoji set; full system emoji picker is a v2 item. */
object EmojiData {
    data class Category(val icon: String, val emojis: List<String>)

    val categories = listOf(
        Category(
            "😀",
            listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
                "🙂", "😉", "😍", "🥰", "😘", "😗", "😋", "😜", "🤪", "😎",
                "🤩", "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "😣",
                "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥺", "😨",
                "😰", "😥", "😓", "🤗", "🤔", "🤭", "🤫", "🤥", "😶", "😐",
                "😑", "🙄", "😬", "😴", "🤤", "😪", "😵", "🤐", "🥴", "🤢",
                "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠", "😈", "👿", "💀",
            ),
        ),
        Category(
            "👋",
            listOf(
                "👋", "🤚", "🖐", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞",
                "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "👍", "👎",
                "✊", "👊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝", "🙏",
                "💪", "🦾", "✍️", "💅", "🤳", "👂", "👃", "🧠", "👀", "👁",
            ),
        ),
        Category(
            "🐶",
            listOf(
                "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯",
                "🦁", "🐮", "🐷", "🐸", "🐵", "🐔", "🐧", "🐦", "🐤", "🦆",
                "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🐝", "🐛", "🦋",
                "🐌", "🐞", "🐜", "🐢", "🐍", "🦎", "🐙", "🦑", "🦀", "🐠",
                "🐟", "🐬", "🐳", "🐋", "🦈", "🐊", "🌸", "🌺", "🌻", "🌹",
                "🌷", "🌲", "🌴", "🌵", "🍀", "🍁", "⭐", "🌟", "✨", "⚡",
                "🔥", "🌈", "☀️", "🌤", "☁️", "🌧", "⛈", "❄️", "☃️", "🌊",
            ),
        ),
        Category(
            "🍕",
            listOf(
                "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐",
                "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🥑", "🥦",
                "🌽", "🥕", "🍞", "🥐", "🥨", "🧀", "🥚", "🍳", "🥞", "🧇",
                "🥓", "🍗", "🍖", "🌭", "🍔", "🍟", "🍕", "🥪", "🌮", "🌯",
                "🥗", "🍝", "🍜", "🍲", "🍣", "🍱", "🥟", "🍤", "🍚", "🍙",
                "🍦", "🍧", "🎂", "🍰", "🧁", "🍫", "🍬", "🍭", "🍿", "🍩",
                "☕", "🍵", "🥤", "🧃", "🍺", "🍷", "🥂", "🍾", "🧊", "🥄",
            ),
        ),
        Category(
            "⚽",
            listOf(
                "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🎱", "🏓",
                "🏸", "🏒", "🥅", "⛳", "🏹", "🎣", "🥊", "🥋", "🎽", "🛹",
                "⛸", "🥌", "🎿", "🏂", "🏋️", "🤸", "🤺", "🤾", "🏌️", "🏇",
                "🧘", "🏄", "🏊", "🤽", "🚣", "🧗", "🚵", "🚴", "🏆", "🥇",
                "🥈", "🥉", "🏅", "🎖", "🎗", "🎫", "🎟", "🎪", "🎭", "🎨",
                "🎬", "🎤", "🎧", "🎼", "🎹", "🥁", "🎷", "🎺", "🎸", "🎻",
                "🎲", "♟", "🎯", "🎳", "🎮", "🕹", "🎰", "🧩", "🚗", "✈️",
            ),
        ),
        Category(
            "❤️",
            listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
                "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "☮️",
                "✝️", "☪️", "🕉", "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "🛐",
                "⛎", "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐",
                "💯", "💢", "💥", "💫", "💦", "💨", "🕳", "💣", "💬", "👁‍🗨",
                "🗨", "🗯", "💭", "💤", "👍", "✔️", "✅", "❌", "❓", "❗",
            ),
        ),
    )
}
