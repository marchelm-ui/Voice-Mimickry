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

    /** The voice's sound inventory for frame mimicry, built once per voice and cached on disk. */
    fun pool(v: VoiceBank): FrameMimic.Pool? {
        val m = mimic() ?: return null
        pools[v.dir.name]?.let { return it }
        val f = File(v.dir, "pool-${v.recs.size}.f16")
        val p = if (f.exists()) m.loadPool(f) else m.pool(v.recs.map { r ->
            if (r.words.isEmpty()) r.audio else r.audio.copyOfRange(r.words.first().s, r.words.last().e)
        }).also { v.dir.listFiles { x -> x.name.startsWith("pool-") }?.forEach { it.delete() }; m.savePool(it, f) }
        pools[v.dir.name] = p
        return p
    }

    /**
     * Say a line by frame-level mimicry: the neural clone (or the stock reader) speaks it naturally,
     * then every 20 ms frame is replaced with the person's own sounds. Null if the models are missing.
     */
    fun sayMimic(v: VoiceBank, line: String): FloatArray? {
        val m = mimic() ?: return null
        val p = pool(v) ?: return null
        val source = try { neural()?.say(line, v.referenceClip()) } catch (e: Throwable) { null }
            ?: ref.read(com.kenku.mimic.engine.TextNorm.words(line), if (v.f0Median > 165) 2 else 11).audio
        return m.convert(source, p, v.loudMedian)
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
