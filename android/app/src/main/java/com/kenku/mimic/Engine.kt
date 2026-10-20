package com.kenku.mimic

import android.content.Context
import com.kenku.mimic.engine.Aligner
import com.kenku.mimic.engine.FrameMimic
import com.kenku.mimic.engine.KokoroRef
import com.kenku.mimic.engine.NeuralClone
import com.kenku.mimic.engine.Recognizer
import com.kenku.mimic.engine.Synth
import com.kenku.mimic.engine.VoiceBank
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the models and the learned voices. Every engine call runs on [thread] (the models are
 * not thread-safe). Model files ship inside the APK and are copied to private storage once,
 * because onnxruntime reads plain files.
 */
class Engine private constructor(private val ctx: Context) {
    lateinit var ref: KokoroRef; private set
    lateinit var aligner: Aligner; private set
    lateinit var synth: Synth; private set
    private var recognizer: Recognizer? = null
    private var clone: NeuralClone? = null
    private var cloneFailed = false
    private var mimic: FrameMimic? = null
    private var mimicFailed = false
    private val pools = HashMap<String, FrameMimic.Pool>()
    @Volatile var ready = false; private set
    val voices = ArrayList<VoiceBank>()
    private val voicesDir get() = File(ctx.filesDir, "voices")

    /** Blocking: copy models on first run, load the reference reader and saved voices. */
    fun load(progress: (String) -> Unit) {
        if (ready) return
        progress("Unpacking voice tools (first launch only)…")
        val kdir = copyAssets("kokoro", KOKORO_FILES)
        copyAssets("asr", ASR_FILES)
        copyAssets("pocket", POCKET_FILES)
        try { copyAssets("knnvc", KNNVC_FILES) } catch (e: Throwable) { mimicFailed = true }
        progress("Loading the reference reader…")
        ref = KokoroRef(kdir)
        aligner = Aligner(ref)
        synth = Synth(ref, aligner) { neural() }
        progress("Loading your voices…")
        voices.clear()
        voicesDir.listFiles()?.sortedBy { it.name }?.forEach { d ->
            try { voices += VoiceBank.load(d) } catch (e: Throwable) { /* skip a damaged voice */ }
        }
        ready = true
    }

    fun recognizer(): Recognizer = recognizer ?: Recognizer(File(ctx.filesDir, "asr")).also { recognizer = it }

    private fun neural(): NeuralClone? {
        if (clone != null || cloneFailed) return clone
        return try { NeuralClone(File(ctx.filesDir, "pocket")).also { clone = it } } catch (e: Throwable) { cloneFailed = true; null }
    }

    /** Frame-level mimicry models ship with the app; loaded on first use (large). */
    fun mimic(): FrameMimic? {
        if (mimic != null || mimicFailed) return mimic
        return try { FrameMimic(File(ctx.filesDir, "knnvc")).also { mimic = it } } catch (e: Throwable) { mimicFailed = true; null }
    }

    /**
     * The voice's sound inventory for frame mimicry, built once per voice and cached on disk.
     * The cache key covers WHICH recordings the voice holds, not just how many, so fixing or
     * swapping a recording rebuilds the pool instead of mimicking from stale sounds.
     */
    fun pool(v: VoiceBank): FrameMimic.Pool? {
        val m = mimic() ?: return null
        val key = "${v.dir.name}-${v.recs.size}-${v.recs.joinToString("|") { it.id }.hashCode()}"
        pools[key]?.let { return it }
        val f = File(v.dir, "pool-$key.f16")
        val p = if (f.exists()) m.loadPool(f) else m.pool(v.recs.map { r ->
            if (r.words.isEmpty()) r.audio else r.audio.copyOfRange(r.words.first().s, r.words.last().e)
        }).also { v.dir.listFiles { x -> x.name.startsWith("pool-") }?.forEach { it.delete() }; m.savePool(it, f) }
        pools.keys.removeAll { it.startsWith(v.dir.name + "-") }   // drop this voice's stale pools
        pools[key] = p
        return p
    }

