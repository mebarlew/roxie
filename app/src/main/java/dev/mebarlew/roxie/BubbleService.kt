package dev.mebarlew.roxie

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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

/** Keeps the buddy floating over other apps and runs the tap → listen → answer loop, live over Gemini Live. */
class BubbleService : Service() {
    private enum class Mode { IDLE, LISTENING, THINKING, ANSWERING }

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

    /** The touch-down already started this conversation, so its tap-up must not end it again. */
    private var pressStarted = false
    private var canRetryListening = false
    private var happyUntil = 0L

    /** Wall-clock time the brain's quota is back; until then the buddy dozes and taps don't ask the gateway. */
    @Volatile private var tiredUntil = 0L
    private var lastAnswer = ""
    private var screen: ByteArray? = null

    // The Live voice session, while one is open. OkHttp's and the mic's threads read these.
    @Volatile private var live: LiveSession? = null
    @Volatile private var liveAudio: LiveAudio? = null
    /** Bumped per session, so late events from a closed one are dropped. */
    @Volatile private var liveGen = 0
    @Volatile private var soundOn = true
    @Volatile private var lastLiveActivity = 0L

    /** Set on OkHttp's thread from the first audio chunk of a turn until its turnComplete or interrupted. */
    @Volatile private var turnStreaming = false

    /** After a tap shushed the buddy mid-answer: the server keeps streaming that turn, so drop it until it ends. */
    @Volatile private var shushed = false
    private var turnDone = false
    private var heardText = ""
    private var saidText = ""

    // Mic chunks recorded before setupComplete, so words said right after the tap aren't lost.
    private val earlyLock = Any()
    private val earlyMic = ArrayDeque<ByteArray>()
    private var liveReady = false

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

