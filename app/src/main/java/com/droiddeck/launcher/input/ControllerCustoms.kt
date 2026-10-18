package com.droiddeck.launcher.input

import android.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

import com.droiddeck.launcher.R

/**
 * The "Add" sheet for the layout editor - everything a HUD profile can hold: the virtual gamepad's
 * own sticks, buttons and d-pad, buttons that press real PC keys, mouse clicks, a stick that holds
 * WASD, a relative touchpad. Winlator-style: each layout is built from this list, moved in the
 * editor, given a shape in [showManage]. Everything lands in the active layout; the editor passes
 * [onChanged] so its view rebuilds after every change.
 */
object ControllerCustoms {

    /** The add menu, then the key grid when "key" is picked. */
    fun showAdd(activity: android.app.Activity, onChanged: () -> Unit = {}) {
        val layout = ControllerPrefs.activeLayoutId(activity)
        if (layout == ControllerPrefs.LAYOUT_DEFAULT) return
        val d = activity.resources.displayMetrics.density
        fun row(parent: LinearLayout, label: String, action: () -> Unit) {
            parent.addView(Button(activity).apply {
                text = label; isAllCaps = false
                setOnClickListener { sheet?.dismiss(); action(); onChanged() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        // The virtual gamepad itself: any of its pieces, anywhere.
        row(col, activity.getString(R.string.ctrl_pick_stick_l)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "ls") }
        row(col, activity.getString(R.string.ctrl_pick_stick_r)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "rs") }
        row(col, activity.getString(R.string.ctrl_pick_dpad)) { ControllerPrefs.addDpad(activity, layout) }
        row(col, activity.getString(R.string.ctrl_button_a)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "a") }
        row(col, activity.getString(R.string.ctrl_button_b)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "b") }
        row(col, activity.getString(R.string.ctrl_button_x)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "x") }
        row(col, activity.getString(R.string.ctrl_button_y)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "y") }
        row(col, activity.getString(R.string.ctrl_button_lb)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "lb") }
        row(col, activity.getString(R.string.ctrl_button_rb)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "rb") }
        row(col, activity.getString(R.string.ctrl_button_lt)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "lt") }
        row(col, activity.getString(R.string.ctrl_button_rt)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "rt") }
        row(col, activity.getString(R.string.ctrl_target_l3)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "l3") }
        row(col, activity.getString(R.string.ctrl_target_r3)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "r3") }
        row(col, activity.getString(R.string.ctrl_button_view)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "select") }
        row(col, activity.getString(R.string.ctrl_button_menu)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "start") }
        row(col, activity.getString(R.string.ctrl_target_steam)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "guide") }
        row(col, activity.getString(R.string.ctrl_qam_button)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_GAMEPAD_BTN, 0, "qam") }
        // What a virtual pad cannot speak: the PC's own keyboard and mouse.
        row(col, activity.getString(R.string.touchkeys_add_key)) { showKeyGrid(activity, onChanged) }
        row(col, activity.getString(R.string.touchkeys_add_lmb)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_MOUSE_LEFT, 0) }
        row(col, activity.getString(R.string.touchkeys_add_rmb)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_MOUSE_RIGHT, 0) }
        row(col, activity.getString(R.string.touchkeys_joystick_wasd)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_WASD, 0) }
        row(col, activity.getString(R.string.ctrl_add_camera_stick)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_MOUSE_STICK, 0) }
        row(col, activity.getString(R.string.touchkeys_add_touchpad)) { ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_TOUCHPAD, 0) }
        if (ControllerPrefs.layoutById(activity, layout)?.controls?.isNotEmpty() == true) {
            row(col, activity.getString(R.string.touchkeys_manage)) { showManage(activity, onChanged) }
        }
        sheet = AlertDialog.Builder(activity)
            .setTitle(R.string.touchkeys_add_title)
            .setView(ScrollView(activity).apply { addView(col) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private var sheet: AlertDialog? = null

    /** The key grid: one button per key the picker offers. */
    fun showKeyGrid(activity: android.app.Activity, onChanged: () -> Unit = {}) {
        val layout = ControllerPrefs.activeLayoutId(activity)
        if (layout == ControllerPrefs.LAYOUT_DEFAULT) return
        val d = activity.resources.displayMetrics.density
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), 0)
        }
        for (rowKeys in TouchKeys.PICKER_ROWS) {
            val line = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER }
            for ((label, code) in rowKeys) {
                line.addView(Button(activity).apply {
                    text = label; isAllCaps = false
                    setOnClickListener {
                        grid?.dismiss()
                        ControllerPrefs.addControl(activity, layout, ControllerPrefs.KIND_KEY, code)
                        onChanged()
                    }
                }, LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt())
                    .apply { setMargins((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt()) })
            }
            col.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        grid = AlertDialog.Builder(activity)
            .setTitle(R.string.touchkeys_add_key)
            .setView(ScrollView(activity).apply { addView(col) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private var grid: AlertDialog? = null

    /** The active layout's controls: cycle the shape, or take one (and its group) away. */
    fun showManage(activity: android.app.Activity, onChanged: () -> Unit = {}) {
        val layout = ControllerPrefs.activeLayoutId(activity)
        if (layout == ControllerPrefs.LAYOUT_DEFAULT) return
        fun refresh(): List<ControllerPrefs.Custom> = ControllerPrefs.layoutById(activity, layout)?.controls ?: emptyList()
        fun redraw() {
            manage?.dismiss()
            showManage(activity, onChanged)
        }
        val d = activity.resources.displayMetrics.density
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        val seen = HashSet<String>()
        for (c in refresh()) {
            val group = c.group.ifEmpty { c.id }
            if (group in seen) continue
            seen += group
            val title = when {
                c.label.isNotEmpty() -> c.label
                c.kind == ControllerPrefs.KIND_GAMEPAD_BTN -> c.target.uppercase()
                else -> activity.getString(R.string.ctrl_untitled)
            }
            val line = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            fun chip(label: String, action: () -> Unit) {
                line.addView(Button(activity).apply {
                    text = label; isAllCaps = false
                    setOnClickListener { action(); redraw(); onChanged() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            chip(title, {})
            chip(activity.getString(R.string.touchkeys_shape)) {
                ControllerPrefs.setGroupShape(activity, layout, ControllerPrefs.groupOf(c), (c.shape + 1) % 3)
            }
            chip(activity.getString(R.string.touchkeys_remove)) {
                ControllerPrefs.removeControl(activity, layout, c.id)
            }
            col.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        manage = AlertDialog.Builder(activity)
            .setTitle(R.string.touchkeys_manage)
            .setView(ScrollView(activity).apply { addView(col) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private var manage: AlertDialog? = null

    /**
     * One picked control (or d-pad): its shape as three labelled choices, a size slider, and
     * remove - Winlator's element-settings sheet, staying open while the user tries shapes.
     */
    fun showElementSettings(activity: android.app.Activity, layoutId: String, group: String, onChanged: () -> Unit = {}) {
        val controls = ControllerPrefs.layoutById(activity, layoutId)?.controls
            ?.filter { ControllerPrefs.groupOf(it) == group }
            ?.takeIf { it.isNotEmpty() } ?: return
        val first = controls.first()
        val title = when {
            first.label.isNotEmpty() -> first.label
            first.kind == ControllerPrefs.KIND_GAMEPAD_BTN -> first.target.uppercase()
            else -> activity.getString(R.string.ctrl_untitled)
        }
        val d = activity.resources.displayMetrics.density
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        // Shape: three choices, the current one ticked; picking applies at once.
        val shapeRow = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER }
        fun shapeChip(target: Int, label: Int) {
            val active = first.shape == target
            shapeRow.addView(Button(activity).apply {
                text = if (active) "✓ " + activity.getString(label) else activity.getString(label)
                isAllCaps = false
                setOnClickListener {
                    ControllerPrefs.setGroupShape(activity, layoutId, group, target)
                    settings?.dismiss()
                    onChanged()
                    showElementSettings(activity, layoutId, group, onChanged)
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins((2 * d).toInt(), 0, (2 * d).toInt(), 0) })
        }
        shapeChip(ControllerPrefs.SHAPE_CIRCLE, R.string.ctrl_shape_circle)
        shapeChip(ControllerPrefs.SHAPE_ROUNDED, R.string.ctrl_shape_rounded)
        shapeChip(ControllerPrefs.SHAPE_SQUARE, R.string.ctrl_shape_square)
        col.addView(shapeRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Size: a slider over the drawn multiplier, applied when the finger lifts.
        val percent = first.scale * 100f
        val value = android.widget.TextView(activity).apply { text = "${percent.toInt()}%" }
        val slider = android.widget.SeekBar(activity).apply {
            max = 210 // 40% .. 250%
            progress = ((first.scale - 0.4f) * 100f).toInt().coerceIn(0, 210)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    val scale = 0.4f + progress / 100f
                    ControllerPrefs.setGroupScale(activity, layoutId, group, scale)
                    value.text = "${(scale * 100).toInt()}%"
                    onChanged()
                }
            })
        }
        col.addView(LinearLayout(activity).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(android.widget.TextView(activity).apply { text = activity.getString(R.string.ctrl_size) })
            addView(slider, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins((10 * d).toInt(), 0, (10 * d).toInt(), 0) })
            addView(value)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        col.addView(Button(activity).apply {
            text = activity.getString(R.string.touchkeys_remove); isAllCaps = false
            setOnClickListener {
                settings?.dismiss()
                ControllerPrefs.removeControl(activity, layoutId, group)
                onChanged()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, (10 * d).toInt(), 0, 0) })

        settings = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(col) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private var settings: AlertDialog? = null

    /** A yes/no confirm (removing a layout takes its controls with it). */
    fun confirm(activity: android.app.Activity, message: Int, onYes: () -> Unit) {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onYes() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A one-line name prompt (new layout, rename): ok only with non-blank text. */
    fun prompt(activity: android.app.Activity, title: Int, initial: String?, onOk: (String) -> Unit) {
        val d = activity.resources.displayMetrics.density
        val input = EditText(activity).apply {
            setText(initial ?: ""); setSingleLine(true)
        }
        val box = FrameLayout(activity).apply {
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), 0) })
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onOk(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
