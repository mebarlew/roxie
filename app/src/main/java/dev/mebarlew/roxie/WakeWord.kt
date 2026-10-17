package dev.mebarlew.roxie

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Listens for "hey ducky" with Vosk, entirely on the phone: the audio never leaves it.
 * The ~40 MB model is downloaded when you switch the wake word on. Main thread only.
 */
class WakeWord(private val context: Context, private val onWake: () -> Unit) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var loadingModel = false
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null

    /** Whether it should be listening right now. Cheap to set on every tick; it only acts on changes. */
    var listening = false
        set(value) {
            if (field == value) return
            field = value
            if (value) begin() else end()
        }

    fun shutdown() {
        listening = false
        model?.close()
        model = null
        io.shutdownNow()
    }

    private fun begin() {
        if (service != null || !isDownloaded(context)) return
        val loaded = model
        if (loaded == null) {
            if (loadingModel) return
            loadingModel = true
            io.execute {
                val m = runCatching { Model(modelDir(context).path) }.getOrNull()
                main.post {
                    loadingModel = false
                    model = m
                    if (m != null && listening) begin()
                }
            }
            return
        }
        runCatching {
            val r = Recognizer(loaded, SAMPLE_RATE, GRAMMAR)
            recognizer = r
            service = SpeechService(r, SAMPLE_RATE).also { it.startListening(listener) }
        }.onFailure { end() }
    }

    private fun end() {
        service?.stop()
        service?.shutdown()
        service = null
        recognizer?.close()
        recognizer = null
    }

    private fun heard(hypothesis: String?) {
        val text = hypothesis?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }.orEmpty()
        if (WAKE.containsMatchIn(text)) {
            // Frees the mic for the real speech recognizer before handing over.
            listening = false
            onWake()
        }
    }

    private val listener = object : RecognitionListener {
        // Partial guesses are far too eager (they fired on ordinary Polish chat); only final results count.
        override fun onPartialResult(hypothesis: String?) = Unit
        override fun onResult(hypothesis: String?) = heard(hypothesis)
        override fun onFinalResult(hypothesis: String?) = heard(hypothesis)
        override fun onError(exception: Exception?) = end()
        override fun onTimeout() = Unit
    }

    companion object {
        const val PHRASE = "hey ducky"
        private const val MODEL = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL.zip"
        private const val SAMPLE_RATE = 16_000f

        /**
         * The phrase, "[unk]", and everyday words for ordinary speech to land on. With only the phrase and
         * "[unk]" to choose from, normal talk (Polish especially) kept being forced onto "hey ducky". Replaying
         * Polish and English chatter through the model took false wakes from 7 to 0 while still catching 4/4.
         */
        private val GRAMMAR = JSONArray(
            listOf(PHRASE, "[unk]") + (
                "hey hi hello a the and i you he she it we they yes no okay ok so but well what where when how why " +
                    "this that duck ducks lucky buddy dad daddy doug dog dock docky ducked key hockey jockey honey " +
                    "funny money go get got do did done see saw say said tell take make made come came know think " +
                    "want need like look"
                ).split(" "),
        ).toString()

        /** "a ducky" is how the model often hears a quick "hey ducky", so any final "ducky" wakes it. */
        private val WAKE = Regex("\\bducky\\b")

        private fun root(context: Context) = File(context.filesDir, "vosk")
        private fun modelDir(context: Context) = File(root(context), MODEL)

        fun isDownloaded(context: Context) = File(root(context), "ready").exists()

        /** Downloads and unpacks the model. Blocking: call off the main thread. [onProgress] gets 0-100. */
        fun download(context: Context, onProgress: (Int) -> Unit) {
            val root = root(context)
            root.deleteRecursively()
            root.mkdirs()
            val zipFile = File(root, "model.zip")
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    zipFile.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != last) {
                                last = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val base = root.canonicalPath + File.separator
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val out = File(root, entry.name).canonicalFile
                    if (!out.path.startsWith(base)) throw IOException("Unexpected path in model zip: ${entry.name}")
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            zipFile.delete()
            File(root, "ready").writeText(MODEL)
        }
    }
}