    /** After a rotation, keeps the buddy inside the new screen and the bubble beside it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keepBuddyOnScreen()
        windows.updateViewLayout(buddyView, buddyParams)
        if (bubbleShown) positionBubble()
    }

    /** [positionBubble] and dragging work from these numbers, so they must stay inside the current screen. */
    private fun keepBuddyOnScreen() {
        val screen = resources.displayMetrics
        buddyParams.x = buddyParams.x.coerceIn(0, (screen.widthPixels - buddyParams.width).coerceAtLeast(0))
        buddyParams.y = buddyParams.y.coerceIn(0, (screen.heightPixels - buddyParams.height).coerceAtLeast(0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        // Mic access only works when started from the visible app, so never auto-restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        awake = false
        endLive(null)
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
        buddyView = BuddyView(
            this, onPress = ::onBuddyPress, onPressCancel = ::onBuddyPressCancel, onTap = ::onBuddyTap, onDrag = ::onBuddyDrag,
        )
        val size = buddyView.size
        buddyParams = overlayParams(size, size).apply {
            x = if (prefs.bubbleX >= 0) prefs.bubbleX else screen.widthPixels - size - size / 4
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else screen.heightPixels / 3
        }
        // A spot saved in landscape can be off a portrait screen.
        keepBuddyOnScreen()
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

    /**
     * A touch that will start listening opens the session on the way down, so the whole press is spent
     * connecting. Touches that shush, end a session or hit tired mode wait for the tap as before.
     */
    private fun onBuddyPress() {
        pressStarted = live == null && mode == Mode.IDLE && !tired()
        if (pressStarted) startConversation()
    }

    /** The press became a drag: nothing was asked, so close the session and mic it opened. */
    private fun onBuddyPressCancel() {
        if (!pressStarted) return
        pressStarted = false
        endLive(null)
        Log.d(TAG, "press turned into a drag: live session and mic closed")
        // lookAtScreen hid the bubble for the screenshot that's no longer coming.
        if (bubbleShown) bubble.visibility = View.VISIBLE
    }

    private fun onBuddyTap() {
        if (pressStarted) {
            pressStarted = false
            return
        }
        if (live != null) {
            // While it talks a tap shushes it and it keeps listening; while it listens a tap ends the session.
            if (mode == Mode.ANSWERING) shush() else endLive("Okay, I'll be right here!")
            return
        }
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
        startConversation()
    }

    private fun startConversation() {
        Latency.tap()
        voice.stop()
        bubble.stopTyping()
        typing = false
        startLive()
    }

    /** "Hey ducky": like a tap, but straight to listening so the rest of your sentence isn't talked over. */
    private fun onWakeWord() {
        if (tired()) {
            showTired()
            return
        }
        startConversation()
    }

    /** Grabs the screen without the speech bubble in the way, when screen reading is switched on. */
    private fun lookAtScreen(then: (note: String) -> Unit) {
        screen = null
        val reader = ScreenReader.instance
        if (reader == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            then("")
            return
        }
        bubble.visibility = View.INVISIBLE
        main.postDelayed({
            reader.capture { jpeg, error ->
                main.post {
                    screen = jpeg
                    then(screenNote(error))
                }
            }
        }, BUBBLE_HIDE_MS)
    }

    /** Says why the buddy can't see the screen, so a screen question isn't answered blind without a word. */
    private fun screenNote(error: Int?): String = when (error) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "🙈 This app hides its screen, so I can't look.\n"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "Screen reading got switched off. Turn it back on in the app.\n"
        else -> ""
    }

    private fun startListening(retry: Boolean = true) {
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

    /**
     * Opens a Live session and starts the mic at once, while it connects and the screen is captured. No spoken
     * greeting: listening right away is what makes it feel quick.
     */
    private fun startLive() {
        // Only one recorder can have the mic.
        wake.listening = false
        // On Android 10+ a call always keeps the mic, so a session now would only ever hear silence.
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
            mode = Mode.IDLE
            showBubble("You're on a call. I'll be here after!")
            return
        }
        val gen = ++liveGen
        val audioOut = LiveAudio(this, ::onLiveMic) { if (gen == liveGen) endLive("My ears glitched. Tap me to try again!") }
        if (!audioOut.startMic()) {
            audioOut.release()
            mode = Mode.IDLE
            showBubble(
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    "I need the microphone permission. Open Rubber Duck Assistant to fix it."
                } else {
                    "Another app is using the mic right now. Try me again in a moment!"
                },
            )
            return
        }
        liveAudio = audioOut
        soundOn = voice.soundAllowed()
        heardText = ""
        saidText = ""
        turnDone = false
        turnStreaming = false
        shushed = false
        lastLiveActivity = SystemClock.uptimeMillis()
        mode = Mode.LISTENING
        live = LiveSession(brain.systemPrompt(), prefs.voice, LiveEvents(gen)).also { it.start() }
        lookAtScreen { note ->
            if (gen != liveGen) return@lookAtScreen
            if (mode == Mode.LISTENING && heardText.isEmpty()) showBubble("$note🎤 I'm listening…", animate = false)
            sendScreen()
        }
    }

    private fun onLiveMic(pcm: ByteArray) {
        synchronized(earlyLock) {
            if (!liveReady) {
                earlyMic.addLast(pcm)
                while (earlyMic.size > EARLY_MIC_CHUNKS) earlyMic.removeFirst()
                return
            }
        }
        live?.sendAudio(pcm)
    }

    /** The screenshot goes up as the session's first frame, once both it and the session are ready. */
    private fun sendScreen() {
        val shot = screen ?: return
        if (!liveReady) return
        screen = null
        live?.sendFrame(shot)
    }

    private fun onLiveReady() {
        sendScreen()
        synchronized(earlyLock) {
            earlyMic.forEach { live?.sendAudio(it) }
            earlyMic.clear()
            liveReady = true
        }
        Latency.micReady()
    }

    /** The answer has begun, by its first audio or (in silent mode) its first words. */
    private fun startAnswer() {
        if (mode == Mode.ANSWERING) return
        mode = Mode.ANSWERING
        Latency.replyStarted()
    }

    /** Ends the turn once its audio has played out, and goes back to listening with the session open. */
    private fun finishLiveTurn() {
        turnDone = false
        if (mode == Mode.ANSWERING) Latency.replyDone()
        voice.stop()
        lastAnswer = saidText
        heardText = ""
        saidText = ""
        mode = Mode.LISTENING
    }

    private fun shush() {
        liveAudio?.flush()
        voice.stop()
        if (turnStreaming) shushed = true
        mode = Mode.LISTENING
    }

    private fun endLive(message: String?) {
        liveGen++
        live?.close()
        live = null
        // After the socket, so a chunk still in flight can't start the speaker again.
        liveAudio?.release()
        liveAudio = null
        synchronized(earlyLock) {
            earlyMic.clear()
            liveReady = false
        }
        voice.stop()
        turnDone = false
        mode = Mode.IDLE
        if (message != null) showBubble(message)
    }

    private fun liveErrorLine(error: LiveError): String = when (error) {
        is LiveError.Refused -> when (error.code) {
            429 -> {
                tiredUntil = System.currentTimeMillis() + untilUtcMidnight()
                tiredLine()
            }
            401, 403 -> "My key to the brain isn't working. The app needs a new gateway key."
            else -> "My brain hiccuped (error ${error.code}). Try again?"
        }
        is LiveError.Quota -> {
            tiredUntil = System.currentTimeMillis() + LIVE_QUOTA_REST_MS
            tiredLine()
        }
        is LiveError.Server, is LiveError.Network -> "I can't reach my brain right now. Is the internet on?"
    }

    /** One per session. Audio goes straight to the speaker; everything else is handled on the main thread. */
    private inner class LiveEvents(private val gen: Int) : LiveSession.Listener {
        private fun ui(block: () -> Unit) {
            main.post { if (gen == liveGen) block() }
        }

        override fun onReady() = ui(::onLiveReady)

        override fun onAudio(pcm: ByteArray) {
            if (gen != liveGen || shushed) return
            lastLiveActivity = SystemClock.uptimeMillis()
            if (soundOn) liveAudio?.play(pcm)
            if (!turnStreaming) {
                turnStreaming = true
                ui {
                    startAnswer()
                    if (soundOn) voice.mouthFollows { liveAudio?.mouthOpen() == true }
                }
            }
        }

        override fun onHeard(text: String) = ui {
            lastLiveActivity = SystemClock.uptimeMillis()
            heardText += text
            if (mode == Mode.LISTENING || mode == Mode.THINKING) showBubble("🎤 ${heardText.trim()}", animate = false)
        }

        override fun onSaid(text: String) = ui {
            if (shushed) return@ui
            saidText += text
            startAnswer()
            showBubble(saidText.trim(), animate = false)
        }

        override fun onUserDone() = ui {
            lastLiveActivity = SystemClock.uptimeMillis()
            if (mode == Mode.LISTENING) {
                mode = Mode.THINKING
                Latency.speechEnded()
            }
        }

        override fun onInterrupted() {
            turnStreaming = false
            shushed = false
            ui {
                // The user talked over it: what's queued is stale, and the new question is already coming in.
                liveAudio?.flush()
                voice.stop()
                heardText = ""
                saidText = ""
                mode = Mode.LISTENING
            }
        }

        override fun onTurnComplete() {
            turnStreaming = false
            shushed = false
            ui { turnDone = true }
        }

        override fun onClosed(error: LiveError) = ui { endLive(liveErrorLine(error)) }
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
        if (live != null) endLive(null)
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
            if (live != null) {
                if (turnDone && liveAudio?.playing != true) finishLiveTurn()
                if (mode == Mode.LISTENING && SystemClock.uptimeMillis() - lastLiveActivity > LIVE_IDLE_MS) endLive(null)
            }
            // Paused while anything plays media: speech in a video was enough to wake it.
            wake.listening = prefs.wakeWord && live == null && !dragging && !typing && !voice.busy &&
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
        private const val TAG = "RubberDuck"

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

        /** A quiet Live session closes after this, and the wake word takes over again. */
        private const val LIVE_IDLE_MS = 30_000L

        /** 4 s of 40 ms mic chunks: enough to cover the connect, small enough to stay fresh. */
        private const val EARLY_MIC_CHUNKS = 100

        /** Community reports put a Live quota hit as a 1011 close; resting a while beats hammering it. */
        private const val LIVE_QUOTA_REST_MS = 10 * 60_000L
    }
}
