package com.droiddeck.launcher.input

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The store behind the Winlator-style on-screen PC keys: round-trip and the junk it refuses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TouchKeysStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("touch_keys", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun roundTripKeepsEveryField() {
        val widgets = listOf(
            TouchKeys.Widget(1L, TouchKeys.KEY, 17, "W", 0.25f, 0.8f, 1.25f),
            TouchKeys.Widget(2L, TouchKeys.MOUSE_LEFT, 0, "L", 0.5f, 0.85f, 1f),
            TouchKeys.Widget(3L, TouchKeys.MOUSE_RIGHT, 0, "R", 0.6f, 0.85f, 0.8f),
            TouchKeys.Widget(4L, TouchKeys.TOUCHPAD, 0, "TOUCH", 0.35f, 0.55f, 1.5f),
        )
        TouchKeys.save(context, widgets)
        assertEquals(widgets, TouchKeys.load(context))
    }

    @Test fun decodeDropsCorruptEntriesAndKeepsTheRest() {
        val json = """[
            {"id":1,"kind":0,"code":17,"label":"W","x":0.25,"y":0.8,"scale":1.0},
            {"id":2,"kind":99,"code":0,"label":"?","x":0.5,"y":0.5,"scale":1.0},
            {"id":3,"kind":4,"code":0,"label":"T","x":9.0,"y":0.5,"scale":1.0},
            {"id":4,"kind":1,"code":0,"label":"L","x":0.5,"y":0.5,"scale":0.0},
            "not an object",
            {"id":5,"kind":4,"code":0,"label":"T","x":0.4,"y":0.6,"scale":1.2}
        ]"""
        val out = TouchKeys.decode(json)
        assertEquals(listOf(1L, 5L), out.map { it.id })
        assertEquals(TouchKeys.TOUCHPAD, out[1].kind)
    }

    @Test fun decodeOfGarbageIsEmptyAndSaveOverwrites() {
        assertTrue(TouchKeys.decode(null).isEmpty())
        assertTrue(TouchKeys.decode("{not json").isEmpty())
        TouchKeys.save(context, listOf(TouchKeys.Widget(9L, TouchKeys.KEY, 30, "A", 0.1f, 0.1f, 1f)))
        assertEquals(1, TouchKeys.load(context).size)
        TouchKeys.save(context, emptyList())
        assertTrue(TouchKeys.load(context).isEmpty())
    }

    @Test fun pickerCoversAKeyboardWorthAndNamesThem() {
        val labels = TouchKeys.PICKER_ROWS.flatten().toMap()
        assertTrue(labels.containsKey("W") && labels.containsKey("Shift") && labels.containsKey("F12"))
        assertEquals(17, labels["W"])
        assertEquals("W", TouchKeys.keyName(17))
        assertEquals("Esc", TouchKeys.keyName(1))
    }
}