    /**
     * Say a line by frame-level mimicry: the neural clone (or the stock reader) speaks it naturally,
     * then every 20 ms frame is replaced with the person's own sounds. Null if the models are missing.
     */
    fun sayMimic(v: VoiceBank, line: String): FloatArray? {
        val m = mimic() ?: return null
        val p = pool(v) ?: return null
        // the stock reading guides better than a clone reading (benchmark: 6.2% vs 6.9% misheard,
        // 0.74 vs 0.69 similarity) — every sound is replaced anyway, the guide only sets the flow
        val source = try { ref.read(com.kenku.mimic.engine.TextNorm.words(line), if (v.f0Median > 165) 2 else 11).audio }
            catch (e: Throwable) { neural()?.say(line, v.referenceClip()) ?: return null }
        return m.convert(source, p, v.loudMedian)
    }

    // ------------------------------------------------------------ starter voices

    data class Preset(val id: String, val name: String, val blurb: String, val seconds: Int)

    /** Starter voices shipped in the APK: real public-domain readers and synthetic characters. */
    fun presets(): List<Preset> = try {
        val arr = org.json.JSONArray(ctx.assets.open("presets/index.json").bufferedReader().readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Preset(o.getString("id"), o.getString("name"), o.getString("blurb"), o.optInt("seconds"))
        }
    } catch (e: Throwable) { emptyList() }

    /** Learn a starter voice from its shipped recordings; exact transcripts, so no mishearing. */
    fun installPreset(p: Preset, progress: (String) -> Unit): VoiceBank {
        val meta = org.json.JSONObject(ctx.assets.open("presets/${p.id}/voice.json").bufferedReader().readText())
        val utts = meta.getJSONArray("utts")
        val v = newVoice(p.name)
        try {
            for (i in 0 until utts.length()) {
                progress("Learning ${p.name}… ${i + 1}/${utts.length()}")
                val u = utts.getJSONObject(i)
                val audio = AudioIO.readWavAsset(ctx, "presets/${p.id}/${u.getString("wav")}")
                val words = com.kenku.mimic.engine.TextNorm.words(u.getString("text"))
                if (words.isEmpty()) continue
                val r = VoiceBank.capture("p${p.id}$i", audio, words, 1.0, aligner)
                if (r.words.isNotEmpty()) { v.add(r); v.saveRecording(r) }
            }
        } catch (e: Throwable) {
            deleteVoice(v)
            throw e
        }
        if (v.recs.isEmpty()) { deleteVoice(v); throw IllegalStateException("Couldn't learn ${p.name}.") }
        return v
    }

    /** "Just say it well": frame mimicry first, word stitching as the fallback. */
    fun sayBest(v: VoiceBank, text: String, opts: Synth.Opts = Synth.Opts()): Pair<FloatArray, Synth.Report> {
        val x = sayMimic(v, text)
        if (x != null && x.isNotEmpty()) {
            return x to Synth.Report(com.kenku.mimic.engine.TextNorm.words(text).map { it to Synth.Source.NEURAL }, 0)
        }
        return synth.speak(v, text, opts)
    }

    fun newVoice(name: String): VoiceBank {
        val id = "v" + System.currentTimeMillis()
        val v = VoiceBank(File(voicesDir, id), name)
        v.saveMeta()
        voices += v
        return v
    }

    fun deleteVoice(v: VoiceBank) {
        v.dir.deleteRecursively()
        voices.remove(v)
    }

    private fun copyAssets(dir: String, files: List<String>): File {
        val out = File(ctx.filesDir, dir)
        val marker = File(out, ".copied-$DATA_VERSION")
        if (marker.exists()) return out
        out.deleteRecursively(); out.mkdirs()
        for (f in files) {
            val tmp = File(out, "$f.tmp")
            ctx.assets.open("$dir/$f").use { i -> FileOutputStream(tmp).use { i.copyTo(it, 1 shl 20) } }
            if (!tmp.renameTo(File(out, f))) throw IllegalStateException("Couldn't store $f")
        }
        marker.writeText("ok")
        return out
    }

    companion object {
        const val DATA_VERSION = "2"
        val KOKORO_FILES = listOf(KokoroRef.MODEL, "voices.bin", "tokens.txt", "lexicon-us-en.txt")
        val ASR_FILES = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
        val KNNVC_FILES = listOf("wavlm6.fp16.onnx", "hifigan.fp16.onnx")
        val POCKET_FILES = listOf("lm_flow.int8.onnx", "lm_main.int8.onnx", "encoder.onnx", "decoder.int8.onnx",
            "text_conditioner.onnx", "vocab.json", "token_scores.json")

        @Volatile private var shared: Engine? = null
        fun get(ctx: Context): Engine = shared ?: synchronized(this) { shared ?: Engine(ctx.applicationContext).also { shared = it } }
        val thread: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    }
}
