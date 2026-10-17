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
import java.util.concurrent.LinkedBlockingQueue

/**
 * Sound for a Gemini Live session: the mic at the 16 kHz Live expects, run through the phone's echo
 * canceller so the buddy doesn't hear itself, and a streaming speaker at the 24 kHz Live answers in.
 * [onMic] gets 40 ms chunks of 16-bit mono PCM on the recording thread.
 */
class LiveAudio(private val context: Context, private val onMic: (ByteArray) -> Unit) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var record: AudioRecord? = null
    private var effects = emptyList<AudioEffect>()
    private var micThread: Thread? = null
    private var track: AudioTrack? = null
    private var writer: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var routedToSpeaker = false

    @Volatile private var recording = false
    @Volatile private var generation = 0
    @Volatile private var framesWritten = 0L

    /**
     * For phones whose echo canceller still lets the buddy hear itself: Live would take its own voice as
     * the user talking over it and cut itself off. When set, the mic isn't sent while the buddy talks.
     */
    @Volatile var muteMicWhilePlaying = false

    /** True while answer audio is queued or still coming out of the speaker. */
    val playing: Boolean
        get() {
            val t = track ?: return false
            val played = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            return queue.isNotEmpty() || played < framesWritten
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
            if (n < 0) break
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
        if (track == null) startTrack()
        queue.put(pcm)
    }

    /** Drops everything not yet heard, for when the user talks over the buddy. */
    fun flush() {
        generation++
        queue.clear()
        track?.let {
            // flush() is a no-op on a playing track, so pause first.
            it.pause()
            it.flush()
            framesWritten = 0
            it.play()
        }
    }

    fun release() {
        stopMic()
        generation++
        writer?.interrupt()
        writer?.join(JOIN_MS)
        writer = null
        queue.clear()
        track?.release()
        track = null
        if (routedToSpeaker && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        routedToSpeaker = false
    }

    private fun startTrack() {
        val t = AudioTrack.Builder()
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
            .build()
        routeToSpeaker()
        t.play()
        track = t
        writer = Thread({ writeSpeaker(t) }, "live-speaker").also { it.start() }
    }

    /** Call audio goes to the earpiece by default; a buddy on screen should be heard from the speaker. */
    private fun routeToSpeaker() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Headphones or a car stay as they are: only the earpiece is swapped for the speaker.
        if (audio.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) return
        val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        routedToSpeaker = speaker != null && audio.setCommunicationDevice(speaker)
    }

    private fun writeSpeaker(t: AudioTrack) {
        try {
            while (true) {
                val pcm = queue.take()
                val gen = generation
                // Small slices, so a flush mid-chunk lets at most one slice of stale audio through.
                var off = 0
                while (off < pcm.size && gen == generation) {
                    val n = t.write(pcm, off, minOf(SLICE_BYTES, pcm.size - off))
                    if (n < 0) return
                    off += n
                    framesWritten += n / 2
                }
            }
        } catch (e: InterruptedException) {
            // release() ends the writer this way while it waits for audio.
        }
    }

    private companion object {
        const val MIC_RATE = 16_000
        const val SPEAKER_RATE = 24_000
        const val CHUNK_MS = 40
        const val SLICE_BYTES = SPEAKER_RATE / 1000 * 20 * 2
        const val JOIN_MS = 500L

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
