package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Winlator-style on-screen PC input, beside the gamepad controls: buttons the user places that
 * press real PC keys (a WASD cluster for a keyboard-only game), buttons that click the mouse,
 * and a touchpad area that moves the pointer relatively (drags aim the cursor; a tap clicks).
 *
 * Steam's touch controls (SteamTouchControls) speak Steam Input, and a keyboard-and-mouse game
 * hears nothing from a virtual gamepad - MiSide was the report (2026-10-17). These widgets are
 * the fork's own and go straight over the channels that already exist: keys through the session's
 * wl_keyboard (the same sendGuestKey the PC keyboard uses), buttons through the scene pointer,
 * and the touchpad through the relative-pointer path, so a game holding a pointer lock (an FPS)
 * gets relative_motion.
 *
 * Layout is global (one set of widgets everywhere), stored as JSON in the "touch_keys"
 * preferences; the drawer's controller page adds widgets and enters the edit mode, where a
 * widget drags, pinches bigger or smaller, and drops on the ✕ chip to go away.
 */
object TouchKeys {
    const val KEY = 0
    const val MOUSE_LEFT = 1
    const val MOUSE_RIGHT = 2
    const val MOUSE_MIDDLE = 3
    const val TOUCHPAD = 4

    /** One placed control: [x]/[y] are its centre as a fraction of the view, like Steam's layout. */
    data class Widget(val id: Long, val kind: Int, val code: Int, val label: String, val x: Float, val y: Float, val scale: Float)

    private const val PREFS = "touch_keys"

    fun load(context: Context): List<Widget> = decode(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("widgets", null))

    fun save(context: Context, widgets: List<Widget>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("widgets", encode(widgets)).apply()
    }

    fun encode(widgets: List<Widget>): String {
        val array = JSONArray()
        for (w in widgets) array.put(JSONObject()
            .put("id", w.id).put("kind", w.kind).put("code", w.code)
            .put("label", w.label).put("x", w.x.toDouble()).put("y", w.y.toDouble())
            .put("scale", w.scale.toDouble()))
        return array.toString()
    }

    fun decode(json: String?): List<Widget> {
        val out = ArrayList<Widget>()
        val array = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return out
        for (i in 0 until array.length()) runCatching {
            val o = array.getJSONObject(i)
            out += Widget(o.getLong("id"), o.getInt("kind"), o.getInt("code"),
                o.optString("label"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                o.getDouble("scale").toFloat())
        }
        return out.filter { it.kind in KEY..TOUCHPAD && it.x in 0f..1f && it.y in 0f..1f && it.scale > 0.2f }
    }

    /** A mouse widget's evdev button, for the store and the picker both. */
    fun buttonOf(kind: Int): Int = when (kind) {
        MOUSE_RIGHT -> PointerGestures.BTN_RIGHT
        MOUSE_MIDDLE -> PointerGestures.BTN_MIDDLE
        else -> PointerGestures.BTN_LEFT
    }

    /** What a fresh widget of [kind] says on its face. */
    fun defaultLabel(kind: Int, code: Int): String = when (kind) {
        MOUSE_LEFT -> "L"
        MOUSE_RIGHT -> "R"
        MOUSE_MIDDLE -> "M"
        TOUCHPAD -> "TOUCH"
        else -> keyName(code)
    }

    // The keys the picker offers: a laptop's worth, laid out as on one. Evdev codes.
    val PICKER_ROWS: List<List<Pair<String, Int>>> = listOf(
        listOf("Esc" to 1, "Tab" to 15, "Caps" to 58, "Shift" to 42, "Ctrl" to 29, "Alt" to 56, "Space" to 57, "Enter" to 28, "Back" to 14),
        listOf("Q" to 16, "W" to 17, "E" to 18, "R" to 19, "T" to 20, "Y" to 21, "U" to 22, "I" to 23, "O" to 24, "P" to 25),
        listOf("A" to 30, "S" to 31, "D" to 32, "F" to 33, "G" to 34, "H" to 35, "J" to 36, "K" to 37, "L" to 38),
        listOf("Z" to 44, "X" to 45, "C" to 46, "V" to 47, "B" to 48, "N" to 49, "M" to 50),
        listOf("1" to 2, "2" to 3, "3" to 4, "4" to 5, "5" to 6, "6" to 7, "7" to 8, "8" to 9, "9" to 10, "0" to 11, "-" to 12, "=" to 13),
        listOf("F1" to 59, "F2" to 60, "F3" to 61, "F4" to 62, "F5" to 63, "F6" to 64, "F7" to 65, "F8" to 66, "F9" to 67, "F10" to 68, "F11" to 87, "F12" to 88),
        listOf("↑" to 103, "↓" to 108, "←" to 105, "→" to 106),
    )

    fun keyName(code: Int): String {
        for (row in PICKER_ROWS) for ((label, c) in row) if (c == code) return label
        return "0x" + code.toString(16)
    }
}

/**
 * The view: draws every [TouchKeys.Widget], answers touches on them, and hands everything else
 * back (returning false) so the game's own touch routing underneath keeps working.
 */
class TouchKeysView(context: Context) : View(context) {
    /** A PC key went down or up (evdev code). */
    var onKey: ((Int, Boolean) -> Unit)? = null

