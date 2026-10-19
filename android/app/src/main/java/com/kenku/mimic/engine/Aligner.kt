package com.kenku.mimic.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Precise word and phoneme boundaries: the reference reader says the transcript, and dynamic
 * time warping maps its exact boundaries onto the real recording. Port of engine/aligner.py.
 * Measured on stand-in voices: median boundary error 5-8 ms vs 110-125 ms from recognizer timestamps.
 */
class Aligner(private val ref: KokoroRef) {

    data class Result(val words: List<IntArray>, val phones: List<List<KokoroRef.Phone>>, val cost: Double)

    fun align(audio: FloatArray, words: List<String>, end: String = "."): Result {
        val f0 = Dsp.f0(audio).filter { it > 0 }
        val female = f0.isNotEmpty() && Dsp.median(f0) > 165f
        val refs = if (female) listOf(KokoroRef.FEMALE_A, KokoroRef.FEMALE_B) else listOf(KokoroRef.MALE_A, KokoroRef.MALE_B)
        return refs.map { alignOne(audio, words, it, end) }.minBy { it.cost }
    }

    private fun trim(x: FloatArray): IntArray {
        val db = Dsp.rmsDb(x, 240)
        val top = db.maxOrNull() ?: return intArrayOf(0, x.size)
        val v = db.indices.filter { db[it] > top - 35 }
        if (v.isEmpty()) return intArrayOf(0, x.size)
        return intArrayOf(v.first() * Dsp.HOP, min(x.size, (v.last() + 2) * Dsp.HOP))
    }

    fun features(x: FloatArray): Array<DoubleArray> {
        val m = Dsp.mfcc(x, 20, 40).map { it.copyOfRange(1, 20) }
        val n = m.size
        if (n == 0) return emptyArray()
        val d = m[0].size
        val mean = DoubleArray(d) { k -> m.sumOf { it[k] } / n }
        val sd = DoubleArray(d) { k -> sqrt(m.sumOf { (it[k] - mean[k]) * (it[k] - mean[k]) } / n) + 1e-6 }
        val z = m.map { r -> DoubleArray(d) { (r[it] - mean[it]) / sd[it] } }
        return Array(n) { i ->
            val a = z[max(0, i - 1)]; val b = z[min(n - 1, i + 1)]
            val den = if (i == 0 || i == n - 1) 1.0 else 2.0
            DoubleArray(2 * d) { k -> if (k < d) z[i][k] else 0.5 * (b[k - d] - a[k - d]) / den }
        }
    }

    /** DTW with a Sakoe-Chiba band. Returns path (i,j) pairs and the length-normalized cost. */
    fun dtw(a: Array<DoubleArray>, b: Array<DoubleArray>, band: Double = 0.25): Pair<List<IntArray>, Double> {
        val n = a.size; val m = b.size
        val w = max((band * max(n, m)).toInt(), abs(n - m) + 10)
        val D = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }   // float rows: half the memory
        D[0][0] = 0f
        for (i in 1..n) {
            val c = i.toDouble() * m / n
            val lo = max(1, (c - w).toInt()); val hi = min(m, (c + w).toInt())
            for (j in lo..hi) {
                var s = 0.0
                val ai = a[i - 1]; val bj = b[j - 1]
                for (k in ai.indices) { val df = ai[k] - bj[k]; s += df * df }
                D[i][j] = (sqrt(s) + minOf(D[i - 1][j - 1], D[i - 1][j] + 0.3f, D[i][j - 1] + 0.3f)).toFloat()
            }
        }
        var i = n; var j = m
        val path = ArrayList<IntArray>()
        path += intArrayOf(i - 1, j - 1)
        while (i > 1 || j > 1) {
            val opts = listOf(Triple(D[i - 1][j - 1], i - 1, j - 1), Triple(D[i - 1][j], i - 1, j), Triple(D[i][j - 1], i, j - 1))
            val best = opts.minBy { it.first }
            i = max(1, best.second); j = max(1, best.third)
            path += intArrayOf(i - 1, j - 1)
        }
        path.reverse()
        return path to D[n][m].toDouble() / (n + m)
    }

    private fun alignOne(audio: FloatArray, words: List<String>, sid: Int, end: String): Result {
        val (s0, e0) = trim(audio).let { it[0] to it[1] }
        val rec = audio.copyOfRange(s0, e0)
        var r = ref.read(words, sid, 1f, end)
        var tr = trim(r.audio)
        val speed = ((tr[1] - tr[0]).toDouble() / max(1, rec.size)).coerceIn(0.6, 1.7)
        if (abs(speed - 1) > 0.08) { r = ref.read(words, sid, speed.toFloat(), end); tr = trim(r.audio) }
        val fa = features(r.audio.copyOfRange(tr[0], tr[1]))
        val fb = features(rec)
        if (fa.isEmpty() || fb.isEmpty()) {
            val even = words.indices.map { k -> intArrayOf(s0 + k * rec.size / words.size, s0 + (k + 1) * rec.size / words.size) }
            return Result(even, List(words.size) { emptyList() }, Double.MAX_VALUE)
        }
        // Warp at 10 ms resolution (every 2nd frame): same measured accuracy, a quarter of the memory.
        val S = 2
        val (path, cost) = dtw(fa.filterIndexed { i, _ -> i % S == 0 }.toTypedArray(), fb.filterIndexed { i, _ -> i % S == 0 }.toTypedArray())
        val map = HashMap<Int, MutableList<Int>>()
        for (p in path) for (di in 0 until S) map.getOrPut(p[0] * S + di) { ArrayList() }.apply { for (dj in 0 until S) add(p[1] * S + dj) }
        fun js(sample: Int): List<Int> {
            val f = ((sample - tr[0]) / Dsp.HOP).coerceIn(0, fa.size - 1)
            return map[f] ?: listOf(f)
        }
        fun toRec(sample: Int): Int = js(sample).sorted().let { it[it.size / 2] } * Dsp.HOP
        // Where the speaker paused but the reference didn't, the warp runs flat across the pause:
        // end each word at the FIRST matching frame and start the next at the LAST, so the pause
        // falls between them (measured: this cut misheard words from 79% to about 23%).
        val out = ArrayList<IntArray>()
        val cuts = (0 until words.size - 1).map { k ->
            var lo = js(maxOf(0, r.words[k][1] - 1)).min() * Dsp.HOP + Dsp.HOP
            var hi = js(r.words[k + 1][0]).max() * Dsp.HOP
            if (hi < lo) { val m = (lo + hi) / 2; lo = m; hi = m }
            intArrayOf(lo, hi)
        }
        for (k in words.indices) {
            val a = if (k == 0) 0 else cuts[k - 1][1]
            val b = if (k == words.size - 1) rec.size else cuts[k][0]
            out += intArrayOf(s0 + a, s0 + max(a + Dsp.HOP, b))
        }
        val phones = r.phones.map { list -> list.map { KokoroRef.Phone(it.ph, s0 + toRec(it.s), s0 + toRec(it.e)) } }
        return Result(out, phones, cost)
    }
}
