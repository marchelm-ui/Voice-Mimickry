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

        @SuppressLint("MissingPermission")
        fun start() {
            val rate = 48000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT, max(min, rate / 5 * 4))
            running = true
            chunks.clear(); clipped = false
            rec.startRecording()
            thread = Thread {
                val buf = FloatArray(rate / 20)
                while (running) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n > 0) {
                        val c = buf.copyOf(n)
                        synchronized(chunks) { chunks += c }
                        var pk = 0f
                        for (v in c) { val a = abs(v); if (a > pk) pk = a }
                        level = pk
                        if (pk > 0.99f) clipped = true
                    }
                }
                rec.stop(); rec.release()
            }.also { it.start() }
        }

        /** Stops and returns the recording at 24 kHz. */
        fun stop(): FloatArray {
            running = false
            thread?.join(2000)
            val all = synchronized(chunks) { chunks.toList() }
            val x = FloatArray(all.sumOf { it.size })
            var p = 0
            for (c in all) { System.arraycopy(c, 0, x, p, c.size); p += c.size }
            return Dsp.resample(x, 48000, Dsp.SR)
        }
    }

    // ------------------------------------------------------------ playback

    private var track: AudioTrack? = null

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

    /** Decode any audio file Android understands (wav, mp3, m4a, ogg, ...) to 24 kHz mono. */
    fun decode(ctx: Context, uri: Uri, maxSeconds: Int = 600): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
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
