package io.github.jherobred.pane

import android.view.KeyEvent

/** Android keys and characters to PC/XT scancodes (US layout). E0-prefixed keys have bit 7 set. */
object Keys {
    const val SHIFT = 0x2A
    const val CTRL = 0x1D
    const val ALT = 0x38
    const val WIN = 0xDB
    const val ESC = 0x01
    const val TAB = 0x0F
    const val ENTER = 0x1C
    const val BACKSPACE = 0x0E
    const val DELETE = 0xD3
    const val UP = 0xC8
    const val DOWN = 0xD0
    const val LEFT = 0xCB
    const val RIGHT = 0xCD
    val MODIFIERS = setOf(SHIFT, CTRL, ALT, WIN, 0x36, 0x9D, 0xB8, 0xDC)

    private val letters = intArrayOf(
        0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
        0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C,
    )
    private val digits = intArrayOf(0x0B, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A) // 0..9

    private val byKeycode = buildMap {
        for (i in 0..25) put(KeyEvent.KEYCODE_A + i, letters[i])
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, digits[i])
        for (i in 0..9) put(KeyEvent.KEYCODE_F1 + i, 0x3B + i)
        val np = intArrayOf(0x52, 0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49)
        for (i in 0..9) put(KeyEvent.KEYCODE_NUMPAD_0 + i, np[i])
        putAll(
            mapOf(
                KeyEvent.KEYCODE_F11 to 0x57, KeyEvent.KEYCODE_F12 to 0x58,
                KeyEvent.KEYCODE_ESCAPE to ESC, KeyEvent.KEYCODE_TAB to TAB, KeyEvent.KEYCODE_ENTER to ENTER,
                KeyEvent.KEYCODE_DEL to BACKSPACE, KeyEvent.KEYCODE_FORWARD_DEL to DELETE, KeyEvent.KEYCODE_SPACE to 0x39,
                KeyEvent.KEYCODE_MINUS to 0x0C, KeyEvent.KEYCODE_EQUALS to 0x0D, KeyEvent.KEYCODE_LEFT_BRACKET to 0x1A,
                KeyEvent.KEYCODE_RIGHT_BRACKET to 0x1B, KeyEvent.KEYCODE_BACKSLASH to 0x2B, KeyEvent.KEYCODE_SEMICOLON to 0x27,
                KeyEvent.KEYCODE_APOSTROPHE to 0x28, KeyEvent.KEYCODE_GRAVE to 0x29, KeyEvent.KEYCODE_COMMA to 0x33,
                KeyEvent.KEYCODE_PERIOD to 0x34, KeyEvent.KEYCODE_SLASH to 0x35,
                KeyEvent.KEYCODE_SHIFT_LEFT to SHIFT, KeyEvent.KEYCODE_SHIFT_RIGHT to 0x36,
                KeyEvent.KEYCODE_CTRL_LEFT to CTRL, KeyEvent.KEYCODE_CTRL_RIGHT to 0x9D,
                KeyEvent.KEYCODE_ALT_LEFT to ALT, KeyEvent.KEYCODE_ALT_RIGHT to 0xB8,
                KeyEvent.KEYCODE_META_LEFT to WIN, KeyEvent.KEYCODE_META_RIGHT to 0xDC, KeyEvent.KEYCODE_MENU to 0xDD,
                KeyEvent.KEYCODE_CAPS_LOCK to 0x3A, KeyEvent.KEYCODE_NUM_LOCK to 0x45, KeyEvent.KEYCODE_SCROLL_LOCK to 0x46,
                KeyEvent.KEYCODE_SYSRQ to 0xB7, KeyEvent.KEYCODE_BREAK to 0xC5,
                KeyEvent.KEYCODE_INSERT to 0xD2, KeyEvent.KEYCODE_MOVE_HOME to 0xC7, KeyEvent.KEYCODE_MOVE_END to 0xCF,
                KeyEvent.KEYCODE_PAGE_UP to 0xC9, KeyEvent.KEYCODE_PAGE_DOWN to 0xD1,
                KeyEvent.KEYCODE_DPAD_UP to UP, KeyEvent.KEYCODE_DPAD_DOWN to DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT to LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to RIGHT, KeyEvent.KEYCODE_DPAD_CENTER to ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER to 0x9C, KeyEvent.KEYCODE_NUMPAD_DIVIDE to 0xB5,
                KeyEvent.KEYCODE_NUMPAD_MULTIPLY to 0x37, KeyEvent.KEYCODE_NUMPAD_SUBTRACT to 0x4A,
                KeyEvent.KEYCODE_NUMPAD_ADD to 0x4E, KeyEvent.KEYCODE_NUMPAD_DOT to 0x53,
            )
        )
    }

    fun scancode(keyCode: Int): Int? = byKeycode[keyCode]

    private const val SHIFTED_DIGITS = ")!@#$%^&*("
    private val punctuation = mapOf(
        '-' to (0x0C to false), '_' to (0x0C to true), '=' to (0x0D to false), '+' to (0x0D to true),
        '[' to (0x1A to false), '{' to (0x1A to true), ']' to (0x1B to false), '}' to (0x1B to true),
        '\\' to (0x2B to false), '|' to (0x2B to true), ';' to (0x27 to false), ':' to (0x27 to true),
        '\'' to (0x28 to false), '"' to (0x28 to true), '`' to (0x29 to false), '~' to (0x29 to true),
        ',' to (0x33 to false), '<' to (0x33 to true), '.' to (0x34 to false), '>' to (0x34 to true),
        '/' to (0x35 to false), '?' to (0x35 to true), ' ' to (0x39 to false), '\n' to (ENTER to false), '\t' to (TAB to false),
    )

    /** Scancode and whether Shift is needed. Null for characters a US keyboard can't type. */
    fun forChar(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> letters[c - 'a'] to false
        in 'A'..'Z' -> letters[c - 'A'] to true
        in '0'..'9' -> digits[c - '0'] to false
        else -> SHIFTED_DIGITS.indexOf(c).takeIf { it >= 0 }?.let { digits[it] to true } ?: punctuation[c]
    }
}
