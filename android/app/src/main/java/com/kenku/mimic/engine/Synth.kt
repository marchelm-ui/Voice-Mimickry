package com.kenku.mimic.engine

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Say a new line in a learned voice, using the sounds that voice actually made.
 * Port of engine/synth.py; see that file for the full explanation of each step.
 */
class Synth(private val ref: KokoroRef, private val aligner: Aligner, private val clone: () -> NeuralClone?) {

    enum class Fallback { PHONES, NEURAL, AUTO, SILENT }

    data class Opts(
        var fallback: Fallback = Fallback.AUTO,
        var prosody: Double = 0.7,
        var timing: Double = 0.5,
        var smoothJoins: Boolean = true,
        var roomTone: Boolean = true,
        var matchLoudness: Boolean = true,
        var maxSpan: Int = 8,
    )

    /** How each word of the line was made, for the "how convincing is this" display. */
    enum class Source { REAL, SPLICED, NEURAL, MISSING }
    data class Report(val words: List<Pair<String, Source>>, val seams: Int)

    private class Cand(val len: Int, val rec: Int, val word: Int)   // rec = -1: unheard word

    private class Unit(val words: List<TextNorm.Tok.Word>, var audio: FloatArray, val source: Source, var wordLens: MutableList<Int>)

    companion object {
        val PAUSE_S = mapOf("," to 0.20, "." to 0.38, "!" to 0.34, "?" to 0.38)
        val XFADE = (0.006 * Dsp.SR).toInt()
        val MARGIN = (0.004 * Dsp.SR).toInt()
    }

    // ------------------------------------------------------------ unit selection

    private fun candidates(bank: VoiceBank, words: List<TextNorm.Tok.Word>, maxSpan: Int): List<List<Cand>> =
        words.indices.map { i ->
            val out = ArrayList<Cand>()
            for (rw in bank.index[words[i].w] ?: emptyList()) {
                val rec = bank.recs[rw[0]]; val wi = rw[1]
                var L = 1
                while (L < maxSpan && i + L < words.size && wi + L < rec.words.size &&
                    rec.words[wi + L].text == words[i + L].w && rec.words[wi + L - 1].pauseAfter < 0.12) L++
                for (l in 1..L) out += Cand(l, rw[0], wi)
            }
            out
        }

    private fun targetCost(bank: VoiceBank, c: Cand, words: List<TextNorm.Tok.Word>, i: Int, end: String): Double {
        val rec = bank.recs[c.rec]
        val first = rec.words[c.word]; val last = rec.words[c.word + c.len - 1]
        var cost = 1.0
        val tFinal = i + c.len == words.size
        if (tFinal && !last.final) cost += 0.9
        if (!tFinal && last.final) cost += 0.5
        if (tFinal && end == "?" && !last.question) cost += 0.4
        if (tFinal && end != "?" && last.question) cost += 0.8
        if (i == 0 && !first.initial) cost += 0.15
        if (i > 0 && first.initial) cost += 0.1
        for (k in 0 until c.len) cost += 2.0 * (1 - rec.words[c.word + k].conf)
        return cost
    }

    private fun joinCost(bank: VoiceBank, a: Cand, b: Cand): Double {
        if (a.rec == b.rec && a.word + a.len == b.word) return 0.0
        val A = bank.recs[a.rec].words[a.word + a.len - 1]
        val B = bank.recs[b.rec].words[b.word]
        var spec = 0.0
        for (k in 1 until 13) { val d = A.mfOut[k] - B.mfIn[k]; spec += d * d }
        val pitch = if (A.f0End > 0 && B.f0Start > 0) abs(log2(A.f0End / B.f0Start)) * 3.0 else 0.3
        return 0.4 + sqrt(spec) / 20.0 + pitch + abs(A.loud - B.loud) / 12.0
    }

