package dev.mebarlew.roxie

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Chat with the buddy. Keeps a short in-memory history; persistent memory comes later. */
class Brain(private val prefs: Prefs) {
    private val history = ArrayDeque<JSONObject>()

    /** Blocking: call off the main thread. [screen] is a JPEG of the screen, sent with this question only. */
    fun ask(question: String, screen: ByteArray?): String {
        val messages = JSONArray().put(message("system", systemPrompt()))
        history.forEach { messages.put(it) }
        messages.put(if (screen == null) message("user", question) else withScreen(question, screen))
        val answer = Gateway.chat(messages)
        // History keeps text only: screenshots are big and only matter for the question they came with.
        history.addLast(message("user", question))
        history.addLast(message("assistant", answer))
        while (history.size > MAX_HISTORY) history.removeFirst()
        return answer
    }

    private fun withScreen(question: String, jpeg: ByteArray): JSONObject {
        val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
        return JSONObject().put("role", "user").put("content", content)
    }

    /** Also the system instruction of a Live voice session. */
    fun systemPrompt(): String {
        val name = prefs.name.ifBlank { "your human" }
        val buddy = prefs.buddy
        val now = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date())
        return """
            You are ${buddy.title}, ${buddy.vibe}. You float over $name's phone screen as a tiny pixel-art helper.
            Talk like a warm, playful best friend. Be genuinely helpful and honest.
            Keep replies short: one to three sentences of plain text, no markdown, no lists. They show up in a small comic speech bubble and may be read aloud.
            Always reply in the language the user speaks to you.
            When a screenshot comes with a question, it shows $name's phone screen right now, with you floating on top. Use it when the question is about what's on screen, ignore it otherwise, and never read out passwords, codes or other private details you spot.
            It is now $now.
        """.trimIndent()
    }

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private companion object {
        const val MAX_HISTORY = 20
    }
}
