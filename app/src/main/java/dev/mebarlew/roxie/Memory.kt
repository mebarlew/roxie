package dev.mebarlew.roxie

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate

/**
 * What the buddy remembers, kept on the phone only: today's conversation (the older part folded into a summary),
 * facts to keep, and a diary entry for each past day. It lives in noBackupFilesDir because Auto Backup copies
 * filesDir to Google Drive. One instance per process, so the service and the setup screen share every change.
 * Today's part is always today's: the first read of a new day puts the last day aside for its diary entry.
 */
class Memory private constructor(private val dir: File) {
    class Turn(val role: String, val text: String, val at: Long)
    class Fact(val text: String, val at: Long)
    class DiaryEntry(val day: String, val text: String)
    private class PastDay(val day: String, val summary: String, val turns: List<Turn>)

    private val file = AtomicFile(File(dir, "memory.json"))
    private val badFile = File(dir, "memory.json.bad")
    private var day = LocalDate.now().toString()
    private var summary = ""
    private val turns = mutableListOf<Turn>()
    private val facts = mutableListOf<Fact>()
    private val diary = mutableListOf<DiaryEntry>()

    /** Days that ended without a diary entry yet, oldest first. */
    private val pending = mutableListOf<PastDay>()

    init {
        load()
    }

    @Synchronized fun turns(): List<Turn> {
        rollOver()
        return turns.toList()
    }

    @Synchronized fun facts(): List<Fact> = facts.toList()

    @Synchronized fun summary(): String {
        rollOver()
        return summary
    }

    @Synchronized fun diary(): List<DiaryEntry> = diary.toList()

    @Synchronized fun addFact(text: String) {
        // The time doubles as the fact's id on the memory screen, so it must never repeat.
        val at = maxOf(System.currentTimeMillis(), (facts.maxOfOrNull { it.at } ?: 0) + 1)
        facts += Fact(text, at)
        save()
    }

    @Synchronized fun editFact(at: Long, text: String) {
        val i = facts.indexOfFirst { it.at == at }
        if (i < 0) return
        facts[i] = Fact(text, at)
        save()
    }

    @Synchronized fun removeFact(at: Long) {
        if (facts.removeAll { it.at == at }) save()
    }

    /** Forgets everything and removes its files, so nothing of it is left on the phone. */
    @Synchronized fun wipe() {
        day = LocalDate.now().toString()
        summary = ""
        turns.clear()
        facts.clear()
        diary.clear()
        pending.clear()
        file.delete()
        badFile.delete()
    }

    @Synchronized fun add(question: String, answer: String) {
        rollOver()
        val now = System.currentTimeMillis()
        turns += Turn("user", question, now)
        turns += Turn("assistant", answer, now)
        save()
    }

    /** True once today's conversation is long enough to fold its older half into the summary. */
    @Synchronized fun isLong(): Boolean {
        rollOver()
        return turns.sumOf { it.text.length } > COMPACT_AT_CHARS
    }

    /**
     * Folds the oldest half of today's turns into the summary. [summarize] gets the summary so far and those
     * turns and returns the new summary; it runs outside the lock because it's a network call.
     */
    fun compact(summarize: (String, List<Turn>) -> String) {
        val (summarySoFar, oldest) = synchronized(this) {
            rollOver()
            summary to turns.take(turns.size / 4 * 2)
        }
        if (oldest.isEmpty()) return
        val merged = summarize(summarySoFar, oldest)
        synchronized(this) {
            if (turns.size < oldest.size || turns.subList(0, oldest.size) != oldest) return
            summary = merged
            turns.subList(0, oldest.size).clear()
            save()
        }
    }

    /**
     * Writes the diary entry of the oldest day that ended without one, with [write] (day, summary, turns → entry,
     * or null for none). It's a network call, so it runs outside the lock; if it throws, the day waits for next time.
     */
    fun writeDiary(write: (LocalDate, String, List<Turn>) -> String?) {
        val last = synchronized(this) {
            rollOver()
            pending.firstOrNull()
        } ?: return
        val entry = write(LocalDate.parse(last.day), last.summary, last.turns)
        synchronized(this) {
            // Gone if a wipe came in meanwhile.
            if (pending.firstOrNull() !== last) return
            pending.removeAt(0)
            entry?.let { diary += DiaryEntry(last.day, it) }
            save()
        }
    }

    /**
     * Runs under the lock before today is read or added to. On the phone only, so the first question of a day
     * doesn't wait for anything: once the day has changed, the last one goes into [pending] and today starts empty.
     */
    private fun rollOver() {
        val today = LocalDate.now()
        if (!LocalDate.parse(day).isBefore(today)) return
        if (summary.isNotBlank() || turns.isNotEmpty()) pending += PastDay(day, summary, turns.toList())
        day = today.toString()
        summary = ""
        turns.clear()
        save()
    }

    private fun load() {
        val json = try {
            JSONObject(String(file.readFully()))
        } catch (e: FileNotFoundException) {
            return
        } catch (e: JSONException) {
            setAside()
            return
        }
        try {
            day = json.optString("day", day)
            summary = json.optString("summary")
            json.optJSONArray("turns")?.forEach { turns += turn(it) }
            json.optJSONArray("facts")?.forEach { facts += Fact(it.getString("text"), it.getLong("at")) }
            json.optJSONArray("diary")?.forEach { diary += DiaryEntry(it.getString("day"), it.getString("text")) }
            json.optJSONArray("pending")?.forEach { past ->
                val pastTurns = mutableListOf<Turn>()
                past.getJSONArray("turns").forEach { pastTurns += turn(it) }
                pending += PastDay(past.getString("day"), past.getString("summary"), pastTurns)
            }
        } catch (e: JSONException) {
            setAside()
        }
    }

    /**
     * A file that doesn't parse would crash the service on every start, so it's kept as memory.json.bad for
     * a look later, and memory starts empty.
     */
    private fun setAside() {
        file.baseFile.renameTo(badFile)
        day = LocalDate.now().toString()
        summary = ""
        turns.clear()
        facts.clear()
        diary.clear()
        pending.clear()
    }

    private fun save() {
        val pendingJson = pending.map { JSONObject().put("day", it.day).put("summary", it.summary).put("turns", turnsJson(it.turns)) }
        val json = JSONObject()
            .put("day", day)
            .put("summary", summary)
            .put("turns", turnsJson(turns))
            .put("facts", JSONArray(facts.map { JSONObject().put("text", it.text).put("at", it.at) }))
            .put("diary", JSONArray(diary.map { JSONObject().put("day", it.day).put("text", it.text) }))
            .put("pending", JSONArray(pendingJson))
        val out = file.startWrite()
        try {
            out.write(json.toString().toByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    private fun turn(json: JSONObject) = Turn(json.getString("role"), json.getString("text"), json.getLong("at"))

    private fun turnsJson(turns: List<Turn>) =
        JSONArray(turns.map { JSONObject().put("role", it.role).put("text", it.text).put("at", it.at) })

    private inline fun JSONArray.forEach(action: (JSONObject) -> Unit) {
        for (i in 0 until length()) action(getJSONObject(i))
    }

    companion object {
        /** About 3k tokens: Google's rule of thumb is ~4 characters a token, and mixed Polish/English measured 3.6. */
        private const val COMPACT_AT_CHARS = 12_000

        @Volatile private var instance: Memory? = null

        fun get(context: Context): Memory = instance ?: synchronized(this) {
            instance ?: Memory(context.applicationContext.noBackupFilesDir).also { instance = it }
        }
    }
}
