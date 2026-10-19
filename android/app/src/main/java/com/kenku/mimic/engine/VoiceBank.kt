package com.kenku.mimic.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** One word the kenku heard: where it is in its recording, and how it sounded. */
class HeardWord(
    val text: String,
    val s: Int,
    val e: Int,
    val pauseAfter: Double,
    val conf: Double,
    val phones: List<KokoroRef.Phone>,
) {
    var f0Start = 0f; var f0End = 0f; var f0Mean = 0f
    var loud = -30.0
    var mfIn = DoubleArray(13); var mfOut = DoubleArray(13)
    var final = false; var initial = false; var question = false
}

class Recording(val id: String, val audio: FloatArray, val transcript: String, val words: List<HeardWord>)

/**
 * Everything one voice has said, cut into words. Saved under filesDir/voices/<id>/ so a learned
 * voice survives app restarts and updates. Port of engine/bank.py.
 */
class VoiceBank(val dir: File, var name: String) {
    val recs = ArrayList<Recording>()
    val index = HashMap<String, MutableList<IntArray>>()       // word -> [rec, word]
    var f0Median = 0f; var loudMedian = -25.0
    @Volatile var phoneIndex: HashMap<List<String>, MutableList<IntArray>>? = null

    val wordCount get() = recs.sumOf { it.words.size }
    val seconds get() = recs.sumOf { it.audio.size } / Dsp.SR.toDouble()
    fun vocabulary(): Set<String> = index.keys

    fun add(r: Recording) {
        val ri = recs.size
        recs += r
        r.words.forEachIndexed { wi, w -> index.getOrPut(w.text) { ArrayList() }.add(intArrayOf(ri, wi)) }
        refreshStats()
        phoneIndex = null
    }

    fun remove(id: String) {
        val keep = recs.filter { it.id != id }
        recs.clear(); index.clear(); phoneIndex = null
        keep.forEach { add(it) }
        File(dir, "$id.pcm").delete(); File(dir, "$id.json").delete()
    }

    private fun refreshStats() {
        val f0s = recs.flatMap { r -> r.words.filter { it.f0Mean > 0 }.map { it.f0Mean } }
        f0Median = Dsp.median(f0s)
        val ls = recs.flatMap { r -> r.words.map { it.loud } }.sorted()
        loudMedian = if (ls.isEmpty()) -25.0 else ls[ls.size / 2]
    }

    /** A clean stretch of the voice for the neural clone to copy timbre from. */
    fun referenceClip(seconds: Double = 12.0): FloatArray {
        var out = FloatArray(0)
        for (r in recs.sortedByDescending { it.audio.size }) {
            if (r.words.isEmpty()) continue
            out += r.audio.copyOfRange(r.words.first().s, r.words.last().e) + FloatArray(Dsp.SR / 4)
            if (out.size > seconds * Dsp.SR) break
        }
        return if (out.isEmpty()) FloatArray(Dsp.SR) else out
    }

    // ------------------------------------------------------------ capture

