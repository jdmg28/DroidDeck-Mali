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
        /** Which HUD layout Disposición switches: [LAYOUT_DEFAULT] or a custom one's id. */
        val activeLayoutId: String,
        /** The custom layouts, id to name, for the Disposición picker. */
        val layoutNames: List<Pair<String, String>>,
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
            activeLayoutId = activeLayoutId(context),
            layoutNames = layouts(context).map { it.id to it.name },
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

    // ---- HUD layouts (named profiles, Winlator-style) ------------------------------------------
    //
    // "default" is the fitted pad the app draws. Every other layout is a canvas the user builds:
    // gamepad sticks/buttons/d-pad, PC keys, mouse clicks, a WASD stick, a relative touchpad -
    // each with its shape and its own normalized position, stored per layout so switching the
    // active one in Disposición swaps the whole HUD (a different one per game, as in Winlator).

    const val LAYOUT_DEFAULT = "default"
    const val KIND_KEY = 0
    const val KIND_MOUSE_LEFT = 1
    const val KIND_MOUSE_RIGHT = 2
    const val KIND_MOUSE_MIDDLE = 3
    const val KIND_WASD = 4
    const val KIND_TOUCHPAD = 5
    /** A piece of the virtual gamepad itself; [Custom.target] names it ("a", "ls", "up", ...). */
    const val KIND_GAMEPAD_BTN = 6

    /** 0 circle (the default), 1 rounded square, 2 square. */
    const val SHAPE_CIRCLE = 0
    const val SHAPE_ROUNDED = 1
    const val SHAPE_SQUARE = 2

    data class Custom(
        val id: String, val kind: Int, val code: Int, val label: String,
        val shape: Int = SHAPE_CIRCLE,
        /** KIND_GAMEPAD_BTN only: which pad control this is ("a", "ls", "up", "l3", ...). */
        val target: String = "",
        /** Controls dragged together (a d-pad); empty means this control is its own group. */
        val group: String = "",
        /** Normalized centre; negative means "nobody placed it yet". */
        val x: Float = -1f, val y: Float = -1f,
        /** Drawn and hit at [ControllerPrefs.sizes]-like multiplier: 1 is the natural size. */
        val scale: Float = 1f,
    )

    data class Layout(val id: String, val name: String, val controls: List<Custom> = emptyList())

    private fun customJson(c: Custom) = org.json.JSONObject()
        .put("id", c.id).put("kind", c.kind).put("code", c.code)
        .put("label", c.label).put("shape", c.shape)
        .put("target", c.target).put("group", c.group).put("x", c.x.toDouble()).put("y", c.y.toDouble())
        .put("scale", c.scale.toDouble())

    private fun decodeCustoms(o: org.json.JSONObject): Custom? {
        val kind = runCatching { o.getInt("kind") }.getOrNull() ?: return null
        if (kind !in KIND_KEY..KIND_GAMEPAD_BTN) return null
        val shape = runCatching { o.optInt("shape", 0) }.getOrDefault(0).coerceIn(0, 2)
        return Custom(
            id = runCatching { o.getString("id") }.getOrNull() ?: return null,
            kind = kind,
            code = runCatching { o.getInt("code") }.getOrDefault(0),
            label = o.optString("label"),
            shape = shape,
            target = o.optString("target"),
            group = o.optString("group"),
            x = o.optString("x").toFloatOrNull() ?: -1f,
            y = o.optString("y").toFloatOrNull() ?: -1f,
            scale = (o.optString("scale").toFloatOrNull() ?: 1f).coerceIn(0.4f, 2.5f),
        )
    }

    /** Keeps the store from depending on which screen opens first: the R18 extras become a layout. */
    private fun migrate(context: Context) {
        val p = prefs(context)
        if (p.contains("controllerLayouts")) return
        val legacy = p.getString("customControls", null)
        val list = ArrayList<Layout>()
        val array = legacy?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
        if (array != null && array.length() > 0) {
            val controls = ArrayList<Custom>()
            for (i in 0 until array.length()) runCatching { decodeCustoms(array.getJSONObject(i)) }.getOrNull()?.let { controls += it }
            if (controls.isNotEmpty()) {
                list += Layout("p" + System.nanoTime(), context.getString(R.string.ctrl_layout_imported), controls)
            }
        }
        p.edit().putString("controllerLayouts", layoutsJson(list)).remove("customControls").apply()
    }

    private fun layoutsJson(list: List<Layout>): String {
        val array = org.json.JSONArray()
        for (l in list) {
            val controls = org.json.JSONArray()
            for (c in l.controls) controls.put(customJson(c))
            array.put(org.json.JSONObject().put("id", l.id).put("name", l.name).put("controls", controls))
        }
        return array.toString()
    }

    fun layouts(context: Context): List<Layout> {
        migrate(context)
        val array = prefs(context).getString("controllerLayouts", null)
            ?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
            ?: return emptyList()
        val out = ArrayList<Layout>()
        for (i in 0 until array.length()) runCatching {
            val o = array.getJSONObject(i)
            val id = o.getString("id")
            if (id == LAYOUT_DEFAULT) return@runCatching
            val controls = ArrayList<Custom>()
            val cs = o.optJSONArray("controls")
            if (cs != null) for (j in 0 until cs.length()) decodeCustoms(cs.getJSONObject(j))?.let { controls += it }
            out += Layout(id, o.optString("name"), controls)
        }
        return out
    }

    fun layoutById(context: Context, id: String): Layout? =
        if (id == LAYOUT_DEFAULT) null else layouts(context).firstOrNull { it.id == id }

    fun putLayout(context: Context, layout: Layout) {
        val list = layouts(context).toMutableList()
        val i = list.indexOfFirst { it.id == layout.id }
        if (i >= 0) list[i] = layout else list += layout
        prefs(context).edit().putString("controllerLayouts", layoutsJson(list)).apply()
    }

    /** An empty canvas with [name]; Disposición switches to it. */
    fun newLayout(context: Context, name: String): Layout {
        migrate(context)
        val layout = Layout("p" + System.nanoTime(), name)
        putLayout(context, layout)
        return layout
    }

    fun removeLayout(context: Context, id: String) {
        if (id == LAYOUT_DEFAULT) return
        prefs(context).edit().putString("controllerLayouts", layoutsJson(layouts(context).filterNot { it.id == id })).apply()
        if (activeLayoutId(context) == id) setActiveLayout(context, LAYOUT_DEFAULT)
    }

    fun renameLayout(context: Context, id: String, name: String) {
        layoutById(context, id)?.let { putLayout(context, it.copy(name = name)) }
    }

    fun activeLayoutId(context: Context): String {
        val id = prefs(context).getString("activeLayout", LAYOUT_DEFAULT) ?: LAYOUT_DEFAULT
        return if (id == LAYOUT_DEFAULT || layouts(context).any { it.id == id }) id else LAYOUT_DEFAULT
    }

    fun setActiveLayout(context: Context, id: String) {
        prefs(context).edit().putString("activeLayout", id).apply()
    }

    /** Saves a drag: every control in [positions]' groups gets the group's normalized centre. */
    fun setLayoutPositions(context: Context, id: String, positions: Map<String, Pair<Float, Float>>) {
        layoutById(context, id)?.let { l ->
            putLayout(context, l.copy(controls = l.controls.map { c ->
                positions[c.group.ifEmpty { c.id }]?.let { c.copy(x = it.first, y = it.second) } ?: c
            }))
        }
    }

    fun clearLayoutPositions(context: Context, id: String) {
        layoutById(context, id)?.let { putLayout(context, it.copy(controls = it.controls.map { c -> c.copy(x = -1f, y = -1f) })) }
    }

    private fun insert(context: Context, layoutId: String, controls: List<Custom>): List<Custom> {
        if (layoutId == LAYOUT_DEFAULT) return emptyList()
        migrate(context)
        val l = layoutById(context, layoutId) ?: return emptyList()
        val list = l.controls.toMutableList()
        for (c in controls) {
            var id = c.id
            var n = 2
            while (list.any { it.id == id }) { id = c.id + "-" + n; n++ }
            list += c.copy(id = id)
        }
        putLayout(context, l.copy(controls = list))
        return list
    }

    /** Adds one control to [layoutId] and returns the layout's controls; ids never collide. */
    fun addControl(context: Context, layoutId: String, kind: Int, code: Int, target: String = ""): List<Custom> {
        val base = when (kind) {
            KIND_KEY -> "key$code"
            KIND_MOUSE_LEFT -> "mouseL"
            KIND_MOUSE_RIGHT -> "mouseR"
            KIND_MOUSE_MIDDLE -> "mouseM"
            KIND_WASD -> "wasd"
            KIND_TOUCHPAD -> "pad"
            else -> if (target.isNotEmpty()) target else "pad"
        }
        val label = when (kind) {
            KIND_KEY -> TouchKeys.keyName(code)
            KIND_MOUSE_LEFT -> "L"
            KIND_MOUSE_RIGHT -> "R"
            KIND_MOUSE_MIDDLE -> "M"
            KIND_WASD -> "WASD"
            KIND_TOUCHPAD -> "TOUCH"
            else -> ""
        }
        return insert(context, layoutId, listOf(Custom(base, kind, code, label, target = target)))
    }

    /** A d-pad: four controls that drag together, one per direction. */
    fun addDpad(context: Context, layoutId: String): List<Custom> {
        val group = "dpad" + System.nanoTime()
        val arrows = listOf(
            Custom("padU", KIND_GAMEPAD_BTN, 0, "", target = "up", group = group),
            Custom("padR", KIND_GAMEPAD_BTN, 0, "", target = "right", group = group),
            Custom("padD", KIND_GAMEPAD_BTN, 0, "", target = "down", group = group),
            Custom("padL", KIND_GAMEPAD_BTN, 0, "", target = "left", group = group),
        )
        return insert(context, layoutId, arrows)
    }

    fun setControlShape(context: Context, layoutId: String, controlId: String, shape: Int) {
        layoutById(context, layoutId)?.let { l ->
            putLayout(context, l.copy(controls = l.controls.map { c -> if (c.id == controlId) c.copy(shape = shape) else c }))
        }
    }

    /** Removes one control - or, when it belongs to one, the whole group - from [layoutId]. */
    fun removeControl(context: Context, layoutId: String, controlId: String) {
        layoutById(context, layoutId)?.let { l ->
            val gone = l.controls.firstOrNull { it.id == controlId || it.group == controlId } ?: return
            putLayout(context, l.copy(controls = l.controls.filterNot {
                it.id == gone.id || (gone.group.isNotEmpty() && it.group == gone.group)
            }))
        }
    }

    /** The group a control belongs to: its own id when it drags alone. */
    fun groupOf(c: Custom): String = c.group.ifEmpty { c.id }

    fun setGroupShape(context: Context, layoutId: String, group: String, shape: Int) {
        layoutById(context, layoutId)?.let { l ->
            putLayout(context, l.copy(controls = l.controls.map { c -> if (groupOf(c) == group) c.copy(shape = shape) else c }))
        }
    }

    fun setGroupScale(context: Context, layoutId: String, group: String, scale: Float) {
        val k = scale.coerceIn(0.4f, 2.5f)
        layoutById(context, layoutId)?.let { l ->
            putLayout(context, l.copy(controls = l.controls.map { c -> if (groupOf(c) == group) c.copy(scale = k) else c }))
        }
    }

    fun resetAllLayouts(context: Context) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.filter { it.startsWith("layout.") }.forEach { remove(it) } }.apply()
    }
}
