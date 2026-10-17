package dev.mebarlew.roxie

import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Chat with the buddy, remembering today's conversation and the facts in [memory]. */
class Brain(private val prefs: Prefs, private val memory: Memory) {
    private var diaryFailures = 0

    /** Blocking: call off the main thread. [screen] is a JPEG of the screen, sent with this question only. */
    fun ask(question: String, screen: ByteArray?): String {
        tidyMemory()
        val messages = JSONArray().put(message("system", systemPrompt()))
        memory.turns().forEach { messages.put(message(it.role, it.text)) }
        messages.put(if (screen == null) message("user", question) else withScreen(question, screen))
        val answer = Gateway.chat(messages)
        // Memory keeps text only: screenshots are big and only matter for the question they came with.
        memory.add(question, answer)
        return answer
    }

    /**
     * A diary entry when a new day starts, and the older half of today folded into the summary once today gets
     * long. Each is one extra call at most; if it fails, nothing is lost and the next question tries again.
     */
    private fun tidyMemory() {
        try {
            memory.startDay(LocalDate.now()) { day, summary, turns -> diaryEntry(day, summary, turns) }
            if (memory.isLong()) memory.compact { summary, oldest -> Gateway.chat(prompt(compactPrompt(summary, oldest))) }
        } catch (e: GatewayError) {
            // The question itself still goes through below.
        } catch (e: IOException) {
            // Same: offline now, retried with the next question.
        } catch (e: JSONException) {
            // A 200 with an odd shape (a filtered reply) is no reason to fail the question either.
        }
    }

    /**
     * Yesterday's diary entry, or null for none. A failure is retried with the next question, but one that
     * keeps failing (a 4xx other than 429, or 3 tries) would hold the day open forever and drag yesterday's
     * turns into every prompt, so then the day rolls over with the summary as the entry.
     */
    private fun diaryEntry(day: LocalDate, summary: String, turns: List<Memory.Turn>): String? = try {
        Gateway.chat(prompt(diaryPrompt(day, summary, turns))).also { diaryFailures = 0 }
    } catch (e: Exception) {
        val permanent = e is GatewayError && e.code in 400..499 && e.code != 429
        if (!permanent && ++diaryFailures < DIARY_TRIES) throw e
        diaryFailures = 0
        summary.ifBlank { null }
    }

    private fun compactPrompt(summary: String, oldest: List<Memory.Turn>): String {
        val name = name()
        val buddy = prefs.buddy.title
        return "You keep the memory of $buddy, a helper app on $name's phone. Merge the summary so far and the conversation " +
            "below into one updated summary of today. Keep every concrete detail that could matter later: names, dates, times, " +
            "places, numbers, plans, preferences, and anything $buddy promised to do. Drop greetings and small talk. " +
            "${dateLine(LocalDate.now())} Write it in the language $name used most. Plain text, at most 12 short lines, no markdown." +
            "\n\nSummary so far:\n${summary.ifBlank { "(none yet)" }}\n\nConversation:\n${transcript(oldest)}"
    }

    private fun diaryPrompt(day: LocalDate, summary: String, turns: List<Memory.Turn>): String {
        val name = name()
        val buddy = prefs.buddy.title
        return "Write $buddy's diary entry for ${DAY.format(day)} about the day with $name, in 2 to 4 sentences, first person " +
            "as $buddy, warm and a little playful. Mention the things that matter most for later. ${dateLine(day)} " +
            "Write it in the language $name used most. Plain text only." +
            "\n\nSummary of the day:\n${summary.ifBlank { "(none)" }}\n\nLast part of the conversation:\n${transcript(turns)}"
    }

    /** Relative dates go stale once the text is read on another day: the server check turned "this Thursday" into "next Thursday". */
    private fun dateLine(day: LocalDate) =
        "Today is ${DAY.format(day)}. Write every date as a calendar date with its weekday " +
            "(\"tomorrow\" becomes \"${DAY_NO_YEAR.format(day.plusDays(1))}\"), because this is read again on later days."

    private fun transcript(turns: List<Memory.Turn>): String {
        val name = name()
        return turns.joinToString("\n") { "${if (it.role == "user") name else prefs.buddy.title}: ${it.text}" }
    }

    private fun withScreen(question: String, jpeg: ByteArray): JSONObject {
        val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
        return JSONObject().put("role", "user").put("content", content)
    }

    /** Also the system instruction of a Live voice session, so Live gets the remembered facts and today's summary too. */
    fun systemPrompt(): String {
        val name = name()
        val buddy = prefs.buddy
        val now = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date())
        val base = """
            You are ${buddy.title}, ${buddy.vibe}. You float over $name's phone screen as a tiny pixel-art helper.
            Talk like a warm, playful best friend. Be genuinely helpful and honest.
            Keep replies short: one to three sentences of plain text, no markdown, no lists. They show up in a small comic speech bubble and may be read aloud.
            Always reply in the language the user speaks to you.
            When a screenshot comes with a question, it shows $name's phone screen right now, with you floating on top. Use it when the question is about what's on screen, ignore it otherwise, and never read out passwords, codes or other private details you spot.
            It is now $now.
        """.trimIndent()
        // Added after trimIndent: unindented lines spliced into the raw string would stop it trimming.
        val facts = memory.facts().takeIf { it.isNotEmpty() }
            ?.joinToString("\n", "Things $name asked you to remember:\n") { "- ${it.text}" }
        val summary = memory.summary().takeIf { it.isNotBlank() }?.let { "Earlier today:\n$it" }
        return listOfNotNull(base, facts, summary).joinToString("\n\n")
    }

    private fun name() = prefs.name.ifBlank { "your human" }

    private fun prompt(text: String) = JSONArray().put(message("user", text))

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private companion object {
        const val DIARY_TRIES = 3
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)
        val DAY_NO_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    }
}
