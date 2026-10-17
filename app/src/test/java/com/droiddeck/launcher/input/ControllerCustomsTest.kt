package com.droiddeck.launcher.input

import android.content.Context
import com.droiddeck.launcher.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The controller's HUD layouts (Winlator-style profiles): the JSON round-trip, switching the
 * active one, adding controls with collision-safe ids, the shared-group d-pad, and the R18
 * extras' migration into a layout of their own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ControllerCustomsTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ControllerPrefs.prefs(context).edit().clear().commit()
    }

    @Test fun layoutsRoundTripAndSwitch() {
        val made = ControllerPrefs.newLayout(context, "Teclas")
        assertEquals("Teclas", ControllerPrefs.layoutById(context, made.id)?.name)
        assertTrue(ControllerPrefs.layouts(context).none { it.id == ControllerPrefs.LAYOUT_DEFAULT })
        ControllerPrefs.setActiveLayout(context, made.id)
        assertEquals(made.id, ControllerPrefs.activeLayoutId(context))
        // An unknown active id falls back to the default, never to a dead profile.
        ControllerPrefs.setActiveLayout(context, "gone")
        assertEquals(ControllerPrefs.LAYOUT_DEFAULT, ControllerPrefs.activeLayoutId(context))
        ControllerPrefs.renameLayout(context, made.id, "Ratón")
        assertEquals("Ratón", ControllerPrefs.layoutById(context, made.id)?.name)
        ControllerPrefs.removeLayout(context, made.id)
        assertNull(ControllerPrefs.layoutById(context, made.id))
    }

    @Test fun addedControlsKeepEveryFieldAndNeverCollide() {
        val made = ControllerPrefs.newLayout(context, "Mezcla")
        ControllerPrefs.addControl(context, made.id, ControllerPrefs.KIND_KEY, 17, target = "")
        ControllerPrefs.addControl(context, made.id, ControllerPrefs.KIND_KEY, 17, target = "")
        ControllerPrefs.addControl(context, made.id, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "ls")
        val controls = ControllerPrefs.layoutById(context, made.id)!!.controls
        assertEquals(listOf("key17", "key17-2", "ls"), controls.map { it.id })
        assertEquals("W", controls[0].label)
        assertEquals(ControllerPrefs.KIND_GAMEPAD_BTN, controls[2].kind)
        assertEquals("ls", controls[2].target)
        assertEquals(TouchKeys.keyName(59), "F1")
        // Adding to the fitted pad does nothing: the default is the built-in layout only.
        assertTrue(ControllerPrefs.addControl(context, ControllerPrefs.LAYOUT_DEFAULT, ControllerPrefs.KIND_KEY, 17).isEmpty())
    }

    @Test fun positionsAreStoredPerLayoutAndCanBeCleared() {
        val made = ControllerPrefs.newLayout(context, "Lugares")
        ControllerPrefs.addControl(context, made.id, ControllerPrefs.KIND_MOUSE_LEFT, 0)
        ControllerPrefs.setLayoutPositions(context, made.id, mapOf("mouseL" to (0.25f to 0.75f)))
        var stored = ControllerPrefs.layoutById(context, made.id)!!.controls.single()
        assertEquals(0.25f, stored.x, 0f)
        assertEquals(0.75f, stored.y, 0f)
        // A second layout keeps its own, so switching swaps the whole HUD.
        val other = ControllerPrefs.newLayout(context, "Otra")
        ControllerPrefs.addControl(context, other.id, ControllerPrefs.KIND_MOUSE_LEFT, 0)
        assertTrue(ControllerPrefs.layoutById(context, other.id)!!.controls.single().x < 0f)
        ControllerPrefs.clearLayoutPositions(context, made.id)
        stored = ControllerPrefs.layoutById(context, made.id)!!.controls.single()
        assertTrue(stored.x < 0f && stored.y < 0f)
    }

    @Test fun theDpadAddsFourControlsThatMoveAsOne() {
        val made = ControllerPrefs.newLayout(context, "Pad")
        ControllerPrefs.addDpad(context, made.id)
        val controls = ControllerPrefs.layoutById(context, made.id)!!.controls
        assertEquals(listOf("up", "right", "down", "left"), controls.map { it.target })
        // One shared group is the point: the cross drags as a single thing.
        assertEquals(1, controls.map { it.group }.distinct().size)
        // Removing one arrow takes the whole cross: they drag and live together.
        ControllerPrefs.removeControl(context, made.id, controls[0].id)
        assertTrue(ControllerPrefs.layoutById(context, made.id)!!.controls.isEmpty())
    }

    @Test fun theOldExtraControlsMigrateIntoTheirOwnLayout() {
        val legacy = "[{\"id\":\"key17\",\"kind\":0,\"code\":17,\"label\":\"W\",\"shape\":1}," +
            "{\"id\":\"wasd\",\"kind\":4,\"code\":0,\"label\":\"WASD\"}]"
        ControllerPrefs.prefs(context).edit().putString("customControls", legacy).commit()
        val layouts = ControllerPrefs.layouts(context)
        assertEquals(1, layouts.size)
        assertEquals(context.getString(R.string.ctrl_layout_imported), layouts[0].name)
        assertEquals(listOf("key17", "wasd"), layouts[0].controls.map { it.id })
        assertEquals(ControllerPrefs.SHAPE_ROUNDED, layouts[0].controls[0].shape)
        assertFalse(ControllerPrefs.prefs(context).contains("customControls"))
        // A second look must not stack another copy.
        assertEquals(1, ControllerPrefs.layouts(context).size)
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
