package com.droiddeck.launcher.input

import android.content.Context
import com.droiddeck.launcher.R

object ControllerPrefs {
    const val STEAM_BLUE = 0xFF1A9FFF.toInt()
    const val OFF = "off"

    val tints = listOf(
        STEAM_BLUE to R.string.ctrl_tint_steam_blue,
        0xFF66C0F4.toInt() to R.string.ctrl_tint_sky,
        0xFFE8EEF4.toInt() to R.string.ctrl_tint_white,
        0xFFC77DFF.toInt() to R.string.ctrl_tint_violet,
        0xFF3DDC84.toInt() to R.string.ctrl_tint_green,
        0xFFFFA726.toInt() to R.string.ctrl_tint_amber,
        0xFFFF5252.toInt() to R.string.ctrl_tint_red,
        0xFFFF6FB5.toInt() to R.string.ctrl_tint_pink,
    )

    val opacities = listOf(40, 60, 80, 100)

    val sizes = listOf(80, 90, 100, 110, 125)

    val mappable = listOf(
        "a" to R.string.ctrl_button_a, "b" to R.string.ctrl_button_b, "x" to R.string.ctrl_button_x, "y" to R.string.ctrl_button_y,
        "lb" to R.string.ctrl_button_lb, "rb" to R.string.ctrl_button_rb, "lt" to R.string.ctrl_button_lt, "rt" to R.string.ctrl_button_rt,
        "select" to R.string.ctrl_button_view, "start" to R.string.ctrl_button_menu,
    )

    val targets = listOf(
        "a" to R.string.ctrl_target_a, "b" to R.string.ctrl_target_b, "x" to R.string.ctrl_target_x, "y" to R.string.ctrl_target_y,
        "lb" to R.string.ctrl_target_lb, "rb" to R.string.ctrl_target_rb, "lt" to R.string.ctrl_target_lt, "rt" to R.string.ctrl_target_rt,
        "l3" to R.string.ctrl_target_l3, "r3" to R.string.ctrl_target_r3, "select" to R.string.ctrl_target_view, "start" to R.string.ctrl_target_menu, "guide" to R.string.ctrl_target_steam,
        "up" to R.string.ctrl_target_up, "down" to R.string.ctrl_target_down, "left" to R.string.ctrl_target_left, "right" to R.string.ctrl_target_right,
        OFF to R.string.ctrl_target_hidden,
    )

    class Settings(
        val tint: Int,
        val opacity: Int,
        val size: Int,
        val stickClick: Boolean,
        val adaptiveSticks: Boolean,
        val rumble: Boolean,
        val steamButton: Boolean,
        val qamButton: Boolean,
        val keyboardButton: Boolean,
        val customLayout: Boolean,
        val mapping: Map<String, String>,
    )

    internal fun prefs(context: Context) = context.getSharedPreferences("controller", Context.MODE_PRIVATE)

