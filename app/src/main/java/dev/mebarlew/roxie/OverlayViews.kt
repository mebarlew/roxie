package dev.mebarlew.roxie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
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

/** The floating Roxie disc. Drag to move, tap to talk. */
class RoxieView(
    context: Context,
    private val onTap: () -> Unit,
    private val onDrag: (dx: Int, dy: Int, done: Boolean) -> Unit,
) : View(context) {
    /** Screen pixels per sprite pixel; whole numbers keep the pixel art crisp. */
    private val pixel = (60 * resources.displayMetrics.density / 48).roundToInt().coerceAtLeast(2)
    private val art = Paint().apply { isFilterBitmap = false }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(200, 190, 235)
        strokeWidth = pixel.toFloat()
    }
    private val clip = Path()
    private val face = Rect()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    var frame: Bitmap = Sprite.bitmap(Pose.IDLE)
        set(value) {
            if (field !== value) {
                field = value
                invalidate()
            }
        }

    val size: Int get() = 48 * pixel

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.reset()
        clip.addCircle(w / 2f, h / 2f, w / 2f - 2 * pixel, Path.Direction.CW)
        face.set(4 * pixel, 6 * pixel, 44 * pixel, 46 * pixel)
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        canvas.drawCircle(r, r, r - pixel, disc)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(frame, null, face, art)
        canvas.restore()
        canvas.drawCircle(r, r, r - pixel, ring)
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
}

enum class Speaker { HIDDEN, ON, MUTED }

/** Comic speech bubble with a tail pointing at Roxie. Text types itself out. */
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

    /** Distance from the bubble's top to the tip of its tail. */
    val tailY: Int get() = shape.tailY.toInt()

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

    /** Shows [message]; while it types out, [onTyping] is true so Roxie can move her mouth. */
    fun show(message: String, animate: Boolean, speakerState: Speaker, onTyping: (Boolean) -> Unit) {
        typer?.let { main.removeCallbacks(it) }
        typer = null
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
        body.text = partial(message, shown)
        onTyping(true)
        val step = object : Runnable {
            override fun run() {
                shown = (shown + 2).coerceAtMost(message.length)
                if (shown < message.length && Character.isLowSurrogate(message[shown])) shown++
                body.text = partial(message, shown)
                if (shown < message.length) {
                    main.postDelayed(this, 30)
                } else {
                    typer = null
                    onTyping(false)
                }
            }
        }
        typer = step
        main.postDelayed(step, 30)
    }

    fun stopTyping() {
        typer?.let { main.removeCallbacks(it) }
        typer = null
    }

    private fun partial(message: String, shown: Int) = SpannableString(message).apply {
        if (shown < message.length) {
            setSpan(ForegroundColorSpan(Color.TRANSPARENT), shown, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

/** White comic bubble with a thick ink outline and a tail on the left or right. */
class BubbleDrawable(dp: Float) : Drawable() {
    val tailWidth = 12 * dp
    val tailY = 30 * dp
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
        val tail = Path().apply {
            if (tailOnLeft) {
                moveTo(body.left + 1, tailY - tailWidth)
                lineTo(b.left + inset, tailY)
                lineTo(body.left + 1, tailY + tailWidth * 0.4f)
            } else {
                moveTo(body.right - 1, tailY - tailWidth)
                lineTo(b.right - inset, tailY)
                lineTo(body.right - 1, tailY + tailWidth * 0.4f)
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
