package com.vboard.app.keyboard

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Accessibility contract for the canvas-drawn keyboard (DESIGN_SPEC §10).
 *
 * The keyboard is one View with no child views, so everything a screen reader
 * can see comes from the [AccessibilityNodeProvider] over the key rectangles.
 * These tests assert the node tree that provider produces, explore-by-touch
 * lift-to-type, and the two things the spec says must happen together under
 * touch exploration: previews off, haptics on.
 *
 * NOTE for whoever owns `app/build.gradle.kts`: Robolectric is a JUnit 4 runner
 * and this module configures `useJUnitPlatform()`. These tests need
 * `testRuntimeOnly(junit-vintage-engine)` and
 * `testOptions { unitTests { isIncludeAndroidResources = true } }` before they
 * execute at all — see the accessibility work's report.
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardViewA11yTest {

    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private val listener = RecordingListener()

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        root = FrameLayout(activity)
        activity.setContentView(root)
        setTouchExploration(false)
    }

    @After
    fun tearDown() {
        // Order matters. Tearing the window down flushes the accessibility sends
        // the framework has queued, and those go through
        // `sendAccessibilityEventUnchecked` — which, as the name says, does not
        // check whether accessibility is on. Switching it off first makes that
        // flush throw "Accessibility off. Did you forget to check that?".
        controller.destroy()
        // ShadowAccessibilityManager holds this in static state.
        setTouchExploration(false)
    }

    // ------------------------------------------------------------ node counts

    @Test
    fun `letters layout exposes one node per key`() {
        val view = keyboard(KeyboardLayouts.LETTERS)
        assertEquals(expectedKeyCount(KeyboardLayouts.LETTERS), childCount(view))
        // 10 qwerty + 9 home + 9 (shift, 7 letters, backspace) + 6 bottom row.
        assertEquals(34, childCount(view))
    }

    @Test
    fun `number row letters variant exposes the digit row too`() {
        val view = keyboard(KeyboardLayouts.LETTERS_NUMBER_ROW)
        assertEquals(expectedKeyCount(KeyboardLayouts.LETTERS_NUMBER_ROW), childCount(view))
        assertEquals(44, childCount(view))

        // This variant drops the small digit hints, so if the digit row were not
        // exposed there would be no way to reach a number at all.
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            descriptions(view).take(10),
        )
    }

    @Test
    fun `symbols layers expose every key`() {
        assertEquals(35, childCount(keyboard(KeyboardLayouts.SYMBOLS)))
        assertEquals(
            expectedKeyCount(KeyboardLayouts.SYMBOLS2),
            childCount(keyboard(KeyboardLayouts.SYMBOLS2)),
        )
    }

    @Test
    fun `every node on every layer carries a non-empty description`() {
        for (layout in ALL_LAYOUTS) {
            val view = keyboard(layout)
            val blank = descriptions(view).filter { it.isBlank() }
            assertTrue("blank descriptions on ${layout.layer}: $blank", blank.isEmpty())
        }
    }

    // ----------------------------------------------------------- descriptions

    @Test
    fun `letter keys announce their character and follow shift`() {
        val view = keyboard()
        val q = view.keyIndexOfLabelForTest("q")
        assertEquals("q", description(view, q))

        view.shiftState = KeyboardView.ShiftState.SHIFT
        assertEquals("Q", description(view, q))

        view.shiftState = KeyboardView.ShiftState.CAPS_LOCK
        assertEquals("Q", description(view, q))
    }

    @Test
    fun `function keys announce their action`() {
        val view = keyboard()
        assertEquals("Backspace", description(view, view.keyIndexOfActionForTest(KeyAction.Backspace)))
        assertEquals("Space", description(view, view.keyIndexOfActionForTest(KeyAction.Space)))
        assertEquals("Enter", description(view, view.keyIndexOfActionForTest(KeyAction.Enter)))
        assertEquals("Symbols", description(view, view.keyIndexOfActionForTest(KeyAction.ToSymbols)))
        assertEquals("Voice typing", description(view, view.keyIndexOfActionForTest(KeyAction.Mic)))
    }

    @Test
    fun `shift key announces its state`() {
        val view = keyboard()
        val shift = view.keyIndexOfActionForTest(KeyAction.Shift)
        assertEquals("Shift", description(view, shift))

        view.shiftState = KeyboardView.ShiftState.SHIFT
        assertEquals("Shift on", description(view, shift))

        view.shiftState = KeyboardView.ShiftState.CAPS_LOCK
        assertEquals("Caps lock on", description(view, shift))
    }

    @Test
    fun `enter announces the editor action it is drawn as`() {
        val view = keyboard()
        val enter = view.keyIndexOfActionForTest(KeyAction.Enter)
        view.enterIcon = KeyIcon.SEARCH
        assertEquals("Search", description(view, enter))
        view.enterIcon = KeyIcon.SEND
        assertEquals("Send", description(view, enter))
        view.enterIcon = KeyIcon.GO
        assertEquals("Go", description(view, enter))
        view.enterIcon = KeyIcon.NEXT
        assertEquals("Next", description(view, enter))
        view.enterIcon = KeyIcon.PREVIOUS
        assertEquals("Previous", description(view, enter))
        view.enterIcon = KeyIcon.DONE
        assertEquals("Done", description(view, enter))
        view.enterIcon = KeyIcon.ENTER
        assertEquals("Enter", description(view, enter))
    }

    @Test
    fun `the second symbol page is named distinctly from the first`() {
        val view = keyboard(KeyboardLayouts.SYMBOLS)
        assertEquals(
            "More symbols",
            description(view, view.keyIndexOfActionForTest(KeyAction.ToSymbols2)),
        )
        assertEquals("Letters", description(view, view.keyIndexOfActionForTest(KeyAction.ToLetters)))
    }

    @Test
    fun `mic node names its action and goes disabled when voice is unavailable`() {
        val view = keyboard()
        val mic = view.keyIndexOfActionForTest(KeyAction.Mic)
        assertTrue(node(view, mic).isEnabled)
        assertEquals("speak", action(view, mic, AccessibilityNodeInfo.ACTION_CLICK)?.label)

        view.micEnabled = false
        assertFalse(node(view, mic).isEnabled)
    }

    // ------------------------------------------------------------- activation

    @Test
    fun `activating a node types the key`() {
        // Deliberately runs with no accessibility service enabled: an action can
        // arrive from instrumentation, or from a service that disconnects
        // between querying the node and activating it. Sending the click event
        // unguarded throws IllegalStateException and takes the IME down.
        val view = keyboard()
        assertTrue(
            view.a11yProviderForTest().performAction(
                view.keyIndexOfLabelForTest("k"),
                AccessibilityNodeInfo.ACTION_CLICK,
                null,
            ),
        )
        assertEquals(listOf("k"), listener.typed)
    }

    @Test
    fun `the space key offers switch keyboard as its long press`() {
        // There is no globe key on this layout, so holding space is the only way
        // out of VBoard a screen-reader user has.
        val view = keyboard()
        val space = view.keyIndexOfActionForTest(KeyAction.Space)
        val longClick = action(view, space, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        assertNotNull("space must advertise a long-click action", longClick)
        assertEquals("switch keyboard", longClick!!.label)
        assertTrue(
            view.a11yProviderForTest()
                .performAction(space, AccessibilityNodeInfo.ACTION_LONG_CLICK, null),
        )
    }

    // -------------------------------------------------------- explore by touch

    @Test
    fun `hovering announces keys without typing and lifting commits`() {
        val view = keyboard()
        setTouchExploration(true)

        val g = center(view, view.keyIndexOfLabelForTest("g"))
        val h = center(view, view.keyIndexOfLabelForTest("h"))

        view.onHoverEvent(hover(MotionEvent.ACTION_HOVER_ENTER, g))
        assertTrue("hovering must not type", listener.typed.isEmpty())

        view.onHoverEvent(hover(MotionEvent.ACTION_HOVER_MOVE, h))
        assertTrue("dragging must not type", listener.typed.isEmpty())

        view.onHoverEvent(hover(MotionEvent.ACTION_HOVER_EXIT, h))
        assertEquals("lifting commits the announced key", listOf("h"), listener.typed)
    }

    @Test
    fun `hovering moves the accessibility focus onto the key under the finger`() {
        val view = keyboard()
        setTouchExploration(true)
        val g = view.keyIndexOfLabelForTest("g")

        view.onHoverEvent(hover(MotionEvent.ACTION_HOVER_ENTER, center(view, g)))

        val focused = view.a11yProviderForTest()
            .findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        assertNotNull(focused)
        assertEquals("g", focused!!.contentDescription.toString())
    }

    @Test
    fun `key preview shows when no screen reader is running`() {
        val view = keyboard()
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, center(view, view.keyIndexOfLabelForTest("g"))))
        assertTrue("baseline: the preview bubble is the normal behaviour", view.previewShowingForTest())
    }

    @Test
    fun `key previews are suppressed under touch exploration but haptics still fire`() {
        setTouchExploration(true)
        val view = keyboard()
        val g = center(view, view.keyIndexOfLabelForTest("g"))

        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, g))

        assertFalse(
            "the preview occludes the exploring finger and must be suppressed",
            view.previewShowingForTest(),
        )
        assertEquals(
            "haptics are explicitly retained",
            HapticFeedbackConstants.KEYBOARD_TAP,
            shadowOf(view).lastHapticFeedbackPerformed(),
        )
    }

    @Test
    fun `hovering a key fires a haptic and shows no preview`() {
        setTouchExploration(true)
        val view = keyboard()

        view.onHoverEvent(
            hover(MotionEvent.ACTION_HOVER_ENTER, center(view, view.keyIndexOfLabelForTest("g"))),
        )

        assertEquals(
            HapticFeedbackConstants.KEYBOARD_TAP,
            shadowOf(view).lastHapticFeedbackPerformed(),
        )
        assertFalse(view.previewShowingForTest())
    }

    // ------------------------------------------------------ long-press popup

    @Test
    fun `long-press candidates are individually navigable and selectable`() {
        val view = keyboard()
        val e = view.keyIndexOfLabelForTest("e")
        assertTrue(
            view.a11yProviderForTest()
                .performAction(e, AccessibilityNodeInfo.ACTION_LONG_CLICK, null),
        )

        val popup = view.activePopupForTest()
        assertNotNull("long-click must open the alternates popup", popup)
        assertTrue(popup!!.isSelector)

        val provider = popup.contentViewForTest()!!.accessibilityNodeProvider!!
        val host = provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!!
        assertEquals(5, host.childCount)
        assertEquals(
            listOf("3", "è", "é", "ê", "ë"),
            (0 until host.childCount).map {
                provider.createAccessibilityNodeInfo(it)!!.contentDescription.toString()
            },
        )

        assertTrue(provider.performAction(2, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf("é"), listener.longPressed)
        assertNull("choosing a candidate closes the popup", view.activePopupForTest())
    }

    @Test
    fun `only keys that have a long press advertise one`() {
        val view = keyboard()

        val withAlternates = action(
            view,
            view.keyIndexOfLabelForTest("e"),
            AccessibilityNodeInfo.ACTION_LONG_CLICK,
        )
        assertEquals("show alternate characters", withAlternates?.label)

        assertNull(
            action(view, view.keyIndexOfLabelForTest("d"), AccessibilityNodeInfo.ACTION_LONG_CLICK),
        )

        assertEquals(
            "open the clipboard",
            action(
                view,
                view.keyIndexOfActionForTest(KeyAction.ToSymbols),
                AccessibilityNodeInfo.ACTION_LONG_CLICK,
            )?.label,
        )
    }

    // ------------------------------------------------------------------ infra

    private fun keyboard(layout: KeyboardLayout = KeyboardLayouts.LETTERS): KeyboardView {
        val view = KeyboardView(activity, KeyboardTheme.LIGHT)
        view.listener = listener
        view.layout = layout
        root.addView(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // Qualified: KeyboardView also has a `layout` property.
        (view as View).layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private fun childCount(view: KeyboardView): Int =
        view.a11yProviderForTest()
            .createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!!
            .childCount

    private fun node(view: KeyboardView, id: Int): AccessibilityNodeInfo =
        view.a11yProviderForTest().createAccessibilityNodeInfo(id)!!

    private fun action(
        view: KeyboardView,
        id: Int,
        actionId: Int,
    ): AccessibilityNodeInfo.AccessibilityAction? =
        node(view, id).actionList.firstOrNull { it.id == actionId }

    private fun description(view: KeyboardView, id: Int): String =
        node(view, id).contentDescription.toString()

    private fun descriptions(view: KeyboardView): List<String> =
        (0 until childCount(view)).map { description(view, it) }

    /** Centre of a key in the view's own coordinates, via its published bounds. */
    private fun center(view: KeyboardView, id: Int): Pair<Float, Float> {
        val rect = Rect()
        node(view, id).getBoundsInScreen(rect)
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        rect.offset(-origin[0], -origin[1])
        return rect.exactCenterX() to rect.exactCenterY()
    }

    private fun hover(action: Int, at: Pair<Float, Float>): MotionEvent =
        motion(action, at).also { it.source = InputDevice.SOURCE_TOUCHSCREEN }

    private fun motion(action: Int, at: Pair<Float, Float>): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, at.first, at.second, 0)
    }

    private fun setTouchExploration(enabled: Boolean) {
        val manager =
            activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(manager).setEnabled(enabled)
        shadowOf(manager).setTouchExplorationEnabled(enabled)
    }

    private fun expectedKeyCount(layout: KeyboardLayout): Int = layout.rows.sumOf { it.keys.size }

    private class RecordingListener : KeyboardView.Listener {
        val typed = mutableListOf<String>()
        val longPressed = mutableListOf<String>()

        override fun onKeyAction(action: KeyAction, shifted: Boolean) {
            if (action is KeyAction.Text) typed.add(action.text)
        }

        override fun onKeyLongPressText(text: String) {
            longPressed.add(text)
        }

        override fun onSpacebarCursorMove(steps: Int) = Unit

        override fun onShiftChanged(state: KeyboardView.ShiftState) = Unit
    }

    private companion object {
        const val WIDTH_PX = 1080

        val ALL_LAYOUTS = listOf(
            KeyboardLayouts.LETTERS,
            KeyboardLayouts.LETTERS_NUMBER_ROW,
            KeyboardLayouts.SYMBOLS,
            KeyboardLayouts.SYMBOLS2,
        )
    }
}
