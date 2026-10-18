package dev.mebarlew.roxie

import android.os.SystemClock
import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * One Gemini Live conversation through the gateway's /v1/live WebSocket: mic audio and screen frames go up,
 * the spoken answer comes back as 24 kHz PCM with transcripts of both sides. Google ends a connection after
 * about 10 minutes with a goAway; the session then reconnects with its resumption handle on its own.
 * [listener] is called on OkHttp's reader thread.
 */
class LiveSession(private val systemPrompt: String, private val voice: String, private val listener: Listener) {
    interface Listener {
        /** Setup is done: start sending audio. */
        fun onReady()

        /** 24 kHz 16-bit mono PCM of the answer. */
        fun onAudio(pcm: ByteArray)

        /** More of what the user said, as Live heard it. */
        fun onHeard(text: String)

        /** More of what the buddy is saying. */
        fun onSaid(text: String)

        /** Live decided the user stopped talking; the answer is on its way. */
        fun onUserDone()

        /** The user talked over the answer: drop whatever is still queued to play. */
        fun onInterrupted()

        fun onTurnComplete()

        /** The session ended without [close] being called. */
        fun onClosed(error: LiveError)
    }

    /** The connection audio goes to, from its setupComplete on. */
    @Volatile private var socket: WebSocket? = null

    /** A connection still setting up: the first one, or the replacement after a goAway. */
    @Volatile private var pending: WebSocket? = null
    @Volatile private var resumeHandle: String? = null
    @Volatile private var closed = false

    /** Off while the session idles between chats: there's nothing to keep, so a goAway just lets it end. */
    @Volatile var handOverOnGoAway = true

    /** Google announced the end of this connection and no replacement is coming. */
    @Volatile var expiring = false
        private set

    fun start() = connect()

    /** 16 kHz 16-bit mono PCM from the mic. */
    fun sendAudio(pcm: ByteArray) = sendMedia("audio", pcm, "audio/pcm;rate=16000")

    /** One JPEG of the screen. Live takes at most one frame a second. */
    fun sendFrame(jpeg: ByteArray) = sendMedia("video", jpeg, "image/jpeg")

    @Synchronized fun close() {
        closed = true
        pending?.close(1000, null)
        socket?.close(1000, null)
        pending = null
        socket = null
    }

    private fun sendMedia(kind: String, bytes: ByteArray, mime: String) {
        val ws = socket ?: return
        // Past 16 MiB queued OkHttp closes the socket; long before that, late audio is worse than a gap.
        if (ws.queueSize() > MAX_QUEUED_BYTES) return
        val data = Base64.encodeToString(bytes, Base64.NO_WRAP)
        ws.send(JSONObject().put("realtimeInput", JSONObject().put(kind, JSONObject().put("data", data).put("mimeType", mime))).toString())
    }

    // Synchronized so a failure on OkHttp's thread can't be handled before [pending] is set.
    @Synchronized private fun connect() {
        val request = Request.Builder()
            .url("${BuildConfig.GATEWAY_URL}/v1/live?model=$MODEL")
            // A header rather than ?key=, so the key stays out of URL logs.
            .header("x-goog-api-key", BuildConfig.GATEWAY_KEY)
            .build()
        // This upgrade takes the warm connection, if there is one, so the next warm-up may open another.
        warmedAt = 0
        pending = client.newWebSocket(request, Events())
    }

