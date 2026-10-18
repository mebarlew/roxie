package dev.mebarlew.roxie

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How fast the buddy answers, per turn: tap → mic ready, end of speech → first reply (the first audio on Live,
 * the answer text on the old chain), and reply → done. Later turns of an open Live session start at the end of
 * speech and have no tap time. Main thread only. [last] is Compose state, so the setup screen shows the newest
 * turn as soon as it's recorded.
 */
object Latency {
    class Turn(val tapToMic: Long?, val speechToReply: Long?, val replyToDone: Long?, val vadLag: Long? = null) {
        fun describe(label: String) =
            "$label · tap→mic ${secs(tapToMic)} · speech end→reply ${secs(speechToReply)} · reply→done ${secs(replyToDone)}" +
                (vadLag?.let { " · VAD lag ${secs(it)}" } ?: "")
    }

    private var tapAt = 0L
    private var micAt = 0L
    private var speechEndAt = 0L
    private var replyAt = 0L
    private var heardEndAt = 0L
    private var vadLag: Long? = null

    /** The last [KEPT] turns, oldest first. */
    val turns = ArrayDeque<Turn>()

    var last by mutableStateOf<Turn?>(null)
        private set

    /** Each number's median over [turns], so one slow network moment doesn't decide a tuning call. */
    var median by mutableStateOf<Turn?>(null)
        private set

    /** The setup screen's debug lines: the last turn, plus the median once there are a few. */
    fun summary(): String? {
        val turn = last ?: return null
        val typical = median?.takeIf { turns.size > 1 }?.describe("Median of ${turns.size}")
        return listOfNotNull(turn.describe("Last turn"), typical).joinToString("\n")
    }

    fun tap() {
        // A reply that was cut off before it finished still counts, without its done time.
        if (replyAt > 0) record(null)
        tapAt = now()
        micAt = 0
        speechEndAt = 0
        replyAt = 0
        heardEndAt = 0
    }

    fun micReady() {
        if (tapAt > 0 && micAt == 0L) micAt = now()
    }

    /**
     * Where the phone's own mic heard speech stop (0 clears it, when speech came back). Live's end-of-turn comes
     * later, so [speechEnded] counts from here when there is one, and keeps the difference as the VAD lag.
     */
    fun localSpeechEnd(at: Long) {
        heardEndAt = at
    }

    fun speechEnded() {
        // Talking over a reply starts the next turn, so the one it cut off is recorded first.
        if (replyAt > 0) record(null)
        val at = now()
        val heard = heardEndAt.takeIf { it in 1..at }
        speechEndAt = heard ?: at
        vadLag = heard?.let { at - it }
        heardEndAt = 0
    }

    fun replyStarted() {
        if ((tapAt > 0 || speechEndAt > 0) && replyAt == 0L) replyAt = now()
    }

    fun replyDone() {
        if (replyAt > 0) record(now())
    }

    private fun record(doneAt: Long?) {
        val turn = Turn(
            tapToMic = (micAt - tapAt).takeIf { micAt > 0 },
            speechToReply = (replyAt - speechEndAt).takeIf { speechEndAt > 0 },
            replyToDone = doneAt?.let { it - replyAt },
            vadLag = vadLag,
        )
        turns.addLast(turn)
        while (turns.size > KEPT) turns.removeFirst()
        last = turn
        median = Turn(medianOf { it.tapToMic }, medianOf { it.speechToReply }, medianOf { it.replyToDone }, medianOf { it.vadLag })
        tapAt = 0
        micAt = 0
        speechEndAt = 0
        replyAt = 0
        vadLag = null
    }

    private fun medianOf(number: (Turn) -> Long?): Long? = turns.mapNotNull(number).sorted().let { it.getOrNull(it.size / 2) }

    private fun now() = SystemClock.elapsedRealtime()

    private fun secs(ms: Long?) = ms?.let { "%.2f s".format(it / 1000.0) } ?: "–"

    private const val KEPT = 10
}