    private fun select(bank: VoiceBank, words: List<TextNorm.Tok.Word>, end: String, maxSpan: Int): List<Cand> {
        val n = words.size
        val cands = candidates(bank, words, maxSpan)
        data class St(val cost: Double, val from: Int, val prev: Cand?)
        val best = Array(n + 1) { HashMap<Cand?, St>() }
        best[0][null] = St(0.0, -1, null)
        for (i in 0 until n) {
            if (best[i].isEmpty()) continue
            val opts = cands[i].ifEmpty { listOf(Cand(1, -1, -1)) }
            for (c in opts) {
                val tc = if (c.rec < 0) 6.0 else targetCost(bank, c, words, i, end)
                for ((prev, st) in best[i]) {
                    val jc = if (prev == null || prev.rec < 0 || c.rec < 0) 0.0 else joinCost(bank, prev, c)
                    val tot = st.cost + tc + jc
                    val cur = best[i + c.len][c]
                    if (cur == null || tot < cur.cost) best[i + c.len][c] = St(tot, i, prev)
                }
            }
        }
        val path = ArrayList<Cand>()
        var key: Cand? = best[n].minByOrNull { it.value.cost }!!.key
        var j = n
        while (key != null) {
            path += key
            val st = best[j][key]!!
            key = st.prev; j = st.from
        }
        path.reverse()
        return path
    }

    // ------------------------------------------------------------ unheard words

    private fun phoneIndex(bank: VoiceBank): HashMap<List<String>, MutableList<IntArray>> {
        bank.phoneIndex?.let { return it }
        val idx = HashMap<List<String>, MutableList<IntArray>>()
        bank.recs.forEachIndexed { ri, r ->
            r.words.forEachIndexed { wi, w ->
                val ph = w.phones
                for (a in ph.indices) for (b in a + 1..min(ph.size, a + 5)) {
                    idx.getOrPut(ph.subList(a, b).map { it.ph }) { ArrayList() }.add(intArrayOf(ri, wi, a, b))
                }
            }
        }
        bank.phoneIndex = idx
        return idx
    }

    private fun splice(bank: VoiceBank, word: String): FloatArray? {
        val target = ref.phonemes(word).filter { it != "ˈ" && it != "ˌ" }
        val idx = phoneIndex(bank)
        val pieces = ArrayList<FloatArray>()
        var i = 0
        while (i < target.size) {
            var got: Pair<Int, IntArray>? = null
            for (L in min(5, target.size - i) downTo 1) {
                val opts = idx[target.subList(i, i + L)] ?: continue
                val pick = opts.minBy { o ->
                    val w = bank.recs[o[0]].words[o[1]]
                    val n = w.phones.size
                    var s = 0.0
                    if ((o[2] == 0) != (i == 0)) s += 1
                    if ((o[3] == n) != (i + L == target.size)) s += 1
                    s + 2 * (1 - w.conf)
                }
                got = L to pick; break
            }
            if (got == null) return null
            val (L, o) = got
            val ph = bank.recs[o[0]].words[o[1]].phones
            val a = bank.recs[o[0]].audio
            pieces += a.copyOfRange(max(0, ph[o[2]].s - MARGIN), min(a.size, ph[o[3] - 1].e + MARGIN))
            i += L
        }
        var out = pieces[0]
        for (p in pieces.drop(1)) out = Dsp.crossfade(out, p, minOf(XFADE, p.size / 3, out.size / 3))
        return out
    }

    private fun neural(bank: VoiceBank, phrase: List<String>, need: List<Int>): Map<Int, FloatArray>? {
        val c = clone() ?: return null
        val x = c.say(phrase.joinToString(" ") { it.lowercase() } + ".", bank.referenceClip())
        val al = aligner.align(x, phrase)
        return need.associateWith { x.copyOfRange(al.words[it][0], al.words[it][1]) }
    }

    // ------------------------------------------------------------ prosody

    private class RefWord(val len: Int, val mean: Double?, val start: Double?, val end: Double?)

    private fun semis(f: Float, ref: Float) = 12 * log2(max(f, 1e-3f) / ref).toDouble()

    private fun referenceProsody(words: List<String>, end: String, sid: Int): List<RefWord> {
        val r = ref.read(words, sid, 1f, if (end in setOf(".", "!", "?", ",")) end else ".")
        val f0 = Dsp.f0(r.audio)
        val med = Dsp.median(f0.filter { it > 0 }).takeIf { it > 0 } ?: 150f
        return r.words.map { sp ->
            val a = sp[0] / Dsp.HOP; val b = max(a + 1, sp[1] / Dsp.HOP)
            val v = (a until min(b, f0.size)).map { f0[it] }.filter { it > 0 }
            if (v.isEmpty()) RefWord(sp[1] - sp[0], null, null, null) else {
                val st = v.map { semis(it, med) }
                val k = max(1, st.size / 3)
                RefWord(sp[1] - sp[0], st.average(), st.take(k).average(), st.takeLast(k).average())
            }
        }
    }

