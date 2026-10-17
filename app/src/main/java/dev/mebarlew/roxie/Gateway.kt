package dev.mebarlew.roxie

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GatewayError(val code: Int, body: String) : Exception("HTTP $code: ${body.take(300)}")

/** Bart's AI gateway. Blocking calls: run them off the main thread. */
object Gateway {
    private const val CHAT_MODEL = "gemini-3.5-flash-lite"
    private const val TTS_MODEL = "gemini-3.8-flash-tts"

    fun chat(messages: JSONArray): String {
        val body = JSONObject().put("model", CHAT_MODEL).put("messages", messages)
        val res = post("/v1/chat/completions", body, "authorization" to "Bearer ${BuildConfig.GATEWAY_KEY}")
        return res.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
    }

    /** Returns a complete WAV file: 24 kHz, mono, 16-bit PCM. */
    fun speech(text: String, voice: String, style: String): ByteArray {
        val part = JSONObject().put("text", text).put("speech_metadata", JSONObject().put("style", style))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(part))))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("voice", voice))),
            )
        val res = post("/v1beta/models/$TTS_MODEL:generateContent", body, "x-goog-api-key" to BuildConfig.GATEWAY_KEY)
        val data = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
        return Base64.decode(data, Base64.DEFAULT)
    }

    private fun post(path: String, body: JSONObject, auth: Pair<String, String>): JSONObject {
        val conn = URL(BuildConfig.GATEWAY_URL + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty(auth.first, auth.second)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw GatewayError(code, text)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
