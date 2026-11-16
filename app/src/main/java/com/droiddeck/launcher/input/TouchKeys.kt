package com.droiddeck.launcher.input

/**
 * Helpers for the on-screen controller's custom controls (ControllerPrefs.customs): the key
 * picker's rows, a key's name, and the WASD mapping that turns a knob's position into held keys.
 *
 * A keyboard-and-mouse game hears nothing from a virtual gamepad - MiSide was the report
 * (2026-10-17) - so a control can also press a real PC key (the session's wl_keyboard, the same
 * channel the PC keyboard uses), click the mouse (the scene pointer) or be a stick whose
 * movement holds WASD.
 */
object TouchKeys {
    /** The keys the picker offers: a laptop's worth, laid out as on one. Evdev codes. */
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

    /** The WASD keys a stick's knob position (normalised -1..1) holds. Dead zone 0.35. */
    fun wasdKeys(nx: Float, ny: Float): Set<Int> {
        val out = mutableSetOf<Int>()
        val strength = hypot(nx, ny)
        if (strength < 0.35f) return out
        val x = nx / maxOf(strength, 1e-6f)
        val y = ny / maxOf(strength, 1e-6f)
        if (y < -0.5f) out += 17 // W
        if (y > 0.5f) out += 31  // S
        if (x < -0.5f) out += 30 // A
        if (x > 0.5f) out += 32  // D
        return out
    }

    private fun hypot(x: Float, y: Float) = kotlin.math.sqrt(x * x + y * y)
}