    private fun setup(): String {
        val speech = JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))
        val setup = JSONObject()
            .put("model", "models/$MODEL")
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", speech)
                    // No thinking before answering: on the server it kept the slowest turn at ~1.5 s instead of up to 5 s.
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)),
            )
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            // 300 ms of silence ends the turn, with low end-of-speech sensitivity so a pause between two sentences
            // doesn't: measured on the server, end of speech → first audio had a median of 1.12 s and never cut anyone off.
            .put(
                "realtimeInputConfig",
                JSONObject().put(
                    "automaticActivityDetection",
                    JSONObject().put("silenceDurationMs", 300).put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW"),
                ),
            )
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            // Without compression an audio session stops at 15 minutes, and with a screen frame in it at 2.
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", resumeHandle?.let { JSONObject().put("handle", it) } ?: JSONObject())
        return JSONObject().put("setup", setup).toString()
    }

    private inner class Events : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(setup())
        }

        // Live has sent every frame as binary so far; text frames are handled the same way in case that changes.
        override fun onMessage(webSocket: WebSocket, text: String) = onServerMessage(webSocket, JSONObject(text))

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onServerMessage(webSocket, JSONObject(bytes.utf8()))

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            ended(webSocket) { LiveError.fromClose(code, reason) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ended(webSocket) { response?.let { LiveError.Refused(it.code) } ?: LiveError.Network(t.message.orEmpty()) }
        }
    }

    /** Reports the session as over once no connection is left to carry it. */
    private fun ended(webSocket: WebSocket, error: () -> LiveError) {
        val over = synchronized(this) {
            when {
                closed -> false
                webSocket === pending -> {
                    pending = null
                    // A failed replacement leaves the old connection running until it ends too.
                    socket == null
                }
                webSocket === socket -> {
                    socket = null
                    // Google closing the old connection while its replacement connects is the normal goAway handover.
                    pending == null
                }
                else -> false
            }
        }
        if (over) listener.onClosed(error())
    }

    private fun onServerMessage(webSocket: WebSocket, msg: JSONObject) {
        if (msg.has("setupComplete")) {
            val first = synchronized(this) {
                if (webSocket !== pending) return
                val old = socket
                socket = webSocket
                pending = null
                old?.close(1000, null)
                old == null
            }
            // A replacement after a goAway carries on the same conversation; only the first one starts it.
            if (first) listener.onReady()
            return
        }
        if (webSocket !== socket) return
        msg.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val newHandle = update.optString("newHandle")
            if (update.optBoolean("resumable") && newHandle.isNotEmpty()) resumeHandle = newHandle
        }
        if (msg.optJSONObject("voiceActivity")?.optString("type") == "ACTIVITY_END") listener.onUserDone()
        // Google closes the connection soon; open the next one now and switch over at its setupComplete.
        if (msg.has("goAway") && !closed) {
            if (!handOverOnGoAway) expiring = true else if (pending == null) connect()
        }
        val content = msg.optJSONObject("serverContent") ?: return
        content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let(listener::onHeard)
        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let(listener::onSaid)
        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                listener.onAudio(Base64.decode(data, Base64.DEFAULT))
            }
        }
        if (content.optBoolean("interrupted")) listener.onInterrupted()
        if (content.optBoolean("turnComplete")) listener.onTurnComplete()
    }

    companion object {
        private const val MODEL = "gemini-3.8-live"
        private const val MAX_QUEUED_BYTES = 64 * 1024L

        /** OkHttp keeps an idle connection 5 minutes and Cloudflare 400 s, so a warm-up lasts about this long. */
        private const val WARM_MS = 4 * 60_000L

        /** One client for every session, so connections share OkHttp's pools. */
        private val client = OkHttpClient()

        /**
         * The WebSocket upgrade runs as HTTP/1.1 only, and OkHttp reuses a pooled connection only for a matching
         * protocol list, so warm-ups use that list too (on the same pool) or they'd open an h2 connection it can't use.
         */
        private val warmClient = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()

        @Volatile private var warmedAt = 0L

        /**
         * Opens a connection to the gateway ahead of the next session, so its WebSocket skips DNS, TCP and TLS.
         * `/health` is unauthenticated and doesn't count against the app's daily limit.
         */
        fun warmUp() {
            val now = SystemClock.elapsedRealtime()
            if (warmedAt != 0L && now - warmedAt < WARM_MS) return
            warmedAt = now
            warmClient.newCall(Request.Builder().url("${BuildConfig.GATEWAY_URL}/health").build()).enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        warmedAt = 0
                    }

                    // Closing the body hands the connection back to the pool.
                    override fun onResponse(call: Call, response: Response) = response.close()
                },
            )
        }
    }
}

/** Why a Live session ended, sorted by what the bubble should do about it. */
sealed class LiveError {
    /** The gateway turned the connection down: 401/403 means the key or model setup, 429 the app's daily cap. */
    class Refused(val code: Int) : LiveError()

    /** Google's free Live quota ran out; the chat chain still has its own. */
    class Quota(val reason: String) : LiveError()

    /** Any other close from Google, kept with its reason for the log. */
    class Server(val code: Int, val reason: String) : LiveError()

    class Network(val message: String) : LiveError()

    companion object {
        // Community reports: a Live quota hit closes with 1011 "Resource has been exhausted (e.g. check quota)".
        fun fromClose(code: Int, reason: String): LiveError =
            if (code == 1011 && (reason.contains("exhausted", true) || reason.contains("quota", true))) Quota(reason) else Server(code, reason)
    }
}