    private fun edgeF0(f: DoubleArray, tail: Boolean, n: Int = 8): Double? {
        val seg = if (tail) f.takeLast(n * 3) else f.take(n * 3)
        val v = seg.filter { it > 0 }
        if (v.size < 2) return null
        val pick = if (tail) v.takeLast(n) else v.take(n)
        return pick.sorted()[pick.size / 2]
    }

    private fun rampEdge(r: DoubleArray, semis: Double, tail: Boolean, n0: Int = 24) {
        val n = min(n0, r.size)
        for (i in 0 until n) {
            val g = 2.0.pow(semis * i / max(1, n - 1) / 12)
            if (tail) r[r.size - n + i] *= g else r[n - 1 - i] *= g
        }
    }

    private fun applyProsody(bank: VoiceBank, units: List<Unit>, refw: List<RefWord>, o: Opts, end: String): List<FloatArray> {
        val med = if (bank.f0Median > 0) bank.f0Median else 150f
        class Plan(val u: Unit, val f0: FloatArray, val pitch: DoubleArray, val time: DoubleArray)
        val plans = ArrayList<Plan>()
        var wi = 0
        for (u in units) {
            val f0 = Dsp.f0(u.audio)
            val n = f0.size
            val pr = DoubleArray(n) { 1.0 }; val tr = DoubleArray(n) { 1.0 }
            var t = 0
            u.wordLens.forEachIndexed { k, L ->
                val r = refw.getOrNull(wi + k)
                val a = t / Dsp.HOP; val b = min(n, (t + L) / Dsp.HOP + 1)
                val v = (a until b).map { f0[it] }.filter { it > 0 }
                if (r?.mean != null && v.size > 2 && b > a) {
                    val cur = v.map { semis(it, med) }
                    val kk = max(1, cur.size / 3)
                    val ds = (r.start!! - cur.take(kk).average()) * o.prosody
                    val de = (r.end!! - cur.takeLast(kk).average()) * o.prosody
                    for (f in a until b) {
                        val x = if (b - a > 1) (f - a).toDouble() / (b - a - 1) else 0.0
                        pr[f] = 2.0.pow((ds + (de - ds) * x).coerceIn(-5.0, 5.0) / 12)
                    }
                }
                t += L
            }
            plans += Plan(u, f0, pr, tr)
            wi += u.wordLens.size
        }
        val totSrc = units.sumOf { it.wordLens.sum() }.toDouble()
        val totRef = refw.sumOf { it.len }.toDouble().coerceAtLeast(1.0)
        val scale = totSrc / totRef
        wi = 0
        for (p in plans) {
            var t = 0
            p.u.wordLens.forEachIndexed { k, L ->
                val want = (refw.getOrNull(wi + k)?.len ?: L) * scale
                val r = (want / max(L, 1)).pow(o.timing).coerceIn(0.8, 1.25)
                for (f in t / Dsp.HOP until min(p.f0.size, (t + L) / Dsp.HOP + 1)) p.time[f] = r
                t += L
            }
            wi += p.u.wordLens.size
        }
        plans.last().let { p ->
            val last = p.u.wordLens.last() / Dsp.HOP
            for (f in max(0, p.time.size - last) until p.time.size) p.time[f] *= if (end in setOf(".", "!", "?")) 1.08 else 1.03
        }
        if (o.smoothJoins && plans.size > 1) {
            for (k in 0 until plans.size - 1) {
                val A = plans[k]; val B = plans[k + 1]
                val ea = edgeF0(DoubleArray(A.f0.size) { A.f0[it] * A.pitch[it] }, true)
                val sb = edgeF0(DoubleArray(B.f0.size) { B.f0[it] * B.pitch[it] }, false)
                if (ea != null && sb != null) {
                    val gap = 12 * log2(ea / sb)
                    if (abs(gap) > 0.3) {
                        val half = (gap / 2).coerceIn(-4.0, 4.0)
                        rampEdge(A.pitch, -half, true); rampEdge(B.pitch, half, false)
                    }
                }
            }
        }
        return plans.map { p ->
            val flat = p.pitch.all { abs(it - 1) < 0.004 } && p.time.all { abs(it - 1) < 0.01 }
            if (flat) p.u.audio else Dsp.psola(p.u.audio, p.f0, p.pitch, p.time)
        }
    }

    // ------------------------------------------------------------ main entry

