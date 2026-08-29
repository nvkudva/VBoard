package com.vboard.app.keyboard

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import java.util.Locale

/**
 * Accessibility plumbing shared by the canvas-drawn keyboard surfaces
 * (DESIGN_SPEC §10).
 *
 * Every surface in this keyboard draws its cells onto one [View] so that a tap
 * costs a single hit test and a cold open costs a single measure/draw pass.
 * That is the right architecture and it is not negotiable — but it means a
 * screen reader sees one undifferentiated rectangle. The fix is an
 * [AccessibilityNodeProvider] that reports the cell rectangles the view already
 * computes as virtual descendants, which is what [VirtualCells] does.
 *
 * `androidx.customview`'s `ExploreByTouchHelper` does the same job, but it is
 * not on this module's classpath and adding it means editing the build file, so
 * this is the framework-only equivalent. It deliberately mirrors the shape
 * ToolbarView already uses.
 */
internal object KeyboardA11y {

    /**
     * True when a touch-exploration service (TalkBack) is driving the screen.
     *
     * Two things hang off this: key previews are suppressed (they occlude the
     * finger's own exploration), and touch is delivered as hover, so the views
     * switch to lift-to-type.
     */
    fun touchExplorationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val VARIATION_SELECTOR_16 = 0xFE0F
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3
    private val SKIN_TONE_MODIFIERS = 0x1F3FB..0x1F3FF

    /**
     * A spoken name for an emoji cell — "Grinning face", not the glyph, which
     * most engines read as "unknown character" or spell out its code points.
     *
     * The name comes from the ICU Unicode name of the first *semantic* code
     * point: joiners, variation selectors, keycaps and skin-tone modifiers are
     * skipped so "👍🏽" and "👍" both read as thumbs up rather than as a modifier.
     * ICU names are the basis of the CLDR short names and match them closely
     * enough to be useful; if ICU is unavailable the glyph is used unchanged
     * rather than announcing nothing.
     */
    fun emojiName(emoji: String): String {
        if (emoji.isEmpty()) return emoji
        val codePoint = primaryCodePoint(emoji)
        val raw = runCatching { android.icu.lang.UCharacter.getName(codePoint) }.getOrNull()
        if (raw.isNullOrBlank()) return emoji
        return raw.lowercase(Locale.US).replaceFirstChar { it.uppercase(Locale.US) }
    }

    private fun primaryCodePoint(emoji: String): Int {
        var index = 0
        while (index < emoji.length) {
            val codePoint = emoji.codePointAt(index)
            if (!isModifier(codePoint)) return codePoint
            index += Character.charCount(codePoint)
        }
        return emoji.codePointAt(0)
    }

    private fun isModifier(codePoint: Int): Boolean =
        codePoint == ZERO_WIDTH_JOINER ||
            codePoint == VARIATION_SELECTOR_16 ||
            codePoint == COMBINING_ENCLOSING_KEYCAP ||
            codePoint in SKIN_TONE_MODIFIERS
}

/** Sentinel for "no cell". Deliberately equal to [AccessibilityNodeProvider.HOST_VIEW_ID]. */
internal const val NO_CELL = -1

/**
 * One virtual accessibility node per drawn cell, plus explore-by-touch.
 *
 * Subclasses describe their cells ([count], [boundsOf], [descriptionOf]) and say
 * what activating one does ([click]); everything else — node construction,
 * accessibility focus bookkeeping, hover tracking, event dispatch — is here.
 *
 * **Lift-to-type.** When touch exploration is on, the framework delivers hover
 * rather than touch. [onHover] announces the cell under the finger as it moves
 * and calls [onHoverLift] when the finger leaves, which is where surfaces that
 * type (the keyboard) commit. That is the whole reason this keyboard is usable
 * with TalkBack: without it the only way to find a key is to press it.
 */
internal abstract class VirtualCells(private val host: View) {

    /** How many cells the surface currently draws. */
    protected abstract fun count(): Int

    /** Bounds of [id] in the host's own coordinates, or null if it has none. */
    protected abstract fun boundsOf(id: Int): RectF?

    protected abstract fun descriptionOf(id: Int): CharSequence?

    protected abstract fun click(id: Int): Boolean

    protected open fun classNameOf(id: Int): CharSequence = "android.widget.Button"

    protected open fun isEnabled(id: Int): Boolean = true

    /** Verb TalkBack appends to the double-tap prompt, e.g. "speak". */
    protected open fun clickLabelOf(id: Int): CharSequence? = null

    /** Non-null enables a long-click action on the node, labelled with this. */
    protected open fun longClickLabelOf(id: Int): CharSequence? = null

    protected open fun longClick(id: Int): Boolean = false

    /** Which cell covers a point. Overridden where gaps resolve to a neighbour. */
    protected open fun idAt(x: Float, y: Float): Int {
        for (id in 0 until count()) {
            if (boundsOf(id)?.contains(x, y) == true) return id
        }
        return NO_CELL
    }

    /** Explore-by-touch moved onto [id] ([NO_CELL] when it left every cell). */
    protected open fun onHoverChanged(id: Int) {}

