package dev.mebarlew.roxie

import android.graphics.Bitmap
import android.graphics.Color

enum class Pose { IDLE, BLINK, TALKING, LISTENING, THINKING, SLEEPY, HAPPY }

/** Port of art/sprite.py: one 40x40 indexed grid, poses as overrides, colours as palette swaps. */
object Sprite {
    const val SIZE = 40
    private const val EYE_X = 11
    private const val EYE_Y = 21

    // Left half of the face; the right half is mirrored.
    private val HALF = listOf(
        "....................",
        ".....OO.............",
        ".....OHO............",
        "....OHHHO...........",
        "....OHEHHO..........",
        "....OHEEHHO....OOOOO",
        "....OHEEEHHO.OOHHHHH",
        "...OHHEEEEHHOHHHHHHH",
        "...OHHEEEEEHHHHHHHHH",
        "...OHHHHHHHHHHHHHHHH",
        "...OHHHHHHHHHHHHHHHH",
        "...Ohhhhhhhhhhhhhhhh",
        "..OLHLHLHLHLHLHLHLHL",
        "..OLHLHLHLHLHLHLHLHL",
        "..OLHLHLHLHLHLHLHLHL",
        "...Ohhhhhhhhhhhhhhhh",
        "...Orrrrrrrrrrrrrrrr",
        "...ORlllRRRRllRRRRRR",
        "...ORRRRRRRRRRRRrrRR",
        "...ORRRRrRrrRRRrsSrR",
        "...ORRRRrrsSSrrrSSSs",
        "...ORRRRrsSKKKKSSSSS",
        "...ORRRRrSSSKWWKKSSS",
        "...ORRRRrSSSKWWKKSSS",
        "...ORRRRrSSSKKKKKSSS",
        "...ORRRrSSSSKIIJKSSS",
        "...ORRRrSSSSSKKKSSSS",
        "....ORRrSBBBBSSSSSSS",
        "....ORRrSSBBSSSSSSMS",
        ".....ORrSSSSSSSSSSSM",
        "......OroSSSSSSSSSSS",
        ".......O.ooooSSSSSSS",
        ".............ooooooo",
        "..........OOOOOOOsss",
        "........OOCCCCCOAOOO",
        "......OOTOCCCCCOAAAa",
        ".....OTTTOCCCCcOAOOO",
        ".....OTTTTOCCcOOOTTT",
        "....OTTTTTTOOOTTTTTT",
        "....OTTTTTTTTTTTTTTT",
    )

    private val ROLES = mapOf(
        'O' to "outline", 'H' to "hat", 'h' to "hat_shade", 'L' to "hat_light", 'E' to "ear_inner",
        'R' to "hair", 'r' to "hair_shade", 'l' to "hair_light", 'S' to "skin", 's' to "skin_shade",
        'o' to "jaw", 'K' to "eye", 'W' to "eye_light", 'I' to "iris", 'J' to "iris_light",
        'B' to "blush", 'M' to "mouth", 'N' to "tongue", 'T' to "outfit", 'C' to "collar",
        'c' to "collar_shade", 'A' to "bow", 'a' to "bow_shade",
    )

    private val BASE = mapOf(
        "outline" to Color.rgb(52, 40, 92), "hair" to Color.rgb(140, 86, 66),
        "hair_shade" to Color.rgb(100, 58, 52), "hair_light" to Color.rgb(190, 130, 96),
        "skin" to Color.rgb(255, 226, 208), "skin_shade" to Color.rgb(246, 192, 174),
        "jaw" to Color.rgb(236, 168, 156), "eye" to Color.rgb(40, 32, 72),
        "eye_light" to Color.rgb(255, 255, 255), "blush" to Color.rgb(255, 160, 176),
        "mouth" to Color.rgb(180, 70, 90), "tongue" to Color.rgb(255, 128, 150),
        "collar" to Color.rgb(255, 255, 255), "collar_shade" to Color.rgb(205, 212, 236),
    )

