package com.kenku.mimic.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Signal processing for the engine. Port of engine/dsp.py; keep the two in step. */
object Dsp {
    const val SR = 24000
    const val HOP = 120
    private const val F0_MIN = 60.0
    private const val F0_MAX = 420.0

    // ------------------------------------------------------------ basics

    fun resample(x: FloatArray, src: Int, dst: Int): FloatArray {
        if (src == dst || x.isEmpty()) return x.copyOf()
        // windowed-sinc resampler (good enough for 16/22.05/44.1/48 kHz -> 24 kHz)
        val ratio = dst.toDouble() / src
        val n = (x.size * ratio).toInt()
        val out = FloatArray(n)
        val cutoff = min(1.0, ratio)
        val half = 16
        for (i in 0 until n) {
            val t = i / ratio
            val c = t.toInt()
            var acc = 0.0
            var wsum = 0.0
            for (k in c - half + 1..c + half) {
                if (k < 0 || k >= x.size) continue
                val d = t - k
                val s = if (abs(d) < 1e-9) 1.0 else sin(PI * d * cutoff) / (PI * d * cutoff)
                val w = 0.5 + 0.5 * cos(PI * d / half)
                acc += x[k] * s * w
                wsum += s * w
            }
            out[i] = if (abs(wsum) > 1e-9) (acc / wsum).toFloat() else 0f
        }
        return out
    }

    fun rmsDb(x: FloatArray, win: Int = 480, hop: Int = HOP): FloatArray {
        val n = 1 + max(0, x.size - win) / hop
        return FloatArray(n) { f ->
            var s = 0.0
            val a = f * hop
            for (i in a until min(x.size, a + win)) s += x[i] * x[i]
            (10 * log10(s / win + 1e-10)).toFloat()
        }
    }

    fun loudness(x: FloatArray): Double {
        val db = rmsDb(x)
        if (db.isEmpty()) return -60.0
        val top = db.max()
        val act = db.filter { it > top - 25 }
        if (act.isEmpty()) return top.toDouble()
        return 10 * log10(act.map { 10.0.pow(it / 10.0) }.average() + 1e-12)
    }

    fun scale(x: FloatArray, g: Double): FloatArray = FloatArray(x.size) { (x[it] * g).toFloat() }

    fun crossfade(a: FloatArray, b: FloatArray, n0: Int): FloatArray {
        val n = minOf(n0, a.size, b.size)
        if (n <= 0) return a + b
        val out = FloatArray(a.size + b.size - n)
        System.arraycopy(a, 0, out, 0, a.size - n)
        for (i in 0 until n) {
            val t = (PI / 2) * i / (n - 1).coerceAtLeast(1)
            out[a.size - n + i] = (a[a.size - n + i] * cos(t) + b[i] * sin(t)).toFloat()
        }
        System.arraycopy(b, n, out, a.size, b.size - n)
        return out
    }

    fun fadeEdges(x: FloatArray, n0: Int) {
        val n = min(n0, x.size / 4)
        for (i in 0 until n) {
            val g = i.toFloat() / n
            x[i] *= g; x[x.size - 1 - i] *= g
        }
    }

    // ------------------------------------------------------------ FFT & mel features

