package com.kenku.mimic

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import com.kenku.mimic.engine.Dsp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/** A sound effect. [file] is an asset path, or an absolute path for sounds the user recorded. */
data class Sfx(val id: String, val label: String, val file: String, val seconds: Float, val category: String) {
    val isUserFile get() = file.startsWith("/")
}

/** Small cleanups for table recordings (ported from Mocking Bird). */
object SfxDsp {
    fun trim(src: FloatArray, threshold: Float = 0.012f, padMs: Int = 18): FloatArray {
        if (src.isEmpty()) return src
        val win = 120
        fun energetic(i: Int): Boolean {
            var m = 0f
            for (k in i until minOf(i + win, src.size)) m = maxOf(m, abs(src[k]))
            return m > threshold
        }
        var s = 0
        while (s < src.size && !energetic(s)) s += win
        var e = src.size - 1
        while (e > s && !energetic(maxOf(0, e - win))) e -= win
        if (s >= e) return src
        val pad = Dsp.SR * padMs / 1000
        return src.copyOfRange((s - pad).coerceAtLeast(0), (e + pad).coerceAtMost(src.size))
    }

    private fun voicedRms(src: FloatArray): Float {
        val win = 480
        val frames = ArrayList<Float>()
        var i = 0
        while (i < src.size) {
            var sum = 0.0
            val end = minOf(i + win, src.size)
            for (k in i until end) sum += src[k] * src[k]
            frames.add(sqrt(sum / (end - i)).toFloat())
            i += win
        }
        if (frames.isEmpty()) return 0f
        frames.sort()
        // the louder half carries the voice; quiet gaps would drag the average down
        val top = frames.subList(frames.size / 2, frames.size)
        return top.average().toFloat()
    }

    fun matchLoudness(src: FloatArray, target: Float = 0.085f): FloatArray {
        val rms = voicedRms(src)
        if (rms < 1e-4f) return src
        val gain = (target / rms).coerceIn(0.35f, 3.5f)
        return FloatArray(src.size) { i -> softClip(src[i] * gain) }
    }

    private fun softClip(x: Float): Float =
        if (abs(x) < 0.85f) x else (tanh(x.toDouble() * 1.1) / tanh(1.1)).toFloat().coerceIn(-0.98f, 0.98f)

    fun fadeEdges(src: FloatArray, ms: Int = 6): FloatArray {
        val n = minOf(Dsp.SR * ms / 1000, src.size / 2)
        for (i in 0 until n) {
            val g = i.toFloat() / n
            src[i] *= g
            src[src.size - 1 - i] *= g
        }
        return src
    }

    fun speechSeconds(samples: FloatArray): Float {
        val win = 480
        var voiced = 0
        var i = 0
        while (i + win <= samples.size) {
            var sum = 0.0
            for (k in i until i + win) sum += samples[k] * samples[k]
            if (sqrt(sum / win) > 0.02) voiced++
            i += win
        }
        return voiced * win / Dsp.SR.toFloat()
    }
}

/**
 * Kenku Mimicry: real sounds the user records at the table (a laugh, a door, a friend's
 * catchphrase), saved as playable sounds and usable as [tags] in lines.
 */
object MimicStore {
    const val CATEGORY = "mimic"
    private fun dir(ctx: Context) = File(ctx.filesDir, "mimic").apply { mkdirs() }
    private fun index(ctx: Context) = File(dir(ctx), "index.json")

