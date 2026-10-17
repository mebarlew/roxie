package dev.mebarlew.roxie

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("roxie", Context.MODE_PRIVATE)

    var name: String
        get() = sp.getString("name", null).orEmpty()
        set(value) = sp.edit().putString("name", value).apply()

    /** Last position of the floating bubble, or -1 before she was ever placed. */
    var bubbleX: Int
        get() = sp.getInt("bubble_x", -1)
        set(value) = sp.edit().putInt("bubble_x", value).apply()

    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(value) = sp.edit().putInt("bubble_y", value).apply()
}
