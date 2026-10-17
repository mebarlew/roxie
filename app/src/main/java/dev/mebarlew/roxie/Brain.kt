package dev.mebarlew.roxie

import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Chat with the buddy. Keeps a short in-memory history; persistent memory comes later. */
class Brain(private val prefs: Prefs) {
    private val history = ArrayDeque<JSONObject>()

    /** Blocking: call off the main thread. */
    fun ask(question: String): String {
        history.addLast(message("user", question))
        val messages = JSONArray().put(message("system", systemPrompt()))
        history.forEach { messages.put(it) }
        val answer = try {
            Gateway.chat(messages)
        } catch (e: Exception) {
            history.removeLast()
            throw e
        }
        history.addLast(message("assistant", answer))
        while (history.size > MAX_HISTORY) history.removeFirst()
        return answer
    }

    private fun systemPrompt(): String {
        val name = prefs.name.ifBlank { "your human" }
        val buddy = prefs.buddy
        val now = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date())
        return """
            You are ${buddy.title}, ${buddy.vibe}. You float over $name's phone screen as a tiny pixel-art helper.
            Talk like a warm, playful best friend. Be genuinely helpful and honest.
            Keep replies short: one to three sentences of plain text, no markdown, no lists. They show up in a small comic speech bubble and may be read aloud.
            Always reply in the language the user speaks to you.
            It is now $now.
        """.trimIndent()
    }

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private companion object {
        const val MAX_HISTORY = 20
    }
}
