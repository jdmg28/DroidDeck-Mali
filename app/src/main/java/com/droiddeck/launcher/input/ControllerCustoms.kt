package com.droiddeck.launcher.input

import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

import com.droiddeck.launcher.R

/**
 * The "Add" sheet for the on-screen controller's custom controls - the keyboard-and-mouse
 * controls a virtual gamepad can't speak: a button that presses a real PC key, mouse clicks, a
 * stick that holds WASD, a relative touchpad. Offered from the layout editor's bar and from the
 * controller settings' Disposición group; [onChanged] fires after every store change (the editor
 * passes its [OnScreenControls.rebuild]).
 */
object ControllerCustoms {

    /** The add menu, then the key grid when "key" is picked. */
    fun showAdd(activity: android.app.Activity, onChanged: () -> Unit = {}) {
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
        row(col, activity.getString(R.string.touchkeys_add_key)) { showKeyGrid(activity, onChanged) }
        row(col, activity.getString(R.string.touchkeys_add_lmb)) { ControllerPrefs.addCustom(activity, ControllerPrefs.KIND_MOUSE_LEFT, 0) }
        row(col, activity.getString(R.string.touchkeys_add_rmb)) { ControllerPrefs.addCustom(activity, ControllerPrefs.KIND_MOUSE_RIGHT, 0) }
        row(col, activity.getString(R.string.touchkeys_joystick_wasd)) { ControllerPrefs.addCustom(activity, ControllerPrefs.KIND_WASD, 0) }
        row(col, activity.getString(R.string.touchkeys_add_touchpad)) { ControllerPrefs.addCustom(activity, ControllerPrefs.KIND_TOUCHPAD, 0) }
        if (ControllerPrefs.customs(activity).isNotEmpty()) {
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
                        ControllerPrefs.addCustom(activity, ControllerPrefs.KIND_KEY, code)
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

    /** The customs already added: cycle the shape, or take one away. */
    fun showManage(activity: android.app.Activity, onChanged: () -> Unit = {}) {
        val context = activity
        fun refresh(): List<ControllerPrefs.Custom> = ControllerPrefs.customs(context)
        fun redraw() {
            manage?.dismiss()
            showManage(activity, onChanged)
        }
        val d = activity.resources.displayMetrics.density
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        for (c in refresh()) {
            val line = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            fun chip(label: String, action: () -> Unit) {
                line.addView(Button(context).apply {
                    text = label; isAllCaps = false
                    setOnClickListener { action(); redraw(); onChanged() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            chip(c.label, {})
            chip(activity.getString(R.string.touchkeys_shape)) {
                val list = refresh().toMutableList()
                val i = list.indexOfFirst { it.id == c.id }
                if (i >= 0) list[i] = c.copy(shape = (c.shape + 1) % 3)
                ControllerPrefs.setCustoms(context, list)
            }
            chip(activity.getString(R.string.touchkeys_remove)) {
                ControllerPrefs.setCustoms(context, refresh().filterNot { it.id == c.id })
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
}
