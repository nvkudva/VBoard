package com.vboard.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vboard.app.R

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

    /** Test seams: the panel's three canvases, each with its own node provider. */
    internal fun tabsForTest(): View = tabs

    internal fun gridForTest(): View = grid

    internal fun bottomBarForTest(): View = bottomBar

    private inner class TabBar : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
        }
        private val underline = Paint().apply { }

        init {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        private fun tabBounds(index: Int): RectF? {
            if (width == 0 || index !in EmojiData.categories.indices) return null
            val w = width.toFloat() / EmojiData.categories.size
            return RectF(index * w, 0f, (index + 1) * w, height.toFloat())
        }

        private val a11y = object : VirtualCells(this) {
            override fun count(): Int = EmojiData.categories.size

            override fun boundsOf(id: Int): RectF? = tabBounds(id)

            override fun descriptionOf(id: Int): CharSequence? =
                EmojiData.categories.getOrNull(id)?.let {
                    context.getString(R.string.a11y_emoji_category, context.getString(it.nameRes))
                }

            override fun classNameOf(id: Int): CharSequence = "android.widget.Tab"

            override fun click(id: Int): Boolean {
                if (id !in EmojiData.categories.indices) return false
                selectCategory(id)
                return true
            }
        }

        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = a11y.provider

        override fun onHoverEvent(event: MotionEvent): Boolean =
            if (a11y.onHover(event)) true else super.onHoverEvent(event)

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
                selectCategory(idx)
            }
            return true
        }

        private fun selectCategory(index: Int) {
            if (index == categoryIndex) return
            categoryIndex = index
            scroll.scrollTo(0, 0)
            grid.onCategoryChanged()
            invalidate()
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private inner class EmojiGrid : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26f, resources.displayMetrics)
        }
        private val columns = 8
        private val cell get() = dp(46f)

        init {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        private fun emojis() = EmojiData.categories[categoryIndex].emojis

        /** The whole node tree is replaced when the category changes. */
        fun onCategoryChanged() {
            a11y.reset()
            a11y.notifyContentChanged()
            requestLayout()
            invalidate()
        }

        private fun cellBounds(index: Int): RectF? {
            if (width == 0 || index !in emojis().indices) return null
            val w = width.toFloat() / columns
            val left = (index % columns) * w
            val top = (index / columns) * cell
            return RectF(left, top, left + w, top + cell)
        }

        /**
         * DESIGN_SPEC §10: emoji cells expose Unicode names. The glyph itself is
         * useless to a screen reader — see [KeyboardA11y.emojiName].
         */
        private val a11y = object : VirtualCells(this) {
            override fun count(): Int = emojis().size

            override fun boundsOf(id: Int): RectF? = cellBounds(id)

            override fun descriptionOf(id: Int): CharSequence? =
                emojis().getOrNull(id)?.let { KeyboardA11y.emojiName(it) }

            override fun click(id: Int): Boolean {
                val emoji = emojis().getOrNull(id) ?: return false
                listener?.onEmoji(emoji)
                return true
            }

            override fun onHoverChanged(id: Int) {
                if (id != NO_CELL) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            /** Lift-to-type, the same contract the key grid offers. */
            override fun onHoverLift(id: Int) {
                emojis().getOrNull(id)?.let { listener?.onEmoji(it) }
            }
        }

        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = a11y.provider

        override fun onHoverEvent(event: MotionEvent): Boolean =
            if (a11y.onHover(event)) true else super.onHoverEvent(event)

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

        init {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
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

        private val a11y = object : VirtualCells(this) {
            override fun count(): Int = 2

            override fun boundsOf(id: Int): RectF? {
                if (width == 0) return null
                val half = width / 2f
                return when (id) {
                    0 -> RectF(0f, 0f, half, height.toFloat())
                    1 -> RectF(half, 0f, width.toFloat(), height.toFloat())
                    else -> null
                }
            }

            override fun descriptionOf(id: Int): CharSequence? = when (id) {
                0 -> context.getString(R.string.a11y_letters)
                1 -> context.getString(R.string.a11y_backspace)
                else -> null
            }

            override fun click(id: Int): Boolean = when (id) {
                0 -> { listener?.onBackToLetters(); true }
                1 -> { listener?.onBackspace(); true }
                else -> false
            }
        }

        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = a11y.provider

        override fun onHoverEvent(event: MotionEvent): Boolean =
            if (a11y.onHover(event)) true else super.onHoverEvent(event)
    }
}

/** Curated common-emoji set; full system emoji picker is a v2 item. */
object EmojiData {
    /**
     * [nameRes] is what a screen reader announces for the tab — the icon glyph
     * alone reads as a single emoji, which says nothing about the category.
     */
    data class Category(val icon: String, val nameRes: Int, val emojis: List<String>)

    val categories = listOf(
        Category(
            "😀",
            R.string.a11y_emoji_cat_smileys,
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
            R.string.a11y_emoji_cat_people,
            listOf(
                "👋", "🤚", "🖐", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞",
                "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "👍", "👎",
                "✊", "👊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝", "🙏",
                "💪", "🦾", "✍️", "💅", "🤳", "👂", "👃", "🧠", "👀", "👁",
            ),
        ),
        Category(
            "🐶",
            R.string.a11y_emoji_cat_nature,
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
            R.string.a11y_emoji_cat_food,
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
            R.string.a11y_emoji_cat_activity,
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
            R.string.a11y_emoji_cat_symbols,
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