    fun list(ctx: Context): List<Sfx> = try {
        val arr = JSONArray(index(ctx).readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Sfx(o.getString("id"), o.getString("label"), File(dir(ctx), o.getString("id") + ".wav").absolutePath,
                o.optDouble("dur", 1.0).toFloat(), CATEGORY)
        }.filter { File(it.file).exists() }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, items: List<Sfx>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label).put("dur", it.seconds.toDouble())) }
        index(ctx).writeText(arr.toString())
    }

    fun add(ctx: Context, label: String, samples: FloatArray): Sfx {
        val id = "mimic_" + System.currentTimeMillis()
        val f = File(dir(ctx), "$id.wav")
        AudioIO.writeWav(f, samples)
        val s = Sfx(id, label, f.absolutePath, samples.size / Dsp.SR.toFloat(), CATEGORY)
        save(ctx, list(ctx) + s)
        return s
    }

    fun rename(ctx: Context, s: Sfx, label: String) = save(ctx, list(ctx).map { if (it.id == s.id) it.copy(label = label) else it })

    fun delete(ctx: Context, s: Sfx) {
        File(s.file).delete()
        save(ctx, list(ctx).filter { it.id != s.id })
    }
}

data class SfxCategory(val key: String, val title: String, val icon: String, val desc: String, val sounds: List<Sfx>)

/** The medieval sound library bundled in assets/sfx (all CC0). */
object SfxLibrary {
    @Volatile private var cache: List<SfxCategory>? = null

    fun categories(context: Context): List<SfxCategory> = cache ?: synchronized(this) {
        cache ?: load(context).also { cache = it }
    }

    private fun load(context: Context): List<SfxCategory> {
        val json = context.assets.open("sfx/catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("categories")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val key = c.getString("key")
            val s = c.getJSONArray("sounds")
            SfxCategory(key, c.getString("title"), c.getString("icon"), c.getString("desc"),
                (0 until s.length()).map { j ->
                    val o = s.getJSONObject(j)
                    Sfx(o.getString("id"), o.getString("label"), o.getString("file"), o.optDouble("dur", 1.0).toFloat(), key)
                })
        }
    }

    fun all(context: Context) = categories(context).flatMap { it.sounds }

    /**
     * Finds a sound for a tag typed into a line, like [coins], [door creak] or [goblin].
     * Matches sound names first, then whole categories; picks one at random so repeats vary.
     */
    fun find(context: Context, tag: String): Sfx? {
        val words = tag.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
            .map { it.removeSuffix("s").ifEmpty { it } }
        if (words.isEmpty()) return null
        val cats = listOf(SfxCategory(MimicStore.CATEGORY, "My Mimicry", "🦜", "", MimicStore.list(context))) + categories(context)
        val exact = cats.flatMap { it.sounds }.filter { s -> s.label.lowercase().removeSuffix("s").trim() == tag.lowercase().removeSuffix("s").trim() }
        if (exact.isNotEmpty()) return exact.random()
        val byName = cats.flatMap { it.sounds }.filter { s -> val l = s.label.lowercase(); words.all { l.contains(it) } }
        if (byName.isNotEmpty()) return byName.random()
        val byCat = cats.filter { c -> val t = (c.key + " " + c.title + " " + c.desc).lowercase(); words.all { t.contains(it) } }
        if (byCat.isNotEmpty()) return byCat.random().sounds.randomOrNull()
        val loose = cats.flatMap { it.sounds }.filter { s -> val l = s.label.lowercase(); words.any { l.contains(it) } }
        return loose.randomOrNull()
    }
}

/**
 * Plays sound effects; several can overlap, any can be stopped, and any can loop.
 * Short sounds go through SoundPool, which keeps them decoded in memory so a tap plays
 * with almost no delay. Long sounds and loops stream through MediaPlayer.
 */
class SfxPlayer(private val context: Context) {
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val pool = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(attrs).build()
    private val handler = Handler(Looper.getMainLooper())

    private val loaded = HashMap<String, Int>()        // sound id -> SoundPool sample id
    private val ready = HashSet<Int>()                  // sample ids finished loading
    private val pendingPlay = HashMap<Int, Sfx>()       // sample id -> sound to play once loaded
    private val poolStreams = HashMap<String, Int>()    // sound id -> playing stream id
    private val players = LinkedHashMap<String, MediaPlayer>()
    private val looping = HashSet<String>()

