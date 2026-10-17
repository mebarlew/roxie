package dev.mebarlew.roxie

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

val INK = Color.rgb(52, 40, 92)

/** The floating buddy disc. Plays 100 ms frame animations; drag to move, tap to talk. */
class BuddyView(
    context: Context,
    private val onTap: () -> Unit,
    private val onDrag: (dx: Int, dy: Int, done: Boolean) -> Unit,
) : View(context) {
    /** Screen pixels per sprite pixel; whole numbers keep the pixel art crisp. */
    private val pixel = (72 * resources.displayMetrics.density / DISC).roundToInt().coerceAtLeast(2)
    private val art = Paint().apply { isFilterBitmap = false }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * pixel
        color = Color.WHITE
    }
    private val fx = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val dest = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var step = 0
    private var steps = BuddyArt.anim(context, Anim.IDLE)

    var buddy = Buddy.RUBBER
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var ringColor = Color.WHITE
        set(value) {
            if (field != value) {
                field = value
                ring.color = value
                invalidate()
            }
        }

    var anim = Anim.IDLE
        set(value) {
            if (field != value) {
                field = value
                steps = BuddyArt.anim(context, value)
                step = 0
                invalidate()
            }
        }

    /** While the voice plays: true for an open mouth, false for closed. Null lets the animation drive it. */
    var lipSync: Boolean? = null

    private val ticker = object : Runnable {
        override fun run() {
            step = (step + 1) % steps.size
            invalidate()
            postDelayed(this, STEP_MS)
        }
    }

    val size: Int get() = DISC * pixel

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(ticker, STEP_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.reset()
        clip.addCircle(w / 2f, h / 2f, w / 2f - 2 * pixel, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val s = steps[step % steps.size]
        val key = when (lipSync) {
            true -> LIP_SYNC_OPEN[step % LIP_SYNC_OPEN.size]
            false -> IDLE_FRAME
            null -> s.frame
        }
        val r = width / 2f
        canvas.drawCircle(r, r, r - pixel, disc)
        canvas.save()
        canvas.clipPath(clip)
        // Squash and stretch keep the feet planted; the bob moves the whole sprite.
        val full = Buddy.SIZE * pixel
        val w = full * (1 + s.squash)
        val h = full * (1 - s.squash)
        val bottom = (MARGIN + Buddy.SIZE + s.dy) * pixel.toFloat()
        dest.set((width - w) / 2, bottom - h, (width + w) / 2, bottom)
        canvas.drawBitmap(BuddyArt.frame(context, buddy, key), null, dest, art)
        canvas.restore()
        canvas.drawCircle(r, r, r - pixel, ring)
        drawEffect(canvas, s.fx, s.dy)
    }

    /** Effects sit in the disc's margin, in sprite-pixel units (the disc is [DISC] units wide). */
    private fun drawEffect(canvas: Canvas, effect: String, dy: Int) {
        val p = pixel.toFloat()
        when {
            effect.startsWith("zzz") -> glyph(canvas, ZZZ, 60, 6 - effect.drop(3).toInt(), mapOf('K' to ZZZ_COLOR))
            effect.startsWith("sparkle") -> {
                val phase = effect.drop(7).toInt()
                SPARKLE_SPOTS.forEachIndexed { i, (x, y) ->
                    if ((i + phase) % 2 == 0) glyph(canvas, SPARKLE, x, y, mapOf('X' to if (i % 2 == 1) GOLD else PINK))
                }
            }
            effect.startsWith("dots") -> repeat(effect.drop(4).toInt()) { i ->
                val cx = (58 + i * 6) * p
                val cy = (14 - i * 3 + dy) * p
                fx.style = Paint.Style.FILL
                fx.color = THOUGHT
                canvas.drawCircle(cx, cy, 2.5f * p, fx)
                fx.style = Paint.Style.STROKE
                fx.strokeWidth = p * 0.7f
                fx.color = INK
                canvas.drawCircle(cx, cy, 2.5f * p, fx)
            }
            effect.startsWith("waves") -> {
                fx.style = Paint.Style.STROKE
                fx.strokeWidth = 1.5f * p
                fx.color = WAVE
                repeat(effect.drop(5).toInt() + 1) { i ->
                    val rr = (6 + i * 5) * p
                    val cx = 68 * p
                    val cy = 40 * p
                    canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -45f, 90f, false, fx)
                }
            }
            effect == "sweat" -> glyph(canvas, DROP, 62, 8 + dy, mapOf('X' to SWEAT, 'W' to Color.WHITE))
        }
    }

    private fun glyph(canvas: Canvas, rows: List<String>, x: Int, y: Int, colours: Map<Char, Int>) {
        val p = pixel.toFloat()
        fx.style = Paint.Style.FILL
        rows.forEachIndexed { gy, row ->
            row.forEachIndexed { gx, c ->
                colours[c]?.let {
                    fx.color = it
                    canvas.drawRect((x + gx) * p, (y + gy) * p, (x + gx + 1) * p, (y + gy + 1) * p, fx)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(event.rawX - downX, event.rawY - downY) > slop) dragging = true
                if (dragging) {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    lastX += dx
                    lastY += dy
                    onDrag(dx, dy, false)
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) onDrag(0, 0, true) else performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    private companion object {
        /** Disc width in sprite pixels: the 64 px sprite plus a margin for the ring and effects. */
        const val DISC = 80
        const val MARGIN = 8

        val ZZZ = listOf(
            "......KKKK", "........K.", ".......K..", "......KKKK", "KKKKK.....",
            "...K......", "..K.......", ".K........", "KKKKK.....",
        )
        val SPARKLE = listOf("..X..", "..X..", "XXXXX", "..X..", "..X..")
        val DROP = listOf("..X..", "..X..", ".XXX.", ".XWX.", "XXWXX", "XXXXX", ".XXX.")
        val SPARKLE_SPOTS = listOf(6 to 22, 66 to 10, 68 to 54, 6 to 56)
        val ZZZ_COLOR = Color.rgb(120, 110, 200)
        val GOLD = Color.rgb(255, 200, 80)
        val PINK = Color.rgb(255, 140, 190)
        val THOUGHT = Color.rgb(236, 230, 255)
        val WAVE = Color.rgb(150, 130, 220)
        val SWEAT = Color.rgb(120, 190, 255)
    }
}

enum class Speaker { HIDDEN, ON, MUTED }

/** Comic speech bubble with a tail pointing at the buddy. Text types itself out. */
class SpeechBubbleView(
    context: Context,
    onSpeaker: () -> Unit,
    onClose: () -> Unit,
) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val shape = BubbleDrawable(dp)
    private val body = TextView(context)
    private val speaker = TextView(context)
    private val main = Handler(Looper.getMainLooper())
    private var typer: Runnable? = null
    private var message = ""

    /** Distance from the bubble's top to the tip of its tail. */
    var tailY: Int
        get() = shape.tailY.toInt()
        set(value) {
            shape.tailY = value.toFloat()
        }

    /** The tail's highest spot, just under the rounded corner, where it sits by default. */
    val topTailY: Int get() = shape.topTailY.toInt()

    var tailOnLeft: Boolean
        get() = shape.tailOnLeft
        set(value) {
            shape.tailOnLeft = value
            val pad = (14 * dp).toInt()
            val tail = shape.tailWidth.toInt()
            setPadding(pad + if (value) tail else 0, pad, pad + if (value) 0 else tail, pad)
        }

    init {
        orientation = VERTICAL
        background = shape
        tailOnLeft = true
        body.setTextColor(INK)
        body.textSize = 16f
        body.setLineSpacing(0f, 1.15f)
        body.movementMethod = ScrollingMovementMethod()
        addView(body)
        val row = LinearLayout(context).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        speaker.text = "🔊"
        speaker.textSize = 20f
        speaker.setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), 0)
        speaker.setOnClickListener { onSpeaker() }
        val close = TextView(context).apply {
            text = "✕"
            textSize = 18f
            setTextColor(INK)
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), 0)
            setOnClickListener { onClose() }
        }
        row.addView(speaker)
        row.addView(close)
        addView(row)
    }

    /** Shows [message]; while it types out, [onTyping] is true so the buddy can move its mouth. */
    fun show(message: String, animate: Boolean, speakerState: Speaker, onTyping: (Boolean) -> Unit) {
        typer?.let { main.removeCallbacks(it) }
        typer = null
        this.message = message
        // A long answer scrolls inside the bubble, so the bubble and its close button stay on screen.
        body.maxHeight = (resources.displayMetrics.heightPixels * MAX_TEXT_SHARE).toInt()
        body.scrollTo(0, 0)
        speaker.visibility = if (speakerState == Speaker.HIDDEN) GONE else VISIBLE
        speaker.alpha = if (speakerState == Speaker.MUTED) 0.3f else 1f
        speaker.isEnabled = speakerState == Speaker.ON
        if (!animate) {
            body.text = message
            onTyping(false)
            return
        }
        // The untyped rest stays laid out (transparent), so the bubble never changes size while typing.
        var shown = 0
        // Long answers type faster, so no message takes more than about a second to appear.
        val perStep = maxOf(2, message.length / TYPING_STEPS + 1)
        body.text = partial(message, shown)
        onTyping(true)
        val step = object : Runnable {
            override fun run() {
                shown = (shown + perStep).coerceAtMost(message.length)
                if (shown < message.length && Character.isLowSurrogate(message[shown])) shown++
                body.text = partial(message, shown)
                if (shown < message.length) {
                    main.postDelayed(this, TYPING_STEP_MS)
                } else {
                    typer = null
                    onTyping(false)
                }
            }
        }
        typer = step
        main.postDelayed(step, TYPING_STEP_MS)
    }

    fun stopTyping() {
        typer?.let { main.removeCallbacks(it) }
        typer = null
    }

    /** Stops the typing effect and shows the whole message at once. */
    fun finishTyping() {
        stopTyping()
        body.text = message
    }

    private fun partial(message: String, shown: Int) = SpannableString(message).apply {
        if (shown < message.length) {
            setSpan(ForegroundColorSpan(Color.TRANSPARENT), shown, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private companion object {
        const val TYPING_STEP_MS = 30L
        const val TYPING_STEPS = 35
        const val MAX_TEXT_SHARE = 0.4f
    }
}

/** White comic bubble with a thick ink outline and a tail on the left or right. */
class BubbleDrawable(dp: Float) : Drawable() {
    val tailWidth = 12 * dp
    val topTailY = 30 * dp

    /** Where the tail's tip sits, from the top; [draw] keeps it on the straight edge between the corners. */
    var tailY = topTailY
        set(value) {
            field = value
            invalidateSelf()
        }
    var tailOnLeft = true
        set(value) {
            field = value
            invalidateSelf()
        }
    private val radius = 18 * dp
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = INK
        strokeWidth = 2.5f * dp
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = stroke.strokeWidth / 2
        val body = RectF(
            b.left + inset + if (tailOnLeft) tailWidth else 0f,
            b.top + inset,
            b.right - inset - if (tailOnLeft) 0f else tailWidth,
            b.bottom - inset,
        )
        val tip = tailY.coerceAtMost(b.height() - radius - tailWidth * 0.4f).coerceAtLeast(topTailY)
        val tail = Path().apply {
            if (tailOnLeft) {
                moveTo(body.left + 1, tip - tailWidth)
                lineTo(b.left + inset, tip)
                lineTo(body.left + 1, tip + tailWidth * 0.4f)
            } else {
                moveTo(body.right - 1, tip - tailWidth)
                lineTo(b.right - inset, tip)
                lineTo(body.right - 1, tip + tailWidth * 0.4f)
            }
            close()
        }
        path.reset()
        path.addRoundRect(body, radius, radius, Path.Direction.CW)
        path.op(tail, Path.Op.UNION)
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