    /** In-place radix-2 FFT. re/im length must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private val fbCache = HashMap<Triple<Int, Int, Int>, Array<DoubleArray>>()

    fun melFilterbank(sr: Int, nFft: Int, nMels: Int, fmin: Double = 20.0): Array<DoubleArray> =
        fbCache.getOrPut(Triple(sr, nFft, nMels)) {
            fun hz2mel(h: Double) = 1127.0 * ln(1 + h / 700.0)
            fun mel2hz(m: Double) = 700.0 * (exp(m / 1127.0) - 1)
            val lo = hz2mel(fmin); val hi = hz2mel(sr / 2.0)
            val hz = DoubleArray(nMels + 2) { mel2hz(lo + (hi - lo) * it / (nMels + 1)) }
            val bins = nFft / 2 + 1
            Array(nMels) { m ->
                DoubleArray(bins) { b ->
                    val f = b.toDouble() * sr / nFft
                    val up = (f - hz[m]) / (hz[m + 1] - hz[m])
                    val dn = (hz[m + 2] - f) / (hz[m + 2] - hz[m + 1])
                    max(0.0, min(up, dn))
                }
            }
        }

    /** log-mel frames [n][nMels], 40 ms window, 5 ms hop. */
    fun logMel(x: FloatArray, nMels: Int = 40, win: Int = 960, nFft: Int = 1024, hop: Int = HOP): Array<DoubleArray> {
        val n = 1 + max(0, x.size - win) / hop
        val fb = melFilterbank(SR, nFft, nMels)
        val hann = DoubleArray(win) { 0.5 - 0.5 * cos(2 * PI * it / (win - 1)) }
        val re = DoubleArray(nFft); val im = DoubleArray(nFft)
        return Array(n) { f ->
            re.fill(0.0); im.fill(0.0)
            val a = f * hop
            for (i in 0 until win) re[i] = if (a + i < x.size) x[a + i] * hann[i] else 0.0
            fft(re, im)
            val p = DoubleArray(nFft / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
            DoubleArray(nMels) { m -> var s = 0.0; val row = fb[m]; for (b in p.indices) s += p[b] * row[b]; ln(s + 1e-8) }
        }
    }

    fun mfcc(x: FloatArray, n: Int = 13, nMels: Int = 40): Array<DoubleArray> {
        val lm = logMel(x, nMels)
        return Array(lm.size) { f ->
            DoubleArray(n) { k -> var s = 0.0; for (m in 0 until nMels) s += lm[f][m] * cos(PI / nMels * (m + 0.5) * k); s }
        }
    }

    // ------------------------------------------------------------ pitch (YIN)

    /** Per-hop F0 in Hz, 0 = unvoiced. */
    fun f0(x: FloatArray, thresh: Double = 0.15): FloatArray {
        val tmax = (SR / F0_MIN).toInt()
        val tmin = (SR / F0_MAX).toInt()
        val win = tmax * 2
        val w = win - tmax
        val n = x.size / HOP + 1
        val out = FloatArray(n)
        val loud = rmsDb(x + FloatArray(HOP))
        val d = DoubleArray(tmax + 1)
        val frame = DoubleArray(win)
        for (i in 0 until n) {
            if (i < loud.size && loud[i] < -50) continue
            val start = i * HOP - win / 2
            for (k in 0 until win) { val idx = start + k; frame[k] = if (idx in x.indices) x[idx].toDouble() else 0.0 }
            for (tau in 1..tmax) {
                var s = 0.0
                for (j in 0 until w) { val df = frame[j] - frame[j + tau]; s += df * df }
                d[tau] = s
            }
            var cum = 0.0
            var found = -1
            var t = tmin
            // cumulative mean normalized difference
            val dn = DoubleArray(tmax + 1)
            for (tau in 1..tmax) { cum += d[tau]; dn[tau] = if (cum > 0) d[tau] * tau / cum else 1.0 }
            while (t < tmax) { if (dn[t] < thresh) { found = t; break }; t++ }
            if (found < 0) continue
            while (found + 1 < tmax && dn[found + 1] < dn[found]) found++
            val a = dn[found - 1]; val b = dn[found]; val c = dn[found + 1]
            val den = a - 2 * b + c
            val tt = if (abs(den) > 1e-12) found + 0.5 * (a - c) / den else found.toDouble()
            out[i] = (SR / tt).toFloat()
        }
        return cleanF0(out)
    }

    private fun cleanF0(f0: FloatArray): FloatArray {
        val out = f0.copyOf()
        for (i in 2 until f0.size - 2) {
            if (f0[i] <= 0) continue
            val nb = (i - 2..i + 2).map { f0[it] }.filter { it > 0 }.sorted()
            if (nb.size >= 3) {
                val med = nb[nb.size / 2]
                if (abs(log2(f0[i] / med)) > 0.6) out[i] = med
            }
        }
        var i = 0
        while (i < out.size) {
            if (out[i] > 0) {
                var j = i
                while (j < out.size && out[j] > 0) j++
                if (j - i < 3) for (k in i until j) out[k] = 0f
                i = j
            } else i++
        }
        return out
    }

    fun median(v: List<Float>): Float = if (v.isEmpty()) 0f else v.sorted()[v.size / 2]

    // ------------------------------------------------------------ PSOLA

    private fun pitchMarks(x: FloatArray, f0: FloatArray): Pair<IntArray, IntArray> {
        val marks = ArrayList<Int>(); val periods = ArrayList<Int>()
        var t = 0
        while (t < x.size) {
            val fi = min(f0.size - 1, t / HOP)
            if (f0[fi] > 0) {
                val p = (SR / f0[fi]).roundToInt()
                val lo = max(0, t - p / 3); val hi = min(x.size, t + p / 3 + 1)
                if (hi > lo) {
                    var best = lo; var bv = -1f
                    for (k in lo until hi) if (abs(x[k]) > bv) { bv = abs(x[k]); best = k }
                    var t2 = best
                    if (marks.isNotEmpty() && t2 <= marks.last() + p / 2) t2 = marks.last() + p
                    t = t2
                }
                if (t >= x.size) break
                marks += t; periods += p; t += p
            } else {
                marks += t; periods += HOP; t += HOP
            }
        }
        return marks.toIntArray() to periods.toIntArray()
    }

    /** TD-PSOLA with per-hop pitch and time ratios (arrays of f0.size). */
    fun psola(x: FloatArray, f0: FloatArray, pitch: DoubleArray, time: DoubleArray): FloatArray {
        val nf = f0.size
        if (x.size < 4 * HOP || nf < 4) return x.copyOf()
        val (marks, periods) = pitchMarks(x, f0)
        if (marks.size < 3) return x.copyOf()
        val tOut = DoubleArray(nf + 1)
        for (i in 0 until nf) tOut[i + 1] = tOut[i] + time[i] * HOP
        val totalOut = tOut[nf] * x.size / max(1, nf * HOP)
        val outLen = totalOut.toInt() + 4 * HOP
        val y = DoubleArray(outLen); val ws = DoubleArray(outLen)
        fun outToIn(t: Double): Double {
            var lo = 0; var hi = nf
            while (lo < hi - 1) { val m = (lo + hi) / 2; if (tOut[m] <= t) lo = m else hi = m }
            val span = tOut[lo + 1] - tOut[lo]
            return (lo + if (span > 0) (t - tOut[lo]) / span else 0.0) * HOP
        }
        var ts = run {
            val m0 = marks[0].toDouble() / HOP
            val i = min(nf - 1, m0.toInt())
            tOut[i] + (m0 - i) * time[i] * HOP
        }
        while (ts < totalOut) {
            val ti = outToIn(ts)
            var k = marks.binarySearch(ti.toInt()).let { if (it < 0) -it - 1 else it }.coerceIn(0, marks.size - 1)
            if (k > 0 && abs(marks[k - 1] - ti) < abs(marks[k] - ti)) k--
            val m = marks[k]; val p = periods[k]
            val c = ts.roundToInt()
            if (c - p >= 0 && c + p <= outLen) {
                for (j in 0 until 2 * p) {
                    val src = m - p + j
                    val wv = 0.5 - 0.5 * cos(2 * PI * (j + 1) / (2 * p + 1))
                    val v = if (src in x.indices) x[src] else 0f
                    y[c - p + j] += v * wv; ws[c - p + j] += wv
                }
            }
            val fi = min(nf - 1, m / HOP)
            ts += if (f0[fi] > 0) p / pitch[fi] else p.toDouble()
        }
        val n = totalOut.toInt()
        return FloatArray(n) { if (ws[it] > 1e-2) (y[it] / ws[it]).toFloat() else y[it].toFloat() }
    }

    // ------------------------------------------------------------ room tone

    fun roomTone(src: FloatArray, seconds: Double, seed: Int = 0): FloatArray {
        val n = (seconds * SR).toInt()
        if (n <= 0) return FloatArray(0)
        val db = rmsDb(src)
        if (db.size < 4) return FloatArray(n)
        val q = db.indices.sortedBy { db[it] }.take(max(2, db.size / 10))
        var e = 0.0; var c = 0
        for (f in q) for (i in f * HOP until min(src.size, f * HOP + 480)) { e += src[i] * src[i]; c++ }
        val level = sqrt(e / max(1, c) + 1e-12)
        val rnd = Random(seed)
        // two-pole low-pass at ~3 kHz so it reads as room air rather than hiss
        val a = exp(-2 * PI * 3000 / SR)
        var s1 = 0.0; var s2 = 0.0
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val g = rnd.nextDouble() * 2 - 1
            s1 = (1 - a) * g + a * s1; s2 = (1 - a) * s1 + a * s2
            out[i] = s2
        }
        val r = sqrt(out.sumOf { it * it } / n + 1e-12)
        return FloatArray(n) { (out[it] * level / r).toFloat() }
    }
}

