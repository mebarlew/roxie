package dev.mebarlew.roxie

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("roxie", Context.MODE_PRIVATE)

    var name: String
        get() = sp.getString("name", null).orEmpty()
        set(value) = sp.edit().putString("name", value).apply()

    var buddy: Buddy
        get() = Buddy.of(sp.getString("buddy", null).orEmpty())
        set(value) = sp.edit().putString("buddy", value.id).apply()

    /** A key of [CANDY]: the colour of the ring around the floating bubble. */
    var color: String
        get() = sp.getString("color", null)?.takeIf { it in CANDY } ?: CANDY.keys.first()
        set(value) = sp.edit().putString("color", value).apply()

    /** Last position of the floating bubble, or -1 before it was ever placed. */
    var bubbleX: Int
        get() = sp.getInt("bubble_x", -1)
        set(value) = sp.edit().putInt("bubble_x", value).apply()

    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(value) = sp.edit().putInt("bubble_y", value).apply()
}