    /** A mouse button went down or up (PointerGestures.BTN_*). */
    var onMouse: ((Int, Boolean) -> Unit)? = null

    /** The touchpad finger moved, in this view's pixels. */
    var onTouchpadDelta: ((Float, Float) -> Unit)? = null

    /** The user left the edit mode (saved): the drawer's pencil flips back. */
    var onEditingChanged: ((Boolean) -> Unit)? = null

    private var widgets: List<TouchKeys.Widget> = TouchKeys.load(context)
    private val editWidgets = ArrayList<TouchKeys.Widget>()

    private var editing = false
    val isEditing: Boolean get() = editing

    private val fingers = HashMap<Int, Assignment>()
    private val pressed = HashSet<Long>()
    private var padFinger: Int? = null

    private class Assignment(val w: TouchKeys.Widget, var lastX: Float, var lastY: Float,
                             var travelled: Float, val downAt: Long)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 16, 16, 24) }
    private val fillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 126, 60, 255) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val dash = Paint(stroke).apply {
        color = Color.argb(255, 179, 136, 255); style = Paint.Style.STROKE; strokeWidth = 2.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f)) }
    private val density = resources.displayMetrics.density

    /** Re-reads the stored widgets (the drawer just added or removed one). */
    fun reload() {
        widgets = TouchKeys.load(context)
        invalidate()
    }

    // -- geometry --

    private fun radius(w: TouchKeys.Widget): Float = when (w.kind) {
        TouchKeys.TOUCHPAD -> 62f * density * w.scale
        else -> 24f * density * w.scale
    }

    private fun rect(w: TouchKeys.Widget): RectF {
        val r = radius(w)
        return if (w.kind == TouchKeys.TOUCHPAD)
            RectF(cx(w) - 1.55f * r, cy(w) - r, cx(w) + 1.55f * r, cy(w) + r)
        else RectF(cx(w) - r, cy(w) - r, cx(w) + r, cy(w) + r)
    }

    private fun cx(w: TouchKeys.Widget) = w.x * width
    private fun cy(w: TouchKeys.Widget) = w.y * height

    private fun hit(x: Float, y: Float): TouchKeys.Widget? {
        val list = if (editing) editWidgets else widgets
        for (i in list.indices.reversed()) {
            val w = list[i]
            val r = rect(w)
            if (r.contains(x, y) || hypot(x - r.centerX(), y - r.centerY()) <= r.width() / 2f) return w
        }
        return null
    }

    // -- drawing --

    override fun onDraw(canvas: Canvas) {
        val list = if (editing) editWidgets else widgets
        for (w in list) {
            val r = rect(w)
            val down = !editing && w.id in pressed
            canvas.drawRoundRect(r, 14f * density, 14f * density, if (down) fillPressed else fill)
            canvas.drawRoundRect(r, 14f * density, 14f * density,
                if (editing && w.id == selectedId) stroke else if (editing) dash else stroke)
            text.textSize = (if (w.kind == TouchKeys.TOUCHPAD) 11f else 15f) * density *
                (if (w.kind == TouchKeys.TOUCHPAD) w.scale.coerceAtLeast(0.7f) else w.scale)
            canvas.drawText(w.label, r.centerX(), r.centerY() - (r.height() * 0.28f) + text.textSize * 0.32f, text)
        }
        if (editing) drawEditChips(canvas)
    }

    private var doneChip = RectF()
    private var trashChip = RectF()

    private fun drawEditChips(canvas: Canvas) {
        val d = density
        doneChip = RectF(width - 74f * d, 18f * d, width - 18f * d, 52f * d)
        canvas.drawRoundRect(doneChip, 10f * d, 10f * d, fill)
        canvas.drawRoundRect(doneChip, 10f * d, 10f * d, stroke)
        text.textSize = 16f * d
        canvas.drawText("✓", doneChip.centerX(), doneChip.centerY() + 6f * d, text)
        // The delete chip rides on the selected widget's corner; drop a widget here to remove it.
        editWidgets.firstOrNull { it.id == selectedId }?.let { w ->
            trashChip = RectF(cx(w) + 30f * d, cy(w) - 62f * d, cx(w) + 62f * d, cy(w) - 30f * d)
            canvas.drawCircle(trashChip.centerX(), trashChip.centerY(), 15f * d, fillPressed)
            canvas.drawCircle(trashChip.centerX(), trashChip.centerY(), 15f * d, stroke)
            canvas.drawText("✕", trashChip.centerX(), trashChip.centerY() + 5f * d, text)
        } ?: run { trashChip.setEmpty() }
    }

    // -- touching --

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (editing) onEditTouch(event) else onPlayTouch(event)
    }

    private fun onPlayTouch(event: MotionEvent): Boolean {
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(index); val y = event.getY(index)
                val w = hit(x, y) ?: return event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
                fingers[event.getPointerId(index)] = Assignment(w, x, y, 0f, event.eventTime)
                if (feedback) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                when (w.kind) {
                    TouchKeys.KEY -> { pressed += w.id; onKey?.invoke(w.code, true) }
                    TouchKeys.MOUSE_LEFT, TouchKeys.MOUSE_RIGHT, TouchKeys.MOUSE_MIDDLE ->
                        { pressed += w.id; onMouse?.invoke(TouchKeys.buttonOf(w.kind), true) }
                    TouchKeys.TOUCHPAD -> if (padFinger == null) padFinger = event.getPointerId(index)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val f = fingers[event.getPointerId(i)] ?: continue
                    if (f.w.kind != TouchKeys.TOUCHPAD) continue
                    val dx = event.getX(i) - f.lastX
                    val dy = event.getY(i) - f.lastY
                    f.travelled += hypot(dx, dy)
                    f.lastX = event.getX(i); f.lastY = event.getY(i)
                    onTouchpadDelta?.invoke(dx, dy)
                }
                return fingers.isNotEmpty()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = event.getPointerId(index)
                val f = fingers.remove(id) ?: return false
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) { releaseAll(); return true }
                when (f.w.kind) {
                    TouchKeys.KEY -> { pressed -= f.w.id; onKey?.invoke(f.w.code, false) }
                    TouchKeys.MOUSE_LEFT, TouchKeys.MOUSE_RIGHT, TouchKeys.MOUSE_MIDDLE ->
                        { pressed -= f.w.id; onMouse?.invoke(TouchKeys.buttonOf(f.w.kind), false) }
                    TouchKeys.TOUCHPAD -> {
                        if (padFinger == id) padFinger = null
                        // A still finger on the pad is a click, where the pointer is.
                        if (f.travelled < slop() * 1.5f && event.eventTime - f.downAt < 350L) {
                            onMouse?.invoke(PointerGestures.BTN_LEFT, true)
                            postDelayed({ onMouse?.invoke(PointerGestures.BTN_LEFT, false) }, 60L)
                        }
                    }
                }
                invalidate()
                return true
            }
        }
        return false
    }

    private fun slop(): Float = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val feedback = true

    /** Lets go of everything a lifted-or-cancelled gesture might have left held. */
    fun releaseAll() {
        val list = widgets
        for (w in list) if (w.id in pressed) {
            if (w.kind == TouchKeys.KEY) onKey?.invoke(w.code, false)
            else onMouse?.invoke(TouchKeys.buttonOf(w.kind), false)
        }
        pressed.clear()
        fingers.clear()
        padFinger = null
        invalidate()
    }

    // -- editing --

    private var selectedId = -1L
    private var dragOffset = 0f to 0f
    private var pinchStart = 0f
    private var pinchScale = 1f

    /** Moves, resizes and removes the widgets; leaving saves them. */
    fun startEditing() {
        releaseAll()
        editing = true
        editWidgets.clear()
        editWidgets += widgets
        selectedId = -1L
        onEditingChanged?.invoke(true)
        invalidate()
    }

    fun stopEditing() {
        if (!editing) return
        editing = false
        selectedId = -1L
        widgets = editWidgets.toList()
        TouchKeys.save(context, widgets)
        onEditingChanged?.invoke(false)
        invalidate()
    }

    private fun onEditTouch(event: MotionEvent): Boolean {
        val x = event.getX(event.actionIndex)
        val y = event.getY(event.actionIndex)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (doneChip.contains(x, y)) { stopEditing(); return true }
                if (trashChip.contains(x, y)) {
                    editWidgets.removeAll { it.id == selectedId }
                    selectedId = -1L
                    invalidate()
                    return true
                }
                val w = hit(x, y)
                selectedId = w?.id ?: -1L
                if (w != null) dragOffset = (x - cx(w)) to (y - cy(w))
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (selectedId >= 0 && event.pointerCount == 2) {
                pinchStart = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                pinchScale = editWidgets.firstOrNull { it.id == selectedId }?.scale ?: 1f
            }
            MotionEvent.ACTION_MOVE -> if (selectedId >= 0) {
                val i = editWidgets.indexOfFirst { it.id == selectedId }
                if (i < 0) return true
                val w = editWidgets[i]
                if (event.pointerCount >= 2 && pinchStart > 0f) {
                    val span = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                    editWidgets[i] = w.copy(scale = (pinchScale * span / pinchStart).coerceIn(0.4f, 3.5f))
                } else if (event.pointerCount == 1) {
                    editWidgets[i] = w.copy(
                        x = ((event.getX(0) - dragOffset.first) / width).coerceIn(0.02f, 0.98f),
                        y = ((event.getY(0) - dragOffset.second) / height).coerceIn(0.05f, 0.98f))
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pinchStart = 0f; invalidate() }
        }
        return true
    }
}
