package com.vboard.app.keyboard

import android.app.Activity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import com.vboard.core.clipboard.ClipEntry
import com.vboard.core.suggest.Suggestion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * Accessibility contract for the other canvas surfaces: the suggestion strip,
 * the clipboard panel and the emoji panel (DESIGN_SPEC §10).
 *
 * See [KeyboardViewA11yTest] for the build configuration these Robolectric
 * tests need.
 */
@RunWith(RobolectricTestRunner::class)
class PanelA11yTest {

    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        root = FrameLayout(activity)
        activity.setContentView(root)
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    // -------------------------------------------------------- suggestion strip

    @Test
    fun `each suggestion is a node that says what it is`() {
        val strip = strip()
        strip.setSuggestions(
            listOf(
                suggestion("the"),
                suggestion("then"),
                suggestion("they"),
            ),
            autocorrect = true,
        )

        // Display order is left = ranked[1], centre = ranked[0], right = ranked[2].
        assertEquals(3, childCount(strip))
        assertEquals(
            listOf("then, suggestion", "the, autocorrect", "they, suggestion"),
            descriptions(strip),
        )
    }

    @Test
    fun `empty suggestion slots are not exposed as silent nodes`() {
        val strip = strip()
        strip.setSuggestions(listOf(suggestion("hello")), autocorrect = false)

        assertEquals(1, childCount(strip))
        assertNull(provider(strip).createAccessibilityNodeInfo(0))
        assertEquals("hello, suggestion", description(strip, 1))
        assertNull(provider(strip).createAccessibilityNodeInfo(2))
    }