    fun speak(bank: VoiceBank, line: String, o: Opts = Opts()): Pair<FloatArray, Report> {
        val refSid = if (bank.f0Median > 165) KokoroRef.FEMALE_A else KokoroRef.MALE_A
        val phrases = TextNorm.phrases(line)
        val toneSrc = bank.recs.take(3).fold(FloatArray(0)) { a, r -> a + r.audio }.ifEmpty { FloatArray(Dsp.SR) }
        val pieces = ArrayList<FloatArray>()
        val sources = ArrayList<Pair<String, Source>>()
        var seams = 0
        pieces += if (o.roomTone) Dsp.roomTone(toneSrc, 0.08, 99) else FloatArray((0.08 * Dsp.SR).toInt())
        phrases.forEachIndexed { pi, (words, end) ->
            val path = select(bank, words, end, o.maxSpan)
            val units = ArrayList<Unit>()
            val needNeural = ArrayList<Pair<Int, Int>>()
            var pos = 0
            for (c in path) {
                if (c.rec < 0) {
                    val w = words[pos].w
                    val x = if (o.fallback == Fallback.PHONES || o.fallback == Fallback.AUTO) splice(bank, w) else null
                    if (x != null) units += Unit(listOf(words[pos]), x, Source.SPLICED, mutableListOf(x.size))
                    else { needNeural += units.size to pos; units += Unit(listOf(words[pos]), FloatArray(0), Source.NEURAL, mutableListOf()) }
                } else {
                    val rec = bank.recs[c.rec]
                    val ws = rec.words.subList(c.word, c.word + c.len)
                    val s = max(0, ws.first().s - MARGIN); val e = min(rec.audio.size, ws.last().e + MARGIN)
                    val lens = ws.map { it.e - it.s }.toMutableList()
                    lens[0] += MARGIN; lens[lens.size - 1] += MARGIN
                    units += Unit(words.subList(pos, pos + c.len), rec.audio.copyOfRange(s, e), Source.REAL, lens)
                }
                pos += c.len
            }
            if (needNeural.isNotEmpty()) {
                val got = if (o.fallback == Fallback.NEURAL || o.fallback == Fallback.AUTO)
                    try { neural(bank, words.map { it.w }, needNeural.map { it.second }) } catch (e: Throwable) { null } else null
                for ((ui, p) in needNeural) {
                    val x = got?.get(p)
                    val old = units[ui]
                    units[ui] = if (x != null && x.isNotEmpty()) Unit(old.words, x, Source.NEURAL, mutableListOf(x.size))
                    else FloatArray(((if (o.fallback == Fallback.SILENT) 0.06 else 0.25) * Dsp.SR).toInt())
                        .let { Unit(old.words, it, Source.MISSING, mutableListOf(it.size)) }
                }
            }
            units.forEach { u -> u.words.forEach { sources += it.w to u.source } }
            seams += max(0, units.size - 1)
            val refw = referenceProsody(words.map { it.w }, end, refSid)
            val audios = if (o.prosody > 0 || o.timing > 0) applyProsody(bank, units, refw, o, end) else units.map { it.audio }
            var phrase: FloatArray? = null
            audios.forEachIndexed { k, a0 ->
                var a = a0
                if (o.matchLoudness && a.isNotEmpty()) {
                    val target = bank.loudMedian - 1.5 * pi / max(1, phrases.size - 1) - 1.0 * k / max(1, audios.size)
                    a = Dsp.scale(a, 10.0.pow((target - Dsp.loudness(a)) / 20))
                }
                phrase = phrase?.let { Dsp.crossfade(it, a, minOf(XFADE, a.size / 4, it.size / 4)) } ?: a
            }
            val ph = phrase ?: FloatArray(0)
            Dsp.fadeEdges(ph, (0.005 * Dsp.SR).toInt())
            pieces += ph
            val gap = if (pi < phrases.size - 1) PAUSE_S[end] ?: 0.3 else 0.15
            pieces += if (o.roomTone) Dsp.roomTone(toneSrc, gap, pi) else FloatArray((gap * Dsp.SR).toInt())
        }
        var out = FloatArray(pieces.sumOf { it.size })
        var p = 0
        for (x in pieces) { System.arraycopy(x, 0, out, p, x.size); p += x.size }
        val peak = out.maxOfOrNull { abs(it) } ?: 0f
        if (peak > 0.95f) out = Dsp.scale(out, 0.95 / peak)
        return out to Report(sources, seams)
    }
}
