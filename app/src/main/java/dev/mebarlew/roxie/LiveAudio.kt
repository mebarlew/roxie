package dev.mebarlew.roxie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sound for a Gemini Live session: the mic at the 16 kHz Live expects, run through the phone's echo
 * canceller so the buddy doesn't hear itself, and a streaming speaker at the 24 kHz Live answers in.
 * [onMic] gets 40 ms chunks of 16-bit mono PCM on the recording thread; [onMicError] runs on the main
 * thread when the recorder dies, after it has been cleaned up. Mic calls and [release] are main thread only;
 * [play] and [flush] work from any thread.
 */
class LiveAudio(
    private val context: Context,
    private val onMic: (ByteArray) -> Unit,
    private val onMicError: () -> Unit,
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    private var effects = emptyList<AudioEffect>()
    private var micThread: Thread? = null

    @Volatile private var recording = false

    /** A queued answer chunk; a null [pcm] asks the writer to drop what the speaker still holds. */
    private class Chunk(val gen: Int, val pcm: ByteArray?)

    /** One [MOUTH_WINDOW_MS] stretch of queued audio, ending at [endFrame] in the track's frame count. */
    private class Window(val endFrame: Long, val open: Boolean)

    private val queue = LinkedBlockingQueue<Chunk>()
    private val generation = AtomicInteger()

    // The writer thread owns the track: it alone creates, writes, flushes and replaces it, so frame counts
    // can't race a flush. The lock covers what other threads read: the track's position and the counts.
    private val lock = Any()
    private var writer: Thread? = null
    private var released = false
    private var track: AudioTrack? = null
    private var framesWritten = 0L
    private var framesInWindows = 0L
    private val windows = ArrayDeque<Window>()
    private var leftover = ByteArray(0)
    private var peak = 0.0

    @Volatile private var routedToSpeaker = false

    /**
     * For phones whose echo canceller still lets the buddy hear itself: Live would take its own voice as
     * the user talking over it and cut itself off. When set, the mic isn't sent while the buddy talks.
     */
    @Volatile var muteMicWhilePlaying = false

    /** True while answer audio is queued or still coming out of the speaker. */
    val playing: Boolean
        get() {
            val gen = generation.get()
            if (queue.any { it.pcm != null && it.gen == gen }) return true
            return synchronized(lock) { track?.let { head(it) < framesWritten } ?: false }
        }

    /** Whether the mouth should be open for the audio coming out of the speaker right now. */
    fun mouthOpen(): Boolean = synchronized(lock) {
        val t = track ?: return false
        val head = head(t)
        while (windows.isNotEmpty() && windows.first().endFrame <= head) windows.removeFirst()
        head < framesWritten && windows.firstOrNull()?.open == true
    }

    /** Starts the mic. False if the permission is missing or the phone won't record. */
    fun startMic(): Boolean {
        if (record != null) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        // AOSP only guarantees 44.1 kHz; 48 kHz divides cleanly down to 16 kHz where 16 kHz itself isn't offered.
        val (r, rate) = listOf(MIC_RATE, MIC_RATE * 3).firstNotNullOfOrNull { candidate ->
            val min = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return@firstNotNullOfOrNull null
            val attempt = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, candidate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, candidate / 5 * 2),
            )
            if (attempt.state == AudioRecord.STATE_INITIALIZED) return@firstNotNullOfOrNull attempt to candidate
            attempt.release()
            null
        } ?: return false
        effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId) else null,
        ).onEach { it.setEnabled(true) }
        record = r
        recording = true
        r.startRecording()
        micThread = Thread({ readMic(r, rate) }, "live-mic").also { it.start() }
        return true
    }

    private fun readMic(r: AudioRecord, rate: Int) {
        val chunk = ByteArray(rate / 1000 * CHUNK_MS * 2)
        while (recording) {
            val n = r.read(chunk, 0, chunk.size)
            if (n < 0) {
                // ERROR_DEAD_OBJECT and friends: AOSP says the recorder has to be recreated.
                main.post {
                    if (record !== r) return@post
                    stopMic()
                    onMicError()
                }
                return
            }
            if (n == 0 || (muteMicWhilePlaying && playing)) continue
            onMic(if (rate == MIC_RATE) chunk.copyOf(n) else downTo16k(chunk, n))
        }
    }

    fun stopMic() {
        recording = false
        record?.stop()
        micThread?.join(JOIN_MS)
        micThread = null
        effects.forEach { it.release() }
        effects = emptyList()
        record?.release()
        record = null
    }

    /** Queues 24 kHz 16-bit mono PCM from Live. Only call it when the buddy may make sound. */
    fun play(pcm: ByteArray) {
        synchronized(lock) {
            // A chunk still in flight from the socket after release() would start a writer and a track nobody frees.
            if (released) return
            if (writer == null) writer = Thread(::writeSpeaker, "live-speaker").also { it.start() }
        }
        queue.put(Chunk(generation.get(), pcm))
    }

    /** Drops everything not yet heard, for when the user talks over the buddy. */
    fun flush() {
        val gen = generation.incrementAndGet()
        queue.clear()
        queue.put(Chunk(gen, null))
    }

    /** Call last: stops the mic and the speaker and gives the earpiece back. */
    fun release() {
        stopMic()
        generation.incrementAndGet()
        val w = synchronized(lock) {
            released = true
            writer.also { writer = null }
        }
        w?.interrupt()
        w?.join(JOIN_MS)
        queue.clear()
        synchronized(lock) {
            track?.release()
            track = null
            resetCounts()
        }
        if (routedToSpeaker && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        routedToSpeaker = false
    }

    private fun writeSpeaker() {
        try {
            while (true) {
                val chunk = queue.take()
                val pcm = chunk.pcm
                if (pcm == null) {
                    dropHeldAudio()
                    continue
                }
                if (chunk.gen != generation.get()) continue
                val t = synchronized(lock) { track ?: newTrack()?.also { track = it } } ?: continue
                synchronized(lock) { addWindows(pcm) }
                // Small slices, so a flush mid-chunk waits for at most one slice before it takes effect.
                var off = 0
                while (off < pcm.size && chunk.gen == generation.get()) {
                    val n = t.write(pcm, off, minOf(SLICE_BYTES, pcm.size - off))
                    if (n < 0) {
                        // ERROR_DEAD_OBJECT: AOSP says the track "needs to be recreated"; the next chunk gets a new one.
                        synchronized(lock) {
                            t.release()
                            track = null
                            resetCounts()
                        }
                        break
                    }
                    synchronized(lock) { framesWritten += n / 2 }
                    off += n
                }
            }
        } catch (e: InterruptedException) {
            // release() ends the writer this way while it waits for audio.
        }
    }

    private fun dropHeldAudio() = synchronized(lock) {
        track?.let {
            // flush() is a no-op on a playing track, and it zeroes the position the counts are measured against.
            it.pause()
            it.flush()
            it.play()
        }
        resetCounts()
    }

    private fun resetCounts() {
        framesWritten = 0
        framesInWindows = 0
        windows.clear()
        leftover = ByteArray(0)
    }

    /**
     * Mouth flags for [pcm], one per [MOUTH_WINDOW_MS]. A stream has no loudest moment known up front,
     * so "loud" is measured against a peak that fades, letting quieter sentences still open the mouth.
     */
    private fun addWindows(pcm: ByteArray) {
        val bytes = leftover + pcm
        val rms = windowRms(bytes, 0, bytes.size, MOUTH_WINDOW_FRAMES)
        for (r in rms) {
            peak = maxOf(r, peak * PEAK_FADE)
            framesInWindows += MOUTH_WINDOW_FRAMES
            windows.addLast(Window(framesInWindows, r > peak * 0.25))
        }
        leftover = bytes.copyOfRange(rms.size * MOUTH_WINDOW_FRAMES * 2, bytes.size)
    }

    private fun newTrack(): AudioTrack? {
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SPEAKER_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val t = try {
            builder.build()
        } catch (e: UnsupportedOperationException) {
            // AOSP: thrown "if the device was not available". Uncaught on this thread it would crash the app,
            // so the chunk is dropped and the next one tries again.
            return null
        }
        routeToSpeaker()
        t.play()
        return t
    }

    /** Call audio goes to the earpiece by default; a buddy on screen should be heard from the speaker. */
    private fun routeToSpeaker() {
        if (routedToSpeaker || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Headphones or a car stay as they are: only the earpiece is swapped for the speaker.
        if (audio.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) return
        val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        routedToSpeaker = speaker != null && audio.setCommunicationDevice(speaker)
    }

    private companion object {
        const val MIC_RATE = 16_000
        const val SPEAKER_RATE = 24_000
        const val CHUNK_MS = 40
        const val SLICE_BYTES = SPEAKER_RATE / 1000 * 20 * 2
        const val MOUTH_WINDOW_FRAMES = SPEAKER_RATE / 1000 * MOUTH_WINDOW_MS
        const val PEAK_FADE = 0.97
        const val JOIN_MS = 500L

        /** The playback position in frames; Android reports it as an unsigned 32-bit count. */
        fun head(t: AudioTrack) = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL

        /** 48 kHz → 16 kHz. Averaging each 3 samples is a cheap low-pass: speech up to 4 kHz keeps >90%, and the 12 kHz band that would fold back drops to a third. */
        fun downTo16k(pcm: ByteArray, length: Int): ByteArray {
            val out = ByteArray(length / 6 * 2)
            for (i in 0 until length / 6) {
                var sum = 0
                for (k in 0 until 3) {
                    val j = (i * 3 + k) * 2
                    sum += (pcm[j].toInt() and 0xFF) or (pcm[j + 1].toInt() shl 8)
                }
                val s = sum / 3
                out[i * 2] = s.toByte()
                out[i * 2 + 1] = (s shr 8).toByte()
            }
            return out
        }
    }
}
