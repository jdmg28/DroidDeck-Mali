package com.droiddeck.launcher.input

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The on-screen controller's custom controls (the keyboard-and-mouse ones): the store's JSON
 * round-trip, add's id collisions, and the WASD mapping a stick drives keys with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ControllerCustomsTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ControllerPrefs.setCustoms(context, emptyList())
    }

    @Test fun roundTripKeepsEveryFieldAndDropsJunk() {
        val list = listOf(
            ControllerPrefs.Custom("key17", ControllerPrefs.KIND_KEY, 17, "W"),
            ControllerPrefs.Custom("mouseL", ControllerPrefs.KIND_MOUSE_LEFT, 0, "L", ControllerPrefs.SHAPE_ROUNDED),
            ControllerPrefs.Custom("wasd", ControllerPrefs.KIND_WASD, 0, "WASD", ControllerPrefs.SHAPE_SQUARE),
        )
        ControllerPrefs.setCustoms(context, list)
        assertEquals(list, ControllerPrefs.customs(context))
        // Junk is decoded away, not crashed on.
        ControllerPrefs.setCustoms(context, emptyList())
        ControllerPrefs.prefs(context).edit().putString("customControls", "[{\"id\":\"x\",\"kind\":99,\"code\":0,\"label\":\"?\"},\"junk\"]").commit()
        assertTrue(ControllerPrefs.customs(context).isEmpty())
    }

    @Test fun addNeverCollidesAndLabelsTheKey() {
        ControllerPrefs.addCustom(context, ControllerPrefs.KIND_KEY, 17)
        ControllerPrefs.addCustom(context, ControllerPrefs.KIND_KEY, 17)
        val ids = ControllerPrefs.customs(context).map { it.id }
        assertEquals(listOf("key17", "key17-2"), ids)
        assertEquals("W", ControllerPrefs.customs(context)[0].label)
        assertEquals(TouchKeys.keyName(59), "F1")
    }

    @Test fun wasdMappingHoldsDiagonalsAndRespectsTheDeadZone() {
        assertTrue(TouchKeys.wasdKeys(0f, 0f).isEmpty())
        assertTrue(TouchKeys.wasdKeys(0.2f, 0.2f).isEmpty()) // inside the dead zone
        assertEquals(setOf(17), TouchKeys.wasdKeys(0f, -1f))          // W
        assertEquals(setOf(31), TouchKeys.wasdKeys(0f, 1f))           // S
        assertEquals(setOf(30), TouchKeys.wasdKeys(-1f, 0f))          // A
        assertEquals(setOf(32), TouchKeys.wasdKeys(1f, 0f))           // D
        assertEquals(setOf(17, 32), TouchKeys.wasdKeys(0.8f, -0.8f))  // W + D
        assertEquals(setOf(30, 31), TouchKeys.wasdKeys(-0.9f, 0.9f))  // A + S
    }
}
