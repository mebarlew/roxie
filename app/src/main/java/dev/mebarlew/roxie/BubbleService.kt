package dev.mebarlew.roxie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.random.Random

/** Keeps Roxie floating over other apps and runs the tap → listen → answer loop. */
class BubbleService : Service() {
    private enum class Mode { IDLE, GREETING, LISTENING, THINKING, ANSWERING }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var windows: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var brain: Brain
    private lateinit var voice: Voice
    private lateinit var roxie: RoxieView
    private lateinit var bubble: SpeechBubbleView
    private lateinit var roxieParams: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var recognizer: SpeechRecognizer? = null
    private var mode = Mode.IDLE
    private var bubbleShown = false
    private var mouthOpen = false
    private var typing = false
    private var typingTick = 0
    private var happyUntil = 0L
    private var blinkUntil = 0L
    private var nextBlink = 0L
    private var lastAnswer = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        brain = Brain(prefs)
        voice = Voice(this) { mouthOpen = it }
        windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startInForeground()
        addOverlays()
        main.post(animator)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        // Mic access only works when started from the visible app, so never auto-restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        bubble.stopTyping()
        recognizer?.destroy()
        voice.shutdown()
        io.shutdownNow()
        windows.removeView(bubble)
        windows.removeView(roxie)
        super.onDestroy()
    }

    private fun startInForeground() {
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Rubber Duck Assistant", NotificationManager.IMPORTANCE_MIN),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Roxie is hanging out")
            .setContentText("Tap her bubble to talk")
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification), "Put her to sleep", stop,
                ).build(),
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun addOverlays() {
        val screen = resources.displayMetrics
        roxie = RoxieView(this, onTap = ::onRoxieTap, onDrag = ::onRoxieDrag)
        val size = roxie.size
        roxieParams = overlayParams(size, size).apply {
            x = if (prefs.bubbleX >= 0) prefs.bubbleX else screen.widthPixels - size - size / 4
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else screen.heightPixels / 3
        }
        bubble = SpeechBubbleView(this, onSpeaker = ::speakAnswer, onClose = ::closeBubble)
        bubble.visibility = View.GONE
        bubbleParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        windows.addView(bubble, bubbleParams)
        windows.addView(roxie, roxieParams)
    }

    private fun onRoxieDrag(dx: Int, dy: Int, done: Boolean) {
        val screen = resources.displayMetrics
        roxieParams.x = (roxieParams.x + dx).coerceIn(0, screen.widthPixels - roxieParams.width)
        roxieParams.y = (roxieParams.y + dy).coerceIn(0, screen.heightPixels - roxieParams.height)
        windows.updateViewLayout(roxie, roxieParams)
        if (bubbleShown) positionBubble()
        if (done) {
            prefs.bubbleX = roxieParams.x
            prefs.bubbleY = roxieParams.y
        }
    }

    private fun onRoxieTap() {
        when (mode) {
            Mode.IDLE, Mode.ANSWERING -> greet()
            Mode.GREETING -> {
                voice.stop()
                startListening()
            }
            Mode.LISTENING -> {
                recognizer?.cancel()
                mode = Mode.IDLE
                showBubble("Okay, I'll be right here!")
            }
            Mode.THINKING -> Unit
        }
    }

    private fun greet() {
        voice.stop()
        mode = Mode.GREETING
        val line = "Hey ${prefs.name.ifBlank { "there" }}, what's up?"
        showBubble(line)
        voice.speak(line) { if (mode == Mode.GREETING) startListening() }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            mode = Mode.IDLE
            showBubble("I can't find a speech recognizer on this phone, so I can't hear you yet.")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(listener)
        mode = Mode.LISTENING
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val heard = firstResult(partialResults)
            if (mode == Mode.LISTENING && !heard.isNullOrBlank()) showBubble("🎤 $heard", animate = false)
        }

        override fun onResults(results: Bundle?) {
            if (mode != Mode.LISTENING) return
            val heard = firstResult(results)
            if (heard.isNullOrBlank()) {
                mode = Mode.IDLE
                showBubble("Didn't catch that. Tap me and try again!")
            } else {
                ask(heard)
            }
        }

        override fun onError(error: Int) {
            if (mode != Mode.LISTENING) return
            mode = Mode.IDLE
            showBubble(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Didn't catch that. Tap me and try again!"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "I need the microphone permission. Open Rubber Duck Assistant to fix it."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "My ears need internet right now."
                    else -> "My ears glitched (error $error). Tap me to try again!"
                },
            )
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun ask(question: String) {
        mode = Mode.THINKING
        showBubble("🎤 $question", animate = false)
        io.execute {
            val answer = try {
                brain.ask(question)
            } catch (e: GatewayError) {
                when (e.code) {
                    429 -> "I'm all out of brain juice for now. Give me a little break and try again!"
                    401, 403 -> "My key to the brain isn't working. The app needs a new gateway key."
                    else -> "My brain hiccuped (error ${e.code}). Try again?"
                }
            } catch (e: Exception) {
                "I can't reach my brain right now. Is the internet on?"
            }
            main.post { if (mode == Mode.THINKING) showAnswer(answer) }
        }
    }

    private fun showAnswer(answer: String) {
        mode = Mode.ANSWERING
        lastAnswer = answer
        showBubble(answer, speaker = if (voice.soundAllowed()) Speaker.ON else Speaker.MUTED)
    }

    private fun speakAnswer() {
        if (lastAnswer.isNotEmpty()) voice.speak(lastAnswer) {}
    }

    private fun closeBubble() {
        if (mode == Mode.LISTENING) recognizer?.cancel()
        voice.stop()
        bubble.stopTyping()
        typing = false
        bubble.visibility = View.GONE
        bubbleShown = false
        if (mode != Mode.THINKING) mode = Mode.IDLE
    }

    private fun showBubble(message: String, animate: Boolean = true, speaker: Speaker = Speaker.HIDDEN) {
        bubble.show(message, animate, speaker) { isTyping ->
            typing = isTyping
            if (!isTyping && animate) happyUntil = SystemClock.uptimeMillis() + 1500
        }
        bubble.visibility = View.VISIBLE
        bubbleShown = true
        positionBubble()
    }

    /** Puts the bubble beside Roxie on the roomier side, tail pointing at her mouth. */
    private fun positionBubble() {
        val screen = resources.displayMetrics
        val size = roxieParams.width
        val gap = (2 * screen.density).toInt()
        val onRight = roxieParams.x + size / 2 > screen.widthPixels / 2
        val room = if (onRight) roxieParams.x - gap else screen.widthPixels - roxieParams.x - size - gap
        val maxWidth = room.coerceAtLeast((180 * screen.density).toInt())
        bubble.tailOnLeft = !onRight
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = bubble.measuredWidth
        val h = bubble.measuredHeight
        bubbleParams.width = w
        bubbleParams.height = h
        bubbleParams.x = (if (onRight) roxieParams.x - w - gap else roxieParams.x + size + gap)
            .coerceIn(0, (screen.widthPixels - w).coerceAtLeast(0))
        bubbleParams.y = (roxieParams.y + (size * 0.62f).toInt() - bubble.tailY)
            .coerceIn(0, (screen.heightPixels - h).coerceAtLeast(0))
        windows.updateViewLayout(bubble, bubbleParams)
    }

    private val animator = object : Runnable {
        override fun run() {
            roxie.frame = Sprite.bitmap(currentPose())
            val busy = mouthOpen || typing || mode != Mode.IDLE
            main.postDelayed(this, if (busy) 50L else 100L)
        }
    }

    private fun currentPose(): Pose {
        val now = SystemClock.uptimeMillis()
        return when {
            mouthOpen -> Pose.TALKING
            typing -> if ((typingTick++ / 3) % 2 == 0) Pose.TALKING else Pose.IDLE
            mode == Mode.LISTENING -> Pose.LISTENING
            mode == Mode.THINKING -> Pose.THINKING
            now < happyUntil -> Pose.HAPPY
            mode == Mode.IDLE && !bubbleShown && isNight() -> Pose.SLEEPY
            else -> {
                if (now >= nextBlink) {
                    blinkUntil = now + 140
                    nextBlink = now + 2500 + Random.nextLong(3000)
                }
                if (now < blinkUntil) Pose.BLINK else Pose.IDLE
            }
        }
    }

    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 6
    }

    companion object {
        const val ACTION_STOP = "dev.mebarlew.roxie.STOP"
        private const val CHANNEL = "roxie"
        private const val NOTIFICATION_ID = 1
    }
}
