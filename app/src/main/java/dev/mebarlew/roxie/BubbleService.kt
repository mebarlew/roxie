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
import android.media.AudioManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.SocketTimeoutException
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

/** Keeps the buddy floating over other apps and runs the tap → listen → answer loop. */
class BubbleService : Service() {
    private enum class Mode { IDLE, GREETING, LISTENING, THINKING, ANSWERING }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var windows: WindowManager
    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs
    private lateinit var brain: Brain
    private lateinit var voice: Voice
    private lateinit var wake: WakeWord
    private lateinit var buddyView: BuddyView
    private lateinit var bubble: SpeechBubbleView
    private lateinit var buddyParams: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var recognizer: SpeechRecognizer? = null
    private var mode = Mode.IDLE
    private var bubbleShown = false
    private var mouthOpen = false
    private var lastMouthAt = 0L
    private var typing = false
    private var dragging = false
    private var waking = false
    private var canRetryListening = false
    private var happyUntil = 0L

    /** Wall-clock time the brain's quota is back; until then the buddy dozes and taps don't ask the gateway. */
    @Volatile private var tiredUntil = 0L
    private var lastAnswer = ""
    private var screen: ByteArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        brain = Brain(prefs)
        voice = Voice(this, prefs) { open ->
            mouthOpen = open
            if (open) lastMouthAt = SystemClock.uptimeMillis()
        }
        voice.warmUp()
        wake = WakeWord(this, ::onWakeWord)
        windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startInForeground()
        addOverlays()
        main.post(animator)
        awake = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        // Mic access only works when started from the visible app, so never auto-restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        awake = false
        main.removeCallbacksAndMessages(null)
        bubble.stopTyping()
        recognizer?.destroy()
        wake.shutdown()
        voice.shutdown()
        io.shutdownNow()
        windows.removeView(bubble)
        windows.removeView(buddyView)
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
            .setContentTitle("${prefs.buddy.title} is hanging out")
            .setContentText("Tap the bubble to talk")
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification), "Put to sleep", stop,
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
        buddyView = BuddyView(this, onTap = ::onBuddyTap, onDrag = ::onBuddyDrag)
        val size = buddyView.size
        buddyParams = overlayParams(size, size).apply {
            x = if (prefs.bubbleX >= 0) prefs.bubbleX else screen.widthPixels - size - size / 4
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else screen.heightPixels / 3
        }
        bubble = SpeechBubbleView(this, onSpeaker = ::speakAnswer, onClose = ::closeBubble)
        bubble.visibility = View.GONE
        bubbleParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        windows.addView(bubble, bubbleParams)
        windows.addView(buddyView, buddyParams)
    }

    private fun onBuddyDrag(dx: Int, dy: Int, done: Boolean) {
        val screen = resources.displayMetrics
        dragging = !done
        buddyParams.x = (buddyParams.x + dx).coerceIn(0, screen.widthPixels - buddyParams.width)
        buddyParams.y = (buddyParams.y + dy).coerceIn(0, screen.heightPixels - buddyParams.height)
        windows.updateViewLayout(buddyView, buddyParams)
        if (bubbleShown) positionBubble()
        if (done) {
            prefs.bubbleX = buddyParams.x
            prefs.bubbleY = buddyParams.y
        }
    }

    private fun onBuddyTap() {
        if (mode == Mode.LISTENING) {
            recognizer?.cancel()
            mode = Mode.IDLE
            showBubble("Okay, I'll be right here!")
            return
        }
        // Tapping while it reads an answer shushes it and leaves the text up; the next tap is a new question.
        if (mode == Mode.ANSWERING && (voice.busy || typing)) {
            voice.stop()
            bubble.finishTyping()
            typing = false
            return
        }
        if (tired()) {
            showTired()
            return
        }
        // Mid-greeting or mid-thinking, a tap cuts it off and listens right away.
        val fresh = mode == Mode.IDLE
        Latency.tap()
        voice.stop()
        bubble.stopTyping()
        typing = false
        lookAtScreen { if (fresh) greet() else startListening() }
    }

    /** "Hey ducky": like a tap, but straight to listening so the rest of your sentence isn't talked over. */
    private fun onWakeWord() {
        if (tired()) {
            showTired()
            return
        }
        waking = true
        Latency.tap()
        voice.stop()
        bubble.stopTyping()
        typing = false
        lookAtScreen { startListening() }
    }

    /** Grabs the screen without the speech bubble in the way, when screen reading is switched on. */
    private fun lookAtScreen(then: () -> Unit) {
        screen = null
        val reader = ScreenReader.instance
        if (reader == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            then()
            return
        }
        bubble.visibility = View.INVISIBLE
        main.postDelayed({
            reader.capture { jpeg ->
                main.post {
                    screen = jpeg
                    then()
                }
            }
        }, BUBBLE_HIDE_MS)
    }

    private fun greet() {
        voice.stop()
        mode = Mode.GREETING
        val line = "Hey ${prefs.name.ifBlank { "there" }}, what's up?"
        showBubble(line)
        voice.speak(line) { if (mode == Mode.GREETING) startListening() }
    }

    private fun startListening(retry: Boolean = true) {
        waking = false
        // Only one recognizer can have the mic.
        wake.listening = false
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            mode = Mode.IDLE
            showBubble("I can't find a speech recognizer on this phone, so I can't hear you yet.")
            return
        }
        // A fresh recognizer per session: a reused one can fail with ERROR_CLIENT after a cancel.
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(listener)
        canRetryListening = retry
        mode = Mode.LISTENING
        // "Listening" only once the recognizer really is: words said before that are lost.
        showBubble("🎤 …", animate = false)
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (mode != Mode.LISTENING) return
            Latency.micReady()
            showBubble("🎤 I'm listening…", animate = false)
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Latency.speechEnded()
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
            val flaky = error == SpeechRecognizer.ERROR_CLIENT ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
            if (flaky && canRetryListening) {
                // Google's recognizer service sometimes isn't ready right after another one let go of the mic.
                canRetryListening = false
                main.postDelayed({ if (mode == Mode.LISTENING) startListening(retry = false) }, RETRY_LISTEN_MS)
                return
            }
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
        val shot = screen
        screen = null
        showBubble("${if (shot != null) "👀" else "🎤"} $question", animate = false)
        io.execute {
            val answer = try {
                brain.ask(question, shot)
            } catch (e: GatewayError) {
                when (e.code) {
                    429 -> {
                        tiredUntil = System.currentTimeMillis() + (e.retryAfterMs ?: if (e.dailyCapHit) untilUtcMidnight() else TIRED_DEFAULT_MS)
                        tiredLine()
                    }
                    401, 403 -> "My key to the brain isn't working. The app needs a new gateway key."
                    // The gateway already retried Google's 5xx three times, so Google is struggling.
                    in 500..599 -> "My brain is swamped. Try again in a minute?"
                    else -> "My brain hiccuped (error ${e.code}). Try again?"
                }
            } catch (e: SocketTimeoutException) {
                "My brain is taking too long. Try again in a minute?"
            } catch (e: Exception) {
                "I can't reach my brain right now. Is the internet on?"
            }
            main.post { if (mode == Mode.THINKING) showAnswer(answer) }
        }
    }

    private fun tired() = System.currentTimeMillis() < tiredUntil

    private fun tiredLine() =
        "I'm worn out. Back at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(tiredUntil))}!"

    private fun showTired() {
        voice.stop()
        bubble.stopTyping()
        typing = false
        mode = Mode.IDLE
        showBubble(tiredLine())
    }

    /** The gateway's cap resets at 00:00 UTC, and Unix time counts every day as exactly 86,400 s. */
    private fun untilUtcMidnight(): Long {
        val now = System.currentTimeMillis()
        return (now / DAY_MS + 1) * DAY_MS - now
    }

    private fun showAnswer(answer: String) {
        mode = Mode.ANSWERING
        lastAnswer = answer
        Latency.replyStarted()
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
            if (!isTyping && mode == Mode.ANSWERING) Latency.replyDone()
        }
        bubble.visibility = View.VISIBLE
        bubbleShown = true
        positionBubble()
    }

    /** Puts the bubble beside the buddy on the roomier side, tail pointing at its mouth. */
    private fun positionBubble() {
        val screen = resources.displayMetrics
        val size = buddyParams.width
        val gap = (2 * screen.density).toInt()
        val onRight = buddyParams.x + size / 2 > screen.widthPixels / 2
        val room = if (onRight) buddyParams.x - gap else screen.widthPixels - buddyParams.x - size - gap
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
        bubbleParams.x = (if (onRight) buddyParams.x - w - gap else buddyParams.x + size + gap)
            .coerceIn(0, (screen.widthPixels - w).coerceAtLeast(0))
        val mouthY = buddyParams.y + (size * 0.62f).toInt()
        bubbleParams.y = (mouthY - bubble.topTailY).coerceIn(0, (screen.heightPixels - h).coerceAtLeast(0))
        // Pushed up off a buddy low on the screen, the bubble moves its tail down so it still points at the mouth.
        bubble.tailY = mouthY - bubbleParams.y
        windows.updateViewLayout(bubble, bubbleParams)
    }

    /** Picks the animation from the current state; the view plays it. Settings apply live. */
    private val animator = object : Runnable {
        override fun run() {
            buddyView.buddy = prefs.buddy
            buddyView.ringColor = CANDY.getValue(prefs.color)
            buddyView.lipSync = if (speaking()) mouthOpen else null
            buddyView.anim = currentAnim()
            // Paused while anything plays media: speech in a video was enough to wake it.
            wake.listening = prefs.wakeWord && !waking && !dragging && !typing && !voice.busy &&
                !audio.isMusicActive && (mode == Mode.IDLE || mode == Mode.ANSWERING)
            main.postDelayed(this, 50L)
        }
    }

    private fun currentAnim(): Anim = when {
        dragging -> Anim.DRAGGED
        speaking() || typing -> Anim.TALKING
        mode == Mode.LISTENING -> Anim.LISTENING
        mode == Mode.THINKING -> Anim.THINKING
        tired() -> Anim.SLEEPY
        SystemClock.uptimeMillis() < happyUntil -> Anim.HAPPY
        mode == Mode.IDLE && !bubbleShown && isNight() -> Anim.SLEEPY
        else -> Anim.IDLE
    }

    /** Mid-sentence pauses close the mouth without leaving the talking animation. */
    private fun speaking() = SystemClock.uptimeMillis() - lastMouthAt < SPEECH_GAP_MS

    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 6
    }

    companion object {
        const val ACTION_STOP = "dev.mebarlew.roxie.STOP"

        /** Compose state, so the setup screen flips the moment the buddy wakes or is put to sleep. */
        var awake by mutableStateOf(false)
            private set
        private const val CHANNEL = "roxie"
        private const val NOTIFICATION_ID = 1
        private const val SPEECH_GAP_MS = 400L

        /** Long enough for the hidden speech bubble to leave the screen before the screenshot. */
        private const val BUBBLE_HIDE_MS = 80L
        private const val RETRY_LISTEN_MS = 400L
        private const val TIRED_DEFAULT_MS = 5 * 60_000L
        private const val DAY_MS = 86_400_000L
    }
}
