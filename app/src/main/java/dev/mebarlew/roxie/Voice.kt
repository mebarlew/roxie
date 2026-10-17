package dev.mebarlew.roxie

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * The buddy's voice: Gemini TTS through the gateway, cached on disk, with the phone's own TTS as the
 * fallback (Google's free tier allows only a few TTS requests a minute). [onMouth] drives lip-sync.
 */
class Voice(private val context: Context, private val prefs: Prefs, private val onMouth: (Boolean) -> Unit) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null

    /** null while the phone's TTS engine is still starting, then whether it started. */
    private var deviceVoiceOk: Boolean? = null
    private var whenDeviceVoiceStarts: (() -> Unit)? = null
    private var mouthTicker: Runnable? = null
    private var generation = 0

    /** True from [speak] until the speech ends or is stopped, including while the audio is still loading. */
    var busy = false
        private set

    /** Silent, vibrate or Do Not Disturb means the buddy makes no sound at all. */
    fun soundAllowed(): Boolean {
        val ringer = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode
        val filter = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter
        return ringer == AudioManager.RINGER_MODE_NORMAL &&
            (filter == NotificationManager.INTERRUPTION_FILTER_ALL || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
    }

    /** Speaks [text] and calls [onDone] when finished, or right away when sound isn't allowed. */
    fun speak(text: String, onDone: () -> Unit) {
        stop()
        if (!soundAllowed()) {
            onDone()
            return
        }
        busy = true
        val done = {
            busy = false
            onDone()
        }
        val request = generation
        io.execute {
            val file = runCatching { cachedSpeech(text, prefs.voice) }.getOrNull()
            main.post {
                if (request != generation) return@post
                if (file != null) play(file, text, done) else speakOnDevice(text, done)
            }
        }
    }

    /** Plays a sample of [voice], even in silent mode since you asked for it. [onResult] gets an error to show, or null. */
    fun preview(voice: String, onResult: (String?) -> Unit) {
        stop()
        val request = generation
        io.execute {
            val result = runCatching { cachedSpeech(PREVIEW_LINE, voice) }
            main.post {
                if (request != generation) return@post
                result.onSuccess { file ->
                    play(file, PREVIEW_LINE) {}
                    onResult(null)
                }.onFailure { e ->
                    onResult(if (e is GatewayError && e.code == 429) quotaMessage() else "Couldn't load $voice. Is the internet on?")
                }
            }
        }
    }

    /** Starts the phone's own voice early, so the first line that falls back to it doesn't wait for the engine. */
    fun warmUp() {
        deviceVoice()
    }

    private fun deviceVoice(): TextToSpeech = tts ?: TextToSpeech(context) { status ->
        deviceVoiceOk = status == TextToSpeech.SUCCESS
        val next = whenDeviceVoiceStarts
        whenDeviceVoiceStarts = null
        next?.invoke()
    }.also { tts = it }

    fun stop() {
        generation++
        busy = false
        whenDeviceVoiceStarts = null
        stopMouth()
        player?.release()
        player = null
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        io.shutdownNow()
    }

    private fun cachedSpeech(text: String, voice: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest("$voice|$STYLE|$text".toByteArray())
        val file = File(context.cacheDir, "tts-${digest.joinToString("") { "%02x".format(it) }}.wav")
        if (!file.exists()) {
            // Waiting on a 429 we already know about would only delay the fallback.
            if (System.currentTimeMillis() < cloudVoiceBackAt) throw GatewayError(429, "")
            val audio = try {
                Gateway.speech(text, voice, STYLE)
            } catch (e: GatewayError) {
                if (e.code == 429) cloudVoiceBackAt = System.currentTimeMillis() + (e.retryAfterMs ?: MINUTE_MS)
                throw e
            }
            val part = File(file.path + ".part")
            part.writeBytes(audio)
            part.renameTo(file)
        }
        return file
    }

    private fun quotaMessage(): String {
        val backAt = cloudVoiceBackAt
        return if (backAt - System.currentTimeMillis() < 2 * MINUTE_MS) {
            "The free voice allows only a few clips a minute. Try again in a minute."
        } else {
            "Google's free voice allows 10 clips a day and they're used up. " +
                "Back at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(backAt))}."
        }
    }

    private fun play(file: File, text: String, onDone: () -> Unit) {
        val mp = MediaPlayer()
        try {
            val envelope = mouthEnvelope(file.readBytes())
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { finish(onDone) }
            mp.setOnErrorListener { _, _, _ ->
                finish(onDone)
                true
            }
            mp.prepare()
            player = mp
            mp.start()
            startMouth {
                val i = mp.currentPosition / WINDOW_MS
                i < envelope.size && envelope[i]
            }
        } catch (e: Exception) {
            mp.release()
            file.delete()
            speakOnDevice(text, onDone)
        }
    }

    private fun finish(onDone: () -> Unit) {
        stopMouth()
        player?.release()
        player = null
        onDone()
    }

    private fun speakOnDevice(text: String, onDone: () -> Unit) {
        val engine = deviceVoice()
        when (deviceVoiceOk) {
            null -> {
                whenDeviceVoiceStarts = { speakOnDevice(text, onDone) }
                return
            }
            false -> {
                onDone()
                return
            }
            true -> Unit
        }
        var tick = 0
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { startMouth { (tick++ / 3) % 2 == 0 } }
            }

            override fun onDone(utteranceId: String?) {
                main.post {
                    stopMouth()
                    onDone()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post {
                    stopMouth()
                    onDone()
                }
            }
        })
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "roxie") == TextToSpeech.ERROR) onDone()
    }

    private fun startMouth(isOpen: () -> Boolean) {
        stopMouth()
        val ticker = object : Runnable {
            override fun run() {
                onMouth(isOpen())
                main.postDelayed(this, WINDOW_MS.toLong())
            }
        }
        mouthTicker = ticker
        main.post(ticker)
    }

    private fun stopMouth() {
        mouthTicker?.let { main.removeCallbacks(it) }
        mouthTicker = null
        onMouth(false)
    }

    /** One flag per [WINDOW_MS] of audio: is the voice loud enough for an open mouth? */
    private fun mouthEnvelope(wav: ByteArray): BooleanArray {
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var dataStart = 44
        var dataLen = wav.size - dataStart
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val len = buf.getInt(pos + 4)
            if (id == "data") {
                dataStart = pos + 8
                dataLen = if (len in 0..(wav.size - dataStart)) len else wav.size - dataStart
                break
            }
            if (len < 0) break
            pos += 8 + len
        }
        val window = SAMPLE_RATE * WINDOW_MS / 1000
        val count = (dataLen / 2 / window).coerceAtLeast(0)
        val rms = DoubleArray(count) { w ->
            var sum = 0.0
            for (i in 0 until window) {
                val s = buf.getShort(dataStart + (w * window + i) * 2).toDouble()
                sum += s * s
            }
            sqrt(sum / window)
        }
        val threshold = (rms.maxOrNull() ?: 0.0) * 0.25
        return BooleanArray(count) { rms[it] > threshold }
    }

    private companion object {
        const val STYLE = "friendly, warm and upbeat"
        const val PREVIEW_LINE = "Hey! This is how I sound. Tap me whenever you need a hand."
        const val SAMPLE_RATE = 24_000
        const val WINDOW_MS = 50
        const val MINUTE_MS = 60_000L

        /** Google's free voice is out of quota until this wall-clock time, so lines go straight to the phone's voice. */
        @Volatile
        var cloudVoiceBackAt = 0L
    }
}

const val DEFAULT_VOICE = "Leda"

/** Gemini's prebuilt voices with Google's one-word description of each. */
val VOICES = listOf(
    "Zephyr" to "Bright", "Puck" to "Upbeat", "Charon" to "Informative", "Kore" to "Firm",
    "Fenrir" to "Excitable", "Leda" to "Youthful", "Orus" to "Firm", "Aoede" to "Breezy",
    "Callirrhoe" to "Easy-going", "Autonoe" to "Bright", "Enceladus" to "Breathy", "Iapetus" to "Clear",
    "Umbriel" to "Easy-going", "Algieba" to "Smooth", "Despina" to "Smooth", "Erinome" to "Clear",
    "Algenib" to "Gravelly", "Rasalgethi" to "Informative", "Laomedeia" to "Upbeat", "Achernar" to "Soft",
    "Alnilam" to "Firm", "Schedar" to "Even", "Gacrux" to "Mature", "Pulcherrima" to "Forward",
    "Achird" to "Friendly", "Zubenelgenubi" to "Casual", "Vindemiatrix" to "Gentle", "Sadachbia" to "Lively",
    "Sadaltager" to "Knowledgeable", "Sulafat" to "Warm",
)
