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
        val messages = JSONArray().put(message("system", systemPrompt()))
        memory.turns().forEach { messages.put(message(it.role, it.text)) }
        messages.put(if (screen == null) message("user", question) else withScreen(question, screen))
        val answer = Gateway.chat(messages)
        // Memory keeps text only: screenshots are big and only matter for the question they came with.
        memory.add(question, answer)
        return answer
    }

    /**
     * Blocking, and meant for after an answer is shown, so no question waits on it: the diary entry of a day that
     * has ended, and the older half of today folded into the summary once today gets long. Each is one call at
     * most; if it fails, nothing is lost and the next answer tries again.
     */
    fun tidyMemory() {
        try {
            memory.writeDiary { day, summary, turns -> diaryEntry(day, summary, turns) }
            if (memory.isLong()) memory.compact { summary, oldest -> Gateway.chat(prompt(compactPrompt(summary, oldest))) }
        } catch (e: GatewayError) {
            // Retried after the next answer.
        } catch (e: IOException) {
            // Same: offline now.
        } catch (e: JSONException) {
            // A 200 with an odd shape (a filtered reply): same again.
        }
    }

    /**
     * A past day's diary entry, or null for none. A failure is retried after the next answer, but one that keeps
     * failing (a 4xx other than 429, or 3 tries) would cost a wasted call after every answer for good, so then the
     * summary becomes the entry.
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

    /** The persona, the remembered facts and today's summary. Live builds on it in [livePrompt]. */
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
            You can't delete memories yourself. If asked to forget something, say it's done from the Memory screen in the app.
            It is now $now.
        """.trimIndent()
        // Added after trimIndent: unindented lines spliced into the raw string would stop it trimming.
        return listOf(base, remembered()).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /**
     * The system instruction of a Live voice session: [systemPrompt] plus the last few turns, so a new session picks up
     * where the last one stopped. Seeding them as history goes silent on gemini-3.8-live, and the B9c-2 spike measured
     * 8 turns at ~0.1 s more setup with answers as fast, where all of today made each answer ~0.2 s slower.
     */
    fun livePrompt(): String {
        val recent = memory.turns().takeLast(LIVE_RECENT_TURNS)
        if (recent.isEmpty()) return systemPrompt()
        return "${systemPrompt()}\n\nThe latest of today's conversation, most recent last:\n${transcript(recent)}"
    }

    /** The facts and today's summary as [systemPrompt] puts them, so a hot Live session can tell if its own are out of date. */
    fun remembered(): String {
        val name = name()
        val facts = memory.facts().takeIf { it.isNotEmpty() }
            ?.joinToString("\n", "Things $name asked you to remember:\n") { "- ${it.text}" }
        val summary = memory.summary().takeIf { it.isNotBlank() }?.let { "Earlier today:\n$it" }
        return listOfNotNull(facts, summary).joinToString("\n\n")
    }

    private fun name() = prefs.name.ifBlank { "your human" }

    private fun prompt(text: String) = JSONArray().put(message("user", text))

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private companion object {
        const val DIARY_TRIES = 3
        const val LIVE_RECENT_TURNS = 8
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)
        val DAY_NO_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    }
}
