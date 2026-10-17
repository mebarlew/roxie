package dev.mebarlew.roxie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONObject

/** The pickable buddies. Art comes from art/buddies.py, exported to assets/buddies/<id>/. */
enum class Buddy(val id: String, val title: String, val vibe: String) {
    RUBBER(
        "rubber", "Sudo",
        "a yellow rubber duck, the kind developers explain their code to. You're a sharp, chill coding buddy who loves a good debugging session",
    ),
    DUCK("duck", "Duckling", "a fluffy little duckling, curious and cheerful"),
    CAT("cat", "Cat", "an orange tabby cat, a bit sassy but secretly sweet"),
    FROG("frog", "Frog", "a laid-back green frog with big eyes and a goofy grin"),
    BUNNY("bunny", "Bunny", "a soft white bunny, gentle and upbeat");

    companion object {
        const val SIZE = 64

        fun of(id: String) = Buddy.entries.firstOrNull { it.id == id } ?: RUBBER
    }
}

enum class Anim(val id: String) {
    IDLE("idle"), LISTENING("listening"), THINKING("thinking"), TALKING("talking"),
    HAPPY("happy"), SLEEPY("sleepy"), DRAGGED("dragged"),
}

/** One 100 ms animation step: which frame, bob in sprite pixels, squash (>0 flat, <0 tall), effect. */
class AnimStep(val frame: String, val dy: Int, val squash: Float, val fx: String)

const val STEP_MS = 100L
const val IDLE_FRAME = "open_smile_rest"

/** Frames the view picks itself for lip-sync while the voice plays. */
val LIP_SYNC_OPEN = listOf("open_talk1_rest", "open_talk2_rest")

/** Candy colours for the ring around the floating bubble; the first one is the default. */
val CANDY = linkedMapOf(
    "Blueberry" to Color.rgb(110, 160, 240),
    "Strawberry" to Color.rgb(255, 128, 160),
    "Mint" to Color.rgb(112, 214, 178),
    "Lavender" to Color.rgb(178, 148, 240),
    "Lemon" to Color.rgb(255, 216, 88),
)

/** Loads frames and animation timings from assets and caches them. Main thread only. */
object BuddyArt {
    private val frames = HashMap<String, Bitmap>()
    private var anims: Map<Anim, List<AnimStep>>? = null

    fun frame(context: Context, buddy: Buddy, key: String): Bitmap = frames.getOrPut("${buddy.id}/$key") {
        context.assets.open("buddies/${buddy.id}/$key.png").use { BitmapFactory.decodeStream(it) }
    }

    fun anim(context: Context, anim: Anim): List<AnimStep> =
        (anims ?: load(context).also { anims = it }).getValue(anim)

    private fun load(context: Context): Map<Anim, List<AnimStep>> {
        val json = JSONObject(context.assets.open("buddies/anims.json").bufferedReader().use { it.readText() })
        return Anim.entries.associateWith { anim ->
            val steps = json.getJSONArray(anim.id)
            List(steps.length()) { i ->
                val s = steps.getJSONArray(i)
                AnimStep(s.getString(0), s.getInt(1), s.getDouble(2).toFloat(), s.getString(3))
            }
        }
    }
}
