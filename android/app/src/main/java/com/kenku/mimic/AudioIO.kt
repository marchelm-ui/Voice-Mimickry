package com.kenku.mimic

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import com.kenku.mimic.engine.Dsp
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Microphone, speaker and audio files. All audio inside the app is mono float at 24 kHz. */
object AudioIO {

    // ------------------------------------------------------------ recording

    class Recorder {
        @Volatile private var running = false
        private var thread: Thread? = null
        private val chunks = ArrayList<FloatArray>()
        @Volatile var level = 0f; private set          // 0..1 for a meter
        @Volatile var clipped = false; private set

        private var rate = 48000

        /** Throws with a clear message if no microphone route works. */
        @SuppressLint("MissingPermission")
        fun start() {
            var rec: AudioRecord? = null
            var isFloat = true
            // some phones refuse float capture or 48 kHz; walk down until one initializes
            for ((r, enc) in listOf(48000 to AudioFormat.ENCODING_PCM_FLOAT, 48000 to AudioFormat.ENCODING_PCM_16BIT,
                                    44100 to AudioFormat.ENCODING_PCM_16BIT, 16000 to AudioFormat.ENCODING_PCM_16BIT)) {
                val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, enc)
                if (min <= 0) continue
                val cand = try {
                    AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, r, AudioFormat.CHANNEL_IN_MONO, enc, max(min, r))
                } catch (e: Throwable) { null } ?: continue
                if (cand.state != AudioRecord.STATE_INITIALIZED) { cand.release(); continue }
                try { cand.startRecording() } catch (e: Throwable) { cand.release(); continue }
                if (cand.recordingState != AudioRecord.RECORDSTATE_RECORDING) { try { cand.stop() } catch (e: Throwable) {}; cand.release(); continue }
                rec = cand; rate = r; isFloat = enc == AudioFormat.ENCODING_PCM_FLOAT
                break
            }
            val ar = rec ?: throw IllegalStateException("The microphone wouldn't open. Close other recording apps and try again.")
            running = true
            chunks.clear(); clipped = false
            thread = Thread {
                val fbuf = FloatArray(rate / 20)
                val sbuf = ShortArray(rate / 20)
                while (running) {
                    val c: FloatArray? = if (isFloat) {
                        val n = ar.read(fbuf, 0, fbuf.size, AudioRecord.READ_BLOCKING)
                        if (n > 0) fbuf.copyOf(n) else null
                    } else {
                        val n = ar.read(sbuf, 0, sbuf.size, AudioRecord.READ_BLOCKING)
                        if (n > 0) FloatArray(n) { i -> sbuf[i] / 32768f } else null
                    }
                    if (c != null) {
                        synchronized(chunks) { chunks += c }
                        var pk = 0f
                        for (v in c) { val a = abs(v); if (a > pk) pk = a }
                        level = pk
                        if (pk > 0.99f) clipped = true
                    }
                }
                try { ar.stop() } catch (e: Throwable) {}
                ar.release()
            }.also { it.start() }
        }

        /** Stops and returns the recording at 24 kHz. */
        fun stop(): FloatArray {
            running = false
            thread?.join(2000)
            val x = Dsp.concat(synchronized(chunks) { chunks.toList() })
            return Dsp.resample(x, rate, Dsp.SR)
        }
    }

    // ------------------------------------------------------------ playback

    private var track: AudioTrack? = null
    private var stream: StreamPlayer? = null

    /**
     * Plays audio piece by piece while later pieces are still being made, so a long line starts
     * sounding after its first sentence. feed() never blocks the caller; a writer thread trickles
     * the queue into a streaming AudioTrack.
     */
    class StreamPlayer {
        private val q = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
        @Volatile private var cancelled = false
        private val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(Dsp.SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(Dsp.SR * 4)                     // 1 s of headroom
            .build()
        private val writer = Thread {
            try {
                track.play()
                while (!cancelled) {
                    val c = q.take()
                    if (c.isEmpty()) break                         // the end marker
                    var off = 0
                    while (off < c.size && !cancelled) {
                        val n = track.write(c, off, c.size - off, AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        off += n
                    }
                }
                if (!cancelled) track.stop() else { track.pause(); track.flush() }
            } catch (e: Throwable) { /* playback already torn down */ }
            track.release()
        }.apply { start() }

        fun feed(x: FloatArray) { if (x.isNotEmpty() && !cancelled) q.put(x) }
        fun finish() = q.put(FloatArray(0))
        fun cancel() { cancelled = true; q.clear(); q.put(FloatArray(0)) }
    }

    /** Starts streaming playback, stopping whatever was playing. */
    @Synchronized
    fun openStream(): StreamPlayer {
        stop()
        return StreamPlayer().also { stream = it }
    }

    @Synchronized
    fun play(x: FloatArray, onDone: (() -> Unit)? = null) {
        stop()
        if (x.isEmpty()) { onDone?.invoke(); return }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(Dsp.SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(x.size * 4)
            .build()
        t.write(x, 0, x.size, AudioTrack.WRITE_BLOCKING)
        t.notificationMarkerPosition = x.size - 1
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(tr: AudioTrack?) { onDone?.invoke() }
            override fun onPeriodicNotification(tr: AudioTrack?) {}
        })
        t.play()
        track = t
    }

    @Synchronized
    fun stop() {
        track?.let { try { it.stop() } catch (e: Throwable) {}; it.release() }
        track = null
        stream?.cancel()
        stream = null
    }

    // ------------------------------------------------------------ files

    fun writeWav(f: File, x: FloatArray) {
        val data = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) data.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(Dsp.SR).putInt(Dsp.SR * 2)
            .putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(x.size * 2)
        FileOutputStream(f).use { it.write(h.array()); it.write(data.array()) }
    }

    /** Read a 16-bit mono wav from the APK's assets at 24 kHz (the starter-voice packs). */
    fun readWavAsset(ctx: Context, path: String): FloatArray {
        val d = ctx.assets.open(path).use { it.readBytes() }
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        require(d.size > 44 && String(d, 0, 4) == "RIFF") { "Not a wav: $path" }
        var rate = Dsp.SR
        var o = 12
        while (o + 8 <= d.size) {                     // walk RIFF chunks; ffmpeg adds LIST etc.
            val id = String(d, o, 4)
            val sz = b.getInt(o + 4)
            if (id == "fmt ") rate = b.getInt(o + 12)
            if (id == "data") {
                val n = minOf(sz, d.size - o - 8) / 2
                val x = FloatArray(n) { i -> b.getShort(o + 8 + i * 2) / 32768f }
                return if (rate == Dsp.SR) x else Dsp.resample(x, rate, Dsp.SR)
            }
            o += 8 + sz + (sz and 1)
        }
        throw IllegalArgumentException("No audio data in $path")
    }

    /** Decode any audio file Android understands (wav, mp3, m4a, ogg, ...) to 24 kHz mono. */
    fun decode(ctx: Context, uri: Uri, maxSeconds: Int = 600): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        return decodeExtractor(ex, maxSeconds)
    }

    /** Decode a bundled sound effect (ogg in assets) to 24 kHz mono. */
    fun decodeAsset(ctx: Context, path: String, maxSeconds: Int = 60): FloatArray {
        val ex = MediaExtractor()
        ctx.assets.openFd(path).use { fd -> ex.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        return decodeExtractor(ex, maxSeconds)
    }

    /** Decode a sound the user recorded (a file path) to 24 kHz mono. */
    fun decodeFile(path: String, maxSeconds: Int = 60): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        return decodeExtractor(ex, maxSeconds)
    }

    private fun decodeExtractor(ex: MediaExtractor, maxSeconds: Int): FloatArray {
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio in that file")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val out = ArrayList<FloatArray>()
        var total = 0
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (oi >= 0) {
                val b = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                b.position(info.offset); b.limit(info.offset + info.size)
                val mono = if (pcmFloat) {
                    val fb = b.asFloatBuffer(); val n = fb.remaining() / ch
                    FloatArray(n) { i -> var s = 0f; for (c in 0 until ch) s += fb.get(i * ch + c); s / ch }
                } else {
                    val sb = b.asShortBuffer(); val n = sb.remaining() / ch
                    FloatArray(n) { i -> var s = 0f; for (c in 0 until ch) s += sb.get(i * ch + c) / 32768f; s / ch }
                }
                out += mono; total += mono.size
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || total > maxSeconds * rate) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        val x = FloatArray(total)
        var p = 0
        for (c in out) { System.arraycopy(c, 0, x, p, c.size); p += c.size }
        return Dsp.resample(x, rate, Dsp.SR)
    }
}