    companion object {
        /**
         * Learn from one recording. [words] is the transcript (from the recognizer or typed by the
         * player); [confidence] 1.0 when the player confirmed it.
         */
        fun capture(id: String, audioIn: FloatArray, words: List<String>, confidence: Double, aligner: Aligner): Recording {
            val peak = audioIn.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            val audio = if (peak > 0.89f) Dsp.scale(audioIn, 0.89 / peak) else audioIn
            if (words.isEmpty()) return Recording(id, audio, "", emptyList())
            val f0 = Dsp.f0(audio)
            val al = aligner.align(audio, words)
            val db = Dsp.rmsDb(audio, 240)
            val top = db.sorted()[(db.size * 0.98).toInt().coerceAtMost(db.size - 1)]
            fun fr(s: Int) = (s / Dsp.HOP).coerceIn(0, db.size - 1)
            val out = ArrayList<HeardWord>()
            for (k in words.indices) {
                val s = al.words[k][0]; val e = al.words[k][1]
                val a = fr(s); val b = fr(e)
                val speech = (a until b).filter { db[it] >= top - 35 }
                val eTrim = if (speech.isNotEmpty()) min(e, (speech.last() + 3) * Dsp.HOP) else e
                val sTrim = if (k == 0 && speech.isNotEmpty()) maxOf(s, (speech.first() - 2) * Dsp.HOP) else s
                if (eTrim - sTrim < (0.03 * Dsp.SR).toInt()) continue
                out += HeardWord(words[k], sTrim, min(audio.size, eTrim), (e - eTrim) / Dsp.SR.toDouble(), confidence, al.phones[k])
            }
            describe(audio, f0, out)
            return Recording(id, audio, words.joinToString(" "), out)
        }

        fun describe(audio: FloatArray, f0: FloatArray, ws: List<HeardWord>) {
            val mf = Dsp.mfcc(audio)
            fun mean(rows: List<DoubleArray>): DoubleArray =
                if (rows.isEmpty()) DoubleArray(13) else DoubleArray(13) { k -> rows.sumOf { it[k] } / rows.size }
            ws.forEachIndexed { k, w ->
                val a = (w.s / Dsp.HOP).coerceIn(0, f0.size - 1); val b = (w.e / Dsp.HOP).coerceIn(a + 1, f0.size)
                val seg = f0.copyOfRange(a, b)
                val v = seg.filter { it > 0 }
                w.f0Mean = Dsp.median(v)
                val third = maxOf(1, seg.size / 3)
                w.f0Start = Dsp.median(seg.take(third).filter { it > 0 }).takeIf { it > 0 } ?: w.f0Mean
                w.f0End = Dsp.median(seg.takeLast(third).filter { it > 0 }).takeIf { it > 0 } ?: w.f0Mean
                w.loud = Dsp.loudness(audio.copyOfRange(w.s, w.e))
                val ma = (w.s / Dsp.HOP).coerceIn(0, mf.size - 1); val mb = (w.e / Dsp.HOP).coerceIn(1, mf.size)
                w.mfIn = mean((ma until min(mf.size, ma + 3)).map { mf[it] })
                w.mfOut = mean((maxOf(0, mb - 3) until mb).map { mf[it] })
                w.final = w.pauseAfter > 0.05 || k == ws.size - 1
                w.initial = k == 0 || ws[k - 1].pauseAfter > 0.05
                w.question = w.final && w.f0Start > 0 && w.f0End > 0 && w.f0End / w.f0Start > 1.12f
            }
        }

        // -------------------------------------------------------- persistence

        fun load(dir: File): VoiceBank {
            val meta = JSONObject(File(dir, "voice.json").readText())
            val bank = VoiceBank(dir, meta.getString("name"))
            val ids = meta.getJSONArray("recordings")
            for (i in 0 until ids.length()) {
                val id = ids.getString(i)
                val pcm = File(dir, "$id.pcm"); val js = File(dir, "$id.json")
                if (!pcm.exists() || !js.exists()) continue
                val audio = readPcm(pcm)
                val o = JSONObject(js.readText())
                val wa = o.getJSONArray("words")
                val ws = (0 until wa.length()).map { k ->
                    val w = wa.getJSONObject(k)
                    val ph = w.getJSONArray("phones")
                    HeardWord(w.getString("t"), w.getInt("s"), w.getInt("e"), w.getDouble("p"), w.getDouble("c"),
                        (0 until ph.length()).map { j -> ph.getJSONArray(j).let { KokoroRef.Phone(it.getString(0), it.getInt(1), it.getInt(2)) } })
                }
                describe(audio, Dsp.f0(audio), ws)
                bank.add(Recording(id, audio, o.getString("transcript"), ws))
            }
            return bank
        }

        private fun readPcm(f: File): FloatArray {
            val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(b.remaining()) { b.get(it) / 32768f }
        }
    }

    fun saveMeta() {
        dir.mkdirs()
        File(dir, "voice.json").writeText(JSONObject().put("name", name)
            .put("recordings", JSONArray(recs.map { it.id })).toString())
    }

    fun saveRecording(r: Recording) {
        dir.mkdirs()
        val bb = ByteBuffer.allocate(r.audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in r.audio) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        File(dir, "${r.id}.pcm").writeBytes(bb.array())
        val wa = JSONArray()
        for (w in r.words) {
            wa.put(JSONObject().put("t", w.text).put("s", w.s).put("e", w.e).put("p", w.pauseAfter).put("c", w.conf)
                .put("phones", JSONArray(w.phones.map { JSONArray(listOf(it.ph, it.s, it.e)) })))
        }
        File(dir, "${r.id}.json").writeText(JSONObject().put("transcript", r.transcript).put("words", wa).toString())
        saveMeta()
    }
}
