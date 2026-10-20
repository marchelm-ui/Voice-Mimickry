package com.kenku.mimic.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Frame-level mimicry (kNN voice conversion, Baas et al. 2023). A natural reading of the line is
 * turned into speech features (WavLM, first 6 layers); every 20 ms frame is replaced by the
 * average of its 4 closest frames from the person's own recordings; a HiFi-GAN vocoder turns
 * that back into sound. Flow from the reader, sound material from the person.
 */
class FrameMimic(dir: File) {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(Dsp.threads())
    }
    private val encoder = env.createSession(File(dir, "wavlm6.fp16.onnx").absolutePath, opts)
    private val vocoder = env.createSession(File(dir, "hifigan.fp16.onnx").absolutePath, opts)
    private val workers = Executors.newFixedThreadPool(4)

    companion object { const val DIM = 1024; const val TOPK = 4 }

    /**
     * Speech features of 24 kHz audio: N x 1024, row-major. Long audio is encoded in 10-second
     * windows with half a second of context — the transformer's attention cost grows with the
     * square of the window, so this is ~10x faster on a 3-minute recording and the frames are
     * the same to within the overlap.
     */
    fun features(audio24: FloatArray): FloatArray {
        val x = Dsp.resample(audio24, Dsp.SR, 16000)
        if (x.size < 1600) return FloatArray(0)
        val win = 16000 * 10
        val ov = 16000 / 2
        if (x.size <= win + ov) return encode(x)
        val parts = ArrayList<FloatArray>()
        var s = 0
        while (s < x.size) {
            val from = if (s == 0) 0 else s - ov
            val seg = encode(x.copyOfRange(from, min(x.size, s + win)))
            val drop = if (s == 0) 0 else (ov / 320) * DIM           // frames that belong to the previous window
            if (seg.size > drop) parts += seg.copyOfRange(drop, seg.size)
            s += win
        }
        return Dsp.concat(parts)
    }

    @Synchronized
    private fun encode(x16: FloatArray): FloatArray {
        val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(x16), longArrayOf(1, x16.size.toLong()))
        val r = encoder.run(mapOf("wav" to t))
        @Suppress("UNCHECKED_CAST")
        val rows = r[0].value as Array<FloatArray>
        r.close(); t.close()
        val out = FloatArray(rows.size * DIM)
        rows.forEachIndexed { i, row -> System.arraycopy(row, 0, out, i * DIM, DIM) }
        return out
    }

    /** The person's sound inventory: features of all their (speech-only) recordings. */
    class Pool(val feats: FloatArray) {
        val n = feats.size / DIM
        val norms = FloatArray(n) { i -> var s = 0f; for (k in 0 until DIM) { val v = feats[i * DIM + k]; s += v * v }; sqrt(s) + 1e-6f }
    }


    fun saveFeats(feats: FloatArray, f: File) {
        val bb = ByteBuffer.allocate(feats.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in feats) bb.putShort(android.util.Half.toHalf(v))
        f.writeBytes(bb.array())
    }

    fun loadFeats(f: File): FloatArray {
        val sb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(sb.remaining()) { android.util.Half.toFloat(sb.get(it)) }
    }

    /** Re-voice [source24] (any natural reading of the line) with [pool]'s sounds. */
    fun convert(source24: FloatArray, pool: Pool, targetDb: Double): FloatArray {
        val q = features(source24)
        val nq = q.size / DIM
        if (nq == 0 || pool.n < TOPK) return FloatArray(0)
        val out = FloatArray(nq * DIM)
        // nearest neighbours by cosine similarity, split across threads
        val chunks = (0 until nq).chunked(max(1, nq / 4))
        chunks.map { rows ->
            workers.submit(Callable {
                val best = IntArray(TOPK); val bestS = FloatArray(TOPK)
                for (i in rows) {
                    var qn = 0f
                    for (k in 0 until DIM) { val v = q[i * DIM + k]; qn += v * v }
                    qn = sqrt(qn) + 1e-6f
                    bestS.fill(-2f); best.fill(0)
                    for (j in 0 until pool.n) {
                        var dot = 0f
                        val qo = i * DIM; val po = j * DIM
                        for (k in 0 until DIM) dot += q[qo + k] * pool.feats[po + k]
                        val s = dot / (qn * pool.norms[j])
                        if (s > bestS[TOPK - 1]) {
                            var m = TOPK - 1
                            while (m > 0 && bestS[m - 1] < s) { bestS[m] = bestS[m - 1]; best[m] = best[m - 1]; m-- }
                            bestS[m] = s; best[m] = j
                        }
                    }
                    for (k in 0 until DIM) {
                        var acc = 0f
                        for (b in best) acc += pool.feats[b * DIM + k]
                        out[i * DIM + k] = acc / TOPK
                    }
                }
            })
        }.forEach { it.get() }
        val wav16 = vocode(out, nq)
        val y = Dsp.resample(wav16, 16000, Dsp.SR)
        val gain = 10.0.pow((targetDb - Dsp.loudness(y)) / 20)
        return Dsp.scale(y, min(gain, 30.0))
    }

    @Synchronized
    private fun vocode(feats: FloatArray, n: Int): FloatArray {
        val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), longArrayOf(1, n.toLong(), DIM.toLong()))
        val r = vocoder.run(mapOf("feats" to t))
        @Suppress("UNCHECKED_CAST")
        val w = (r[0].value as Array<FloatArray>)[0]
        r.close(); t.close()
        return w
    }
}
