package com.vboard.app.keyboard

import android.app.Activity
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * Inline dictation's whole on-keyboard UI is the mic key: it breathes while the
 * session is listening and must stop when it is not. The animator is infinite,
 * so "stops" is not cosmetic — a leaked one posts Choreographer frames for the
 * life of the process, which is the bug the voice bar's breath once had.
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardViewMicStateTest {

    private lateinit var controller: ActivityController<Activity>
    private lateinit var root: FrameLayout
    private lateinit var view: KeyboardView

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        root = FrameLayout(activity)
        activity.setContentView(root)
        view = KeyboardView(activity, KeyboardTheme.LIGHT).also { root.addView(it) }
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun `the halo runs only while the mic is active`() {
        assertFalse(view.micPulseRunningForTest())

        view.micActive = true
        assertTrue(view.micPulseRunningForTest())

        view.micActive = false
        assertFalse(view.micPulseRunningForTest())
    }

    @Test
    fun `detaching the keyboard stops the halo`() {
        view.micActive = true
        assertTrue(view.micPulseRunningForTest())

        root.removeView(view)
        assertFalse(view.micPulseRunningForTest())
    }

    @Test
    fun `amplitude from a finished session cannot restart the halo`() {
        view.setMicAmplitude(0.8f)
        assertFalse(view.micPulseRunningForTest())
    }
}