    @Test
    fun `the clipboard chip announces what it will paste`() {
        val strip = strip()
        strip.setSuggestions(listOf(suggestion("hello")), autocorrect = false)
        strip.setClipboardChip("one time code")

        assertEquals("Paste one time code", description(strip, 0))
        assertEquals(
            "paste",
            node(strip, 0).actionList
                .firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }?.label,
        )
    }

    @Test
    fun `activating a suggestion node picks it`() {
        val strip = strip()
        val picked = mutableListOf<String>()
        strip.listener = object : SuggestionStripView.Listener {
            override fun onSuggestionPicked(suggestion: Suggestion) {
                picked.add(suggestion.text)
            }

            override fun onClipboardChipPicked() = Unit
        }
        strip.setSuggestions(listOf(suggestion("hello")), autocorrect = false)

        assertTrue(provider(strip).performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf("hello"), picked)
    }

    // --------------------------------------------------------- clipboard panel

    @Test
    fun `each clip is a node and pinned clips say so`() {
        val panel = ClipboardPanelView(activity, KeyboardTheme.LIGHT, PANEL_HEIGHT_PX)
        root.addView(panel)
        panel.setClips(
            pinned = listOf(ClipEntry("shipping address", 0L, pinned = true)),
            recent = listOf(ClipEntry("meeting link", 1L)),
        )
        lay(panel)

        val grid = panel.gridForTest()
        assertEquals(2, childCount(grid))
        assertEquals(
            listOf("Pinned clip, shipping address", "Clip, meeting link"),
            descriptions(grid),
        )
    }

    @Test
    fun `a clip node offers paste and the options menu`() {
        val panel = ClipboardPanelView(activity, KeyboardTheme.LIGHT, PANEL_HEIGHT_PX)
        root.addView(panel)
        panel.setClips(pinned = emptyList(), recent = listOf(ClipEntry("meeting link", 1L)))
        lay(panel)

        val actions = node(panel.gridForTest(), 0).actionList
        assertEquals(
            "paste",
            actions.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }?.label,
        )
        assertEquals(
            "show clip options",
            actions.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }?.label,
        )
    }

    @Test
    fun `a long clip is announced as a bounded preview`() {
        val long = "x".repeat(1_000)
        val panel = ClipboardPanelView(activity, KeyboardTheme.LIGHT, PANEL_HEIGHT_PX)
        root.addView(panel)
        panel.setClips(pinned = emptyList(), recent = listOf(ClipEntry(long, 1L)))
        lay(panel)

        val spoken = description(panel.gridForTest(), 0)
        assertTrue("a 1000-character clip must not be read out whole", spoken.length < 200)
        assertTrue(spoken.startsWith("Clip, xxx"))
    }

    @Test
    fun `an empty clipboard says so instead of being a blank rectangle`() {
        val panel = ClipboardPanelView(activity, KeyboardTheme.LIGHT, PANEL_HEIGHT_PX)
        root.addView(panel)
        panel.setClips(pinned = emptyList(), recent = emptyList())
        lay(panel)

        val grid = panel.gridForTest()
        assertEquals(0, childCount(grid))
        assertEquals("Clipboard is empty", grid.contentDescription.toString())
    }

    @Test
    fun `the clipboard bottom bar exposes both of its halves`() {
        val panel = ClipboardPanelView(activity, KeyboardTheme.LIGHT, PANEL_HEIGHT_PX)
        root.addView(panel)
        panel.setClips(pinned = emptyList(), recent = emptyList())
        lay(panel)

        assertEquals(listOf("Letters", "Backspace"), descriptions(panel.bottomBarForTest()))
    }

    // ------------------------------------------------------------- emoji panel

    @Test
    fun `emoji category tabs are named, not just glyphs`() {
        val panel = emojiPanel()
        assertEquals(EmojiData.categories.size, childCount(panel.tabsForTest()))
        assertEquals("Smileys and emotion emoji", description(panel.tabsForTest(), 0))
        assertEquals("Animals and nature emoji", description(panel.tabsForTest(), 2))
    }

    @Test
    fun `every emoji cell is a node`() {
        val panel = emojiPanel()
        assertEquals(
            EmojiData.categories[0].emojis.size,
            childCount(panel.gridForTest()),
        )
    }

    @Test
    fun `emoji cells expose a name rather than the raw glyph`() {
        val panel = emojiPanel()
        val first = description(panel.gridForTest(), 0)
        assertEquals("Grinning face", first)
    }

    @Test
    fun `emoji names come from the base character, not its modifiers`() {
        // Skin tone and variation selectors must not change what is announced.
        assertEquals(KeyboardA11y.emojiName("👍"), KeyboardA11y.emojiName("👍🏽"))
        assertTrue(KeyboardA11y.emojiName("❤️").contains("heart", ignoreCase = true))
    }

    @Test
    fun `the emoji bottom bar exposes both of its halves`() {
        assertEquals(listOf("Letters", "Backspace"), descriptions(emojiPanel().bottomBarForTest()))
    }

    // ------------------------------------------------------------------ infra

    private fun strip(): SuggestionStripView {
        val view = SuggestionStripView(activity, KeyboardTheme.LIGHT)
        root.addView(view)
        lay(view)
        return view
    }

    private fun emojiPanel(): EmojiPanelView {
        val panel = EmojiPanelView(activity, KeyboardTheme.LIGHT, PANEL_HEIGHT_PX)
        root.addView(panel)
        lay(panel)
        return panel
    }

    private fun lay(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(PANEL_HEIGHT_PX, View.MeasureSpec.AT_MOST),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun provider(view: View): AccessibilityNodeProvider {
        val p = view.accessibilityNodeProvider
        assertNotNull("${view.javaClass.simpleName} must publish a node provider", p)
        return p!!
    }

    private fun childCount(view: View): Int =
        provider(view)
            .createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!!
            .childCount

    private fun node(view: View, id: Int): AccessibilityNodeInfo =
        provider(view).createAccessibilityNodeInfo(id)!!

    private fun description(view: View, id: Int): String =
        node(view, id).contentDescription.toString()

    private fun descriptions(view: View): List<String> {
        val result = mutableListOf<String>()
        var id = 0
        var found = 0
        val total = childCount(view)
        while (found < total && id < MAX_SCANNED_IDS) {
            provider(view).createAccessibilityNodeInfo(id)?.let {
                result.add(it.contentDescription.toString())
                found++
            }
            id++
        }
        return result
    }

    private fun suggestion(text: String) =
        Suggestion(text, 1.0, Suggestion.Source.COMPLETION)

    private companion object {
        const val WIDTH_PX = 1080
        const val PANEL_HEIGHT_PX = 800
        const val MAX_SCANNED_IDS = 512
    }
}