    var onChange: (() -> Unit)? = null
    /** 0.0–1.0, applied to new and playing sounds. */
    var volume = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            poolStreams.values.forEach { pool.setVolume(it, field, field) }
            players.values.forEach { try { it.setVolume(field, field) } catch (e: Exception) {} }
        }

    init {
        pool.setOnLoadCompleteListener { _, sample, status ->
            if (status == 0) ready.add(sample)
            pendingPlay.remove(sample)?.let { if (status == 0) startPool(it, sample) }
        }
    }

    fun isPlaying(id: String) = poolStreams.containsKey(id) || players.containsKey(id)
    fun isLooping(id: String) = id in looping
    val anyPlaying get() = poolStreams.isNotEmpty() || players.isNotEmpty()

    private fun usesPool(s: Sfx, loop: Boolean) = !loop && s.seconds <= 5f

    /** Decodes short sounds ahead of time (e.g. the category on screen) so the first tap is instant. */
    fun preload(sounds: List<Sfx>) {
        sounds.filter { usesPool(it, false) && it.id !in loaded }.forEach { s ->
            try { loaded[s.id] = load(s) } catch (e: Exception) {}
        }
    }

    /** Starts the sound, or stops it if it is already playing (so long sounds can be cut off). */
    fun toggle(s: Sfx, loop: Boolean = false) {
        if (isPlaying(s.id)) { stop(s.id); return }
        if (poolStreams.size + players.size >= 10) (players.keys.firstOrNull() ?: poolStreams.keys.firstOrNull())?.let { stop(it) }
        if (usesPool(s, loop)) {
            val sample = loaded[s.id] ?: try { load(s).also { loaded[s.id] = it } } catch (e: Exception) { return }
            if (sample in ready) startPool(s, sample) else pendingPlay[sample] = s
        } else {
            startPlayer(s, loop)
        }
    }

    private fun load(s: Sfx): Int =
        if (s.isUserFile) pool.load(s.file, 1) else context.assets.openFd(s.file).use { fd -> pool.load(fd, 1) }

    /** Forget a cached sound (after the user deletes or re-records it). */
    fun forget(id: String) { stop(id); loaded.remove(id)?.let { pool.unload(it); ready.remove(it) } }

    private fun startPool(s: Sfx, sample: Int) {
        val stream = pool.play(sample, volume, volume, 1, 0, 1f)
        if (stream == 0) return
        poolStreams[s.id] = stream
        onChange?.invoke()
        handler.postDelayed({
            if (poolStreams[s.id] == stream) { poolStreams.remove(s.id); onChange?.invoke() }
        }, (s.seconds * 1000).toLong() + 120)
    }

    private fun startPlayer(s: Sfx, loop: Boolean) {
        val mp = try {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                if (s.isUserFile) setDataSource(s.file)
                else context.assets.openFd(s.file).use { fd -> setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                isLooping = loop
                setVolume(volume, volume)
                prepare()
            }
        } catch (e: Exception) { null } ?: return
        mp.setOnCompletionListener { done ->
            if (players[s.id] === done) { players.remove(s.id); looping.remove(s.id) }
            release(done); onChange?.invoke()
        }
        players[s.id] = mp
        if (loop) looping.add(s.id)
        mp.start()
        onChange?.invoke()
    }

    fun stop(id: String) {
        poolStreams.remove(id)?.let { pool.stop(it) }
        players.remove(id)?.let { release(it) }
        looping.remove(id)
        onChange?.invoke()
    }

    fun stopAll() {
        poolStreams.values.forEach { pool.stop(it) }
        poolStreams.clear()
        players.values.forEach { release(it) }
        players.clear()
        looping.clear()
        onChange?.invoke()
    }

    fun release() { stopAll(); pool.release() }

    private fun release(mp: MediaPlayer) { try { mp.stop() } catch (e: Exception) {}; try { mp.release() } catch (e: Exception) {} }
}