    val VARIANTS = mapOf(
        "Blueberry" to mapOf(
            "hat" to Color.rgb(110, 160, 240), "hat_shade" to Color.rgb(78, 118, 206),
            "hat_light" to Color.rgb(170, 205, 255), "ear_inner" to Color.rgb(255, 170, 200),
            "outfit" to Color.rgb(110, 160, 240), "iris" to Color.rgb(90, 110, 220),
            "iris_light" to Color.rgb(140, 180, 255), "bow" to Color.rgb(255, 120, 160),
            "bow_shade" to Color.rgb(214, 76, 128),
        ),
        "Strawberry" to mapOf(
            "hat" to Color.rgb(255, 128, 160), "hat_shade" to Color.rgb(222, 86, 126),
            "hat_light" to Color.rgb(255, 196, 212), "ear_inner" to Color.rgb(255, 226, 234),
            "outfit" to Color.rgb(255, 128, 160), "iris" to Color.rgb(200, 70, 110),
            "iris_light" to Color.rgb(255, 150, 180), "bow" to Color.rgb(110, 200, 120),
            "bow_shade" to Color.rgb(64, 150, 90),
        ),
        "Mint" to mapOf(
            "hat" to Color.rgb(112, 214, 178), "hat_shade" to Color.rgb(62, 164, 136),
            "hat_light" to Color.rgb(192, 246, 222), "ear_inner" to Color.rgb(255, 176, 204),
            "outfit" to Color.rgb(112, 214, 178), "iris" to Color.rgb(40, 150, 130),
            "iris_light" to Color.rgb(120, 220, 190), "bow" to Color.rgb(255, 120, 160),
            "bow_shade" to Color.rgb(214, 76, 128),
        ),
        "Lavender" to mapOf(
            "hat" to Color.rgb(178, 148, 240), "hat_shade" to Color.rgb(134, 104, 206),
            "hat_light" to Color.rgb(222, 206, 255), "ear_inner" to Color.rgb(255, 180, 214),
            "outfit" to Color.rgb(178, 148, 240), "iris" to Color.rgb(120, 80, 200),
            "iris_light" to Color.rgb(190, 160, 255), "bow" to Color.rgb(255, 210, 100),
            "bow_shade" to Color.rgb(226, 164, 60),
        ),
        "Lemon" to mapOf(
            "hat" to Color.rgb(255, 216, 88), "hat_shade" to Color.rgb(228, 172, 48),
            "hat_light" to Color.rgb(255, 240, 170), "ear_inner" to Color.rgb(255, 170, 190),
            "outfit" to Color.rgb(255, 216, 88), "iris" to Color.rgb(200, 130, 40),
            "iris_light" to Color.rgb(250, 196, 90), "bow" to Color.rgb(110, 160, 240),
            "bow_shade" to Color.rgb(78, 118, 206),
        ),
    )

    private val BLINK = listOf("SSSSSS", "SSSSSS", "KSSSSS", "SKKKKS", "SSSSSS", "SSSSSS")
    private val HAPPY = listOf("SSSSSS", "SSSSSS", "SSKKSS", "SKSSKS", "KSSSSK", "SSSSSS")
    private val SLEEPY = listOf("SSSSSS", "SSSSSS", "SSSSSS", "KSSSSK", "SKKKKS", "SSSSSS")
    private val LOOK_UP = listOf("SKKKKS", "SKIIJK", "SKKWWK", "SKKWWK", "SKKKKK", "SSKKKS")

    private val cache = HashMap<Pair<Pose, String>, Bitmap>()

    /** Cached per pose and variant, so callers can compare frames by identity. Main thread only. */
    fun bitmap(pose: Pose, variant: String = "Blueberry"): Bitmap = cache.getOrPut(pose to variant) {
        val roles = BASE + VARIANTS.getValue(variant)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        grid(pose).forEachIndexed { y, row ->
            row.forEachIndexed { x, c -> if (c != '.') bmp.setPixel(x, y, roles.getValue(ROLES.getValue(c))) }
        }
        bmp
    }

    private fun grid(pose: Pose): Array<CharArray> {
        val g = Array(SIZE) { y -> (HALF[y] + HALF[y].reversed()).toCharArray() }
        // Both eye highlights sit top-left so the light direction stays consistent.
        paint(g, 22, 23, "KWWKK")
        paint(g, 23, 23, "KWWKK")
        paint(g, 25, 23, "KIIJK")
        when (pose) {
            Pose.IDLE -> Unit
            Pose.BLINK -> eyes(g, BLINK)
            Pose.TALKING -> {
                paint(g, 28, 18, "SMMS")
                paint(g, 29, 18, "MNNM")
                paint(g, 30, 18, "SMMS")
            }
            Pose.LISTENING -> {
                perkEars(g)
                paint(g, 28, 18, "SMMS")
                paint(g, 29, 18, "SMMS")
            }
            Pose.THINKING -> {
                eyes(g, LOOK_UP)
                paint(g, 28, 18, "SSSS")
                paint(g, 29, 18, "SMMM")
            }
            Pose.SLEEPY -> {
                eyes(g, SLEEPY)
                paint(g, 28, 18, "SSSS")
                paint(g, 29, 18, "SMMS")
            }
            Pose.HAPPY -> {
                eyes(g, HAPPY)
                paint(g, 28, 17, "MMMMMM")
                paint(g, 29, 17, "SMNNMS")
                paint(g, 30, 17, "SSMMSS")
            }
        }
        return g
    }

    private fun paint(g: Array<CharArray>, y: Int, x: Int, s: String) {
        s.forEachIndexed { i, c -> g[y][x + i] = c }
    }

    /** Paint a 6x6 eye block on the left eye and its mirror image on the right. */
    private fun eyes(g: Array<CharArray>, block: List<String>) {
        block.forEachIndexed { dy, row ->
            row.forEachIndexed { dx, c ->
                g[EYE_Y + dy][EYE_X + dx] = c
                g[EYE_Y + dy][SIZE - 1 - EYE_X - dx] = c
            }
        }
    }

    /** Stretch both ears one pixel taller. */
    private fun perkEars(g: Array<CharArray>) {
        for (x in (0..12) + (27 until SIZE)) for (y in 0 until 8) g[y][x] = g[y + 1][x]
    }
}
