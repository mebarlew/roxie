package dev.mebarlew.roxie

import android.os.SystemClock

/**
 * Hears where speech stops in the 16 kHz mic stream, about a second before Google's own end-of-turn arrives:
 * each chunk's loudness against a noise floor that drops at once and creeps up slowly. It's for the pose and the
 * latency numbers only and never ends a turn (L7: ending turns on the phone was slower). [feed] runs on the mic
 * thread; [onEdge] gets true when speech starts and false at a silence onset, with when that silence began.
 */
class SpeechEdge(private val onEdge: (speaking: Boolean, at: Long) -> Unit) {
    @Volatile private var resetPending = true
    private var floor = 0.0
    private var loudMs = 0
    private var quietMs = 0
    private var speaking = false

    /** Starts over, e.g. after the buddy talked: its own voice in the mic must not set the floor. */
    fun reset() {
        resetPending = true
    }

    fun feed(pcm: ByteArray) {
        if (pcm.size < 2) return
        val rms = windowRms(pcm, 0, pcm.size, pcm.size / 2).firstOrNull() ?: return
        val ms = pcm.size / 2 * 1000 / RATE
        if (resetPending) {
            resetPending = false
            floor = rms
            loudMs = 0
            quietMs = 0
            speaking = false
        }
        floor = if (rms < floor) rms else floor + (rms - floor) * FLOOR_RISE
        if (rms > maxOf(floor, MIN_FLOOR) * RATIO) {
            loudMs += ms
            quietMs = 0
            if (!speaking && loudMs >= SPEECH_MS) {
                speaking = true
                onEdge(true, SystemClock.elapsedRealtime())
            }
        } else {
            quietMs += ms
            loudMs = 0
            if (speaking && quietMs >= SILENCE_MS) {
                speaking = false
                onEdge(false, SystemClock.elapsedRealtime() - quietMs)
            }
        }
    }

    private companion object {
        const val RATE = 16_000
        const val SPEECH_MS = 120
        const val SILENCE_MS = 200
        const val RATIO = 3.0
        const val FLOOR_RISE = 0.02

        /** About −55 dBFS: digital silence would otherwise make any hiss count as speech. */
        const val MIN_FLOOR = 60.0
    }
}