    /** The finger lifted off [id]. Surfaces that type commit here. */
    protected open fun onHoverLift(id: Int) {}

    /** Accessibility focus (swipe navigation) landed on [id]. */
    protected open fun onAccessibilityFocusChanged(id: Int) {}

    var hoveredId: Int = NO_CELL
        private set

    var accessibilityFocusedId: Int = NO_CELL
        private set

    fun touchExplorationEnabled(): Boolean = KeyboardA11y.touchExplorationEnabled(host.context)

    val provider: AccessibilityNodeProvider = object : AccessibilityNodeProvider() {

        @Suppress("DEPRECATION") // obtain()/setBoundsInParent(): no framework-only successor at minSdk 29.
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == HOST_VIEW_ID) {
                val hostNode = AccessibilityNodeInfo.obtain(host)
                host.onInitializeAccessibilityNodeInfo(hostNode)
                for (id in 0 until count()) {
                    if (boundsOf(id) != null) hostNode.addChild(host, id)
                }
                return hostNode
            }
            val rect = boundsOf(virtualViewId) ?: return null
            val node = AccessibilityNodeInfo.obtain(host, virtualViewId)
            node.packageName = host.context.packageName
            node.className = classNameOf(virtualViewId)
            node.contentDescription = descriptionOf(virtualViewId)
            node.setParent(host)
            node.isVisibleToUser = true
            node.isFocusable = true

            val enabled = isEnabled(virtualViewId)
            node.isEnabled = enabled
            node.isClickable = enabled
            if (enabled) {
                node.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        clickLabelOf(virtualViewId),
                    ),
                )
                longClickLabelOf(virtualViewId)?.let { label ->
                    node.isLongClickable = true
                    node.addAction(
                        AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_LONG_CLICK,
                            label,
                        ),
                    )
                }
            }

            val focused = accessibilityFocusedId == virtualViewId
            node.isAccessibilityFocused = focused
            node.addAction(
                if (focused) {
                    AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                } else {
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                },
            )

            val bounds = Rect(
                rect.left.toInt(),
                rect.top.toInt(),
                rect.right.toInt(),
                rect.bottom.toInt(),
            )
            node.setBoundsInParent(bounds)
            val offset = IntArray(2)
            host.getLocationOnScreen(offset)
            bounds.offset(offset[0], offset[1])
            node.setBoundsInScreen(bounds)
            return node
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId == HOST_VIEW_ID) {
                return host.performAccessibilityAction(action, arguments)
            }
            if (boundsOf(virtualViewId) == null) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> focus(virtualViewId)
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> clearFocus(virtualViewId)
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    if (!isEnabled(virtualViewId)) return false
                    sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    click(virtualViewId)
                }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                    if (!isEnabled(virtualViewId)) return false
                    sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
                    longClick(virtualViewId)
                }
                else -> false
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? =
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && accessibilityFocusedId != NO_CELL) {
                createAccessibilityNodeInfo(accessibilityFocusedId)
            } else {
                null
            }
    }

    /**
     * Hover dispatch for explore-by-touch. Returns false when no touch
     * exploration service is running so the caller falls back to `super`.
     */
    fun onHover(event: MotionEvent): Boolean {
        if (!touchExplorationEnabled()) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                moveHoverTo(idAt(event.x, event.y))
                true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                val lifted = hoveredId
                moveHoverTo(NO_CELL)
                if (lifted != NO_CELL) onHoverLift(lifted)
                true
            }
            else -> false
        }
    }

    private fun moveHoverTo(id: Int) {
        if (id == hoveredId) return
        val previous = hoveredId
        hoveredId = id
        if (previous != NO_CELL) sendEvent(previous, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        onHoverChanged(id)
        if (id != NO_CELL) {
            sendEvent(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            focus(id)
        }
    }

    private fun focus(id: Int): Boolean {
        if (accessibilityFocusedId == id) return false
        val previous = accessibilityFocusedId
        accessibilityFocusedId = id
        if (previous != NO_CELL) {
            sendEvent(previous, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        }
        sendEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        onAccessibilityFocusChanged(id)
        return true
    }

    private fun clearFocus(id: Int): Boolean {
        if (accessibilityFocusedId != id) return false
        accessibilityFocusedId = NO_CELL
        sendEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        return true
    }

    /** Drops focus/hover state — call when the cell list is rebuilt under it. */
    fun reset() {
        hoveredId = NO_CELL
        accessibilityFocusedId = NO_CELL
    }

    fun sendEvent(id: Int, eventType: Int) {
        if (id == NO_CELL) return
        val parent = host.parent ?: return
        @Suppress("DEPRECATION") // AccessibilityEvent() is API 30; minSdk here is 29.
        val event = AccessibilityEvent.obtain(eventType)
        event.packageName = host.context.packageName
        event.className = classNameOf(id)
        event.setSource(host, id)
        descriptionOf(id)?.let { event.contentDescription = it }
        parent.requestSendAccessibilityEvent(host, event)
    }

    /** Tells the framework the node tree changed under it. */
    fun notifyContentChanged() {
        host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }
}