    fun read(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            tint = p.getInt("tint", STEAM_BLUE),
            opacity = p.getInt("opacity", 100).takeIf { it in opacities } ?: 100,
            size = p.getInt("size", 100).takeIf { it in sizes } ?: 100,
            stickClick = p.getBoolean("stickClick", true),
            adaptiveSticks = p.getBoolean("adaptiveSticks", true),
            rumble = rumbleEnabled(context),
            steamButton = p.getBoolean("steamButton", true),
            qamButton = p.getBoolean("qamButton", true),
            keyboardButton = p.getBoolean("keyboardButton", true),
            customLayout = p.all.keys.any { it.startsWith("layout.") },
            mapping = mappable.associate { (id, _) -> id to target(context, id) },
        )
    }

    fun target(context: Context, id: String): String =
        prefs(context).getString("map.$id", id)?.takeIf { t -> targets.any { it.first == t } } ?: id

    fun setTarget(context: Context, id: String, target: String) {
        prefs(context).edit().putString("map.$id", target).apply()
    }

    fun resetMapping(context: Context) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.filter { it.startsWith("map.") }.forEach { remove(it) } }.apply()
    }

    fun setTint(context: Context, tint: Int) {
        prefs(context).edit().putInt("tint", tint).apply()
    }

    fun setOpacity(context: Context, percent: Int) {
        prefs(context).edit().putInt("opacity", percent).apply()
    }

    fun setSize(context: Context, percent: Int) {
        prefs(context).edit().putInt("size", percent).apply()
    }

    fun setStickClick(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("stickClick", on).apply()
    }

    fun setAdaptiveSticks(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("adaptiveSticks", on).apply()
    }

    fun rumbleEnabled(context: Context): Boolean = prefs(context).getBoolean("rumble", true)

    fun setRumble(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("rumble", on).apply()
    }

    fun setSteamButton(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("steamButton", on).apply()
    }

    fun setQamButton(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("qamButton", on).apply()
    }

    fun setKeyboardButton(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("keyboardButton", on).apply()
    }

    fun layout(context: Context, width: Int, height: Int): Map<String, Pair<Float, Float>> {
        val raw = prefs(context).getString("layout.${width}x$height", null) ?: return emptyMap()
        return raw.split(';').mapNotNull { entry ->
            val (group, pos) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val (x, y) = pos.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            group to (x to y)
        }.toMap()
    }

    fun setLayout(context: Context, width: Int, height: Int, positions: Map<String, Pair<Float, Float>>) {
        val raw = positions.entries.joinToString(";") { (group, pos) -> "$group:${pos.first},${pos.second}" }
        prefs(context).edit().putString("layout.${width}x$height", raw).apply()
    }

    fun resetLayout(context: Context, width: Int, height: Int) {
        prefs(context).edit().remove("layout.${width}x$height").apply()
    }

    fun resetAll(context: Context) {
        // On older Android versions clear() does not notify listeners; restore rumble explicitly.
        prefs(context).edit().clear().putBoolean("rumble", true).apply()
    }

    // ---- Custom on-screen controls (input/OnScreenControls draws and drives them) ----
    //
    // A keyboard-and-mouse game hears nothing from a virtual gamepad, so the pad can carry
    // controls the user adds: buttons that press real PC keys, mouse clicks, a stick that holds
    // WASD, a relative touchpad. Lives beside the layout (the editor drags them like any other).

    const val KIND_KEY = 0
    const val KIND_MOUSE_LEFT = 1
    const val KIND_MOUSE_RIGHT = 2
    const val KIND_MOUSE_MIDDLE = 3
    const val KIND_WASD = 4
    const val KIND_TOUCHPAD = 5

    /** 0 circle (the default), 1 rounded square, 2 square. */
    const val SHAPE_CIRCLE = 0
    const val SHAPE_ROUNDED = 1
    const val SHAPE_SQUARE = 2

    data class Custom(val id: String, val kind: Int, val code: Int, val label: String, val shape: Int = SHAPE_CIRCLE)

    fun customs(context: Context): List<Custom> = decodeCustoms(prefs(context).getString("customControls", null))

    fun setCustoms(context: Context, list: List<Custom>) {
        val array = org.json.JSONArray()
        for (c in list) array.put(org.json.JSONObject()
            .put("id", c.id).put("kind", c.kind).put("code", c.code)
            .put("label", c.label).put("shape", c.shape))
        prefs(context).edit().putString("customControls", array.toString()).apply()
    }

    /** Adds one custom control and returns the list; [id] is derived so repeats never collide. */
    fun addCustom(context: Context, kind: Int, code: Int): List<Custom> {
        val list = customs(context).toMutableList()
        val base = when (kind) {
            KIND_KEY -> "key" + code
            KIND_MOUSE_LEFT -> "mouseL"
            KIND_MOUSE_RIGHT -> "mouseR"
            KIND_MOUSE_MIDDLE -> "mouseM"
            KIND_WASD -> "wasd"
            else -> "pad"
        }
        var id = base
        var n = 2
        while (list.any { it.id == id }) id = base + "-" + n++
        val label = when (kind) {
            KIND_KEY -> TouchKeys.keyName(code)
            KIND_MOUSE_LEFT -> "L"
            KIND_MOUSE_RIGHT -> "R"
            KIND_MOUSE_MIDDLE -> "M"
            KIND_WASD -> "WASD"
            else -> "TOUCH"
        }
        list += Custom(id, kind, code, label)
        setCustoms(context, list)
        return list
    }

    fun decodeCustoms(json: String?): List<Custom> {
        val out = ArrayList<Custom>()
        val array = json?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } ?: return out
        for (i in 0 until array.length()) runCatching {
            val o = array.getJSONObject(i)
            val kind = o.getInt("kind")
            if (kind !in KIND_KEY..KIND_TOUCHPAD) return@runCatching
            out += Custom(o.getString("id"), kind, o.getInt("code"),
                o.optString("label"), o.optInt("shape", 0).coerceIn(0, 2))
        }
        return out
    }

    fun resetAllLayouts(context: Context) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.filter { it.startsWith("layout.") }.forEach { remove(it) } }.apply()
    }
}
