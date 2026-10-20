package com.kenku.mimic.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Kokoro run directly through onnxruntime as a *reference reader*: it reads text in a stock
 * voice and also reports how long each phoneme lasts. The engine uses that for two things:
 * aligning recordings precisely (Aligner) and predicting how a new sentence should be paced
 * and intoned (Synth's prosody transplant). Port of engine/kokoro_ref.py.
 */
class KokoroRef(dir: File) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokens = HashMap<String, Long>()
    private val lexicon = HashMap<String, List<String>>()
    private val voices: FloatArray
    private val voiceRows: Int

    init {
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(Dsp.threads()) }
        session = env.createSession(File(dir, MODEL).absolutePath, opts)
        File(dir, "tokens.txt").forEachLine { line ->
            val i = line.lastIndexOf(' ')
            if (i >= 0) {
                val k = line.substring(0, i).ifEmpty { " " }
                line.substring(i + 1).toLongOrNull()?.let { tokens[k] = it }
            }
        }
        File(dir, "lexicon-us-en.txt").forEachLine { line ->
            val p = line.split(' ')
            if (p.size > 1 && p[0] !in lexicon) lexicon[p[0]] = p.subList(1, p.size).filter { it.isNotEmpty() }
        }
        val bytes = File(dir, "voices.bin").readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        voices = FloatArray(fb.remaining()).also { fb.get(it) }
        voiceRows = voices.size / (NUM_VOICES * 256)
    }

    fun phonemes(word: String): List<String> {
        val w = word.lowercase()
        lexicon[w]?.let { return it }
        lexicon[w.replace("'", "")]?.let { return it }
        val out = w.flatMap { LETTER[it]?.map { c -> c.toString() } ?: emptyList() }
        return out.ifEmpty { listOf("ə") }
    }

    data class Phone(val ph: String, val s: Int, val e: Int)
    data class Reading(val audio: FloatArray, val words: List<IntArray>, val phones: List<List<Phone>>)

    /** Read words in voice [sid]; returns audio plus exact word and phone spans (samples). */
    @Synchronized
    fun read(words: List<String>, sid: Int, speed: Float = 1f, end: String = "."): Reading {
        val ids = ArrayList<Long>(); val owner = ArrayList<Int>(); val phs = ArrayList<String?>()
        ids += 0L; owner += -1; phs += null
        words.forEachIndexed { i, w ->
            for (ph in phonemes(w)) {
                val id = tokens[ph] ?: continue
                ids += id; owner += i; phs += ph
            }
            if (i < words.size - 1) { ids += tokens[" "]!!; owner += -1; phs += null }
        }
        ids += tokens[end] ?: tokens["."]!!; owner += -1; phs += null
        ids += 0L; owner += -1; phs += null
        val row = minOf(ids.size - 2, voiceRows - 1)
        val style = FloatArray(256) { voices[(sid * voiceRows + row) * 256 + it] }
        val tTok = OnnxTensor.createTensor(env, LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong()))
        val tSty = OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, 256))
        val tSpd = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1))
        val res = session.run(mapOf("tokens" to tTok, "style" to tSty, "speed" to tSpd))
        val audio = (res[0].value as FloatArray).copyOf()
        val dur = res[1].value as LongArray
        res.close(); tTok.close(); tSty.close(); tSpd.close()
        val scale = audio.size.toDouble() / maxOf(1L, dur.sum())
        val t = DoubleArray(dur.size + 1)
        for (k in dur.indices) t[k + 1] = t[k] + dur[k] * scale
        val spans = Array(words.size) { intArrayOf(-1, 0) }
        val phones = List(words.size) { ArrayList<Phone>() }
        for (k in owner.indices) {
            val o = owner[k]
            if (o < 0 || k >= dur.size) continue
            val s = t[k].toInt(); val e = t[k + 1].toInt()
            if (spans[o][0] < 0) spans[o][0] = s
            spans[o][1] = e
            phones[o] += Phone(phs[k]!!, s, e)
        }
        return Reading(audio, spans.map { intArrayOf(maxOf(0, it[0]), it[1]) }, phones)
    }

    companion object {
        const val MODEL = "model.int8.onnx"
        const val NUM_VOICES = 54
        const val FEMALE_A = 2; const val FEMALE_B = 9; const val MALE_A = 11; const val MALE_B = 13
        private val LETTER = mapOf('a' to "æ", 'b' to "b", 'c' to "k", 'd' to "d", 'e' to "ɛ", 'f' to "f", 'g' to "ɡ",
            'h' to "h", 'i' to "ɪ", 'j' to "ʤ", 'k' to "k", 'l' to "l", 'm' to "m", 'n' to "n", 'o' to "ɑ", 'p' to "p",
            'q' to "k", 'r' to "ɹ", 's' to "s", 't' to "t", 'u' to "ʌ", 'v' to "v", 'w' to "w", 'x' to "ks", 'y' to "j", 'z' to "z")
    }
}