/** Split a long recording into utterances at natural pauses (each 1-15 s) so each can be
 *  transcribed and aligned on its own. Returns [start, end) sample ranges. */
object Segmenter {
    fun split(x: FloatArray, maxS: Double = 15.0, minS: Double = 1.0): List<IntArray> {
        val hop = Dsp.HOP
        val db = Dsp.rmsDb(x, 240)
        if (db.isEmpty()) return emptyList()
        val top = db.sorted()[(db.size * 0.98).toInt().coerceAtMost(db.size - 1)]
        val quiet = BooleanArray(db.size) { db[it] < top - 35 }
        val cuts = ArrayList<Int>()
        var i = 0
        while (i < quiet.size) {
            if (quiet[i]) {
                var j = i
                while (j < quiet.size && quiet[j]) j++
                if (j - i >= 60 && i > 0 && j < quiet.size) cuts += (i + j) / 2      // >= 0.3 s pause
                i = j
            } else i++
        }
        val bounds = (listOf(0) + cuts + listOf(db.size)).distinct()
        val segs = ArrayList<IntArray>()
        for (k in 0 until bounds.size - 1) segs += intArrayOf(bounds[k], bounds[k + 1])
        // merge tiny pieces into neighbours
        val merged = ArrayList<IntArray>()
        for (s in segs) {
            if (merged.isNotEmpty() && (s[1] - s[0]) * hop < minS * Dsp.SR) merged[merged.size - 1] = intArrayOf(merged.last()[0], s[1])
            else merged += s
        }
        // force-split anything too long at its quietest frame
        val out = ArrayList<IntArray>()
        val maxF = (maxS * Dsp.SR / hop).toInt()
        val queue = ArrayDeque(merged)
        while (queue.isNotEmpty()) {
            val s = queue.removeFirst()
            if (s[1] - s[0] <= maxF) { out += s; continue }
            val lo = s[0] + maxF / 3; val hi = s[0] + maxF
            var q = lo
            for (f in lo until hi) if (db[f] < db[q]) q = f
            out += intArrayOf(s[0], q); queue.addFirst(intArrayOf(q, s[1]))
        }
        // keep only pieces that contain speech
        return out.filter { s -> (s[0] until s[1]).any { !quiet[it] } }
            .map { intArrayOf(it[0] * hop, minOf(x.size, it[1] * hop)) }
    }
}
