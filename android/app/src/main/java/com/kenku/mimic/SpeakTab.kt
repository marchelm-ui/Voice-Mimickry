package com.kenku.mimic

import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.kenku.mimic.engine.Dsp
import com.kenku.mimic.engine.Synth
import com.kenku.mimic.engine.TextNorm
import com.kenku.mimic.engine.VoiceBank
import java.io.File

/** Speak: type a line, pick whose voice, and the kenku says it with what it has heard. */
class SpeakTab(private val a: MainActivity) {
    private val ui = a.ui
    private var voice: VoiceBank? = null
    private var mode = Synth.Fallback.AUTO
    private var how = "mimic"      // "mimic": frame-level mimicry; "stitch": word stitching
    private var effect = a.prefs.getString("effect", "none") ?: "none"
    private var rotate = a.prefs.getString("rotate", "off") ?: "off"
    private val howRow = ui.row()
    private val voiceRow = ui.row()
    private val modeRow = ui.row()
    private val effectRow = ui.row()
    private val rotateRow = ui.row()
    private val quickWrap = ui.column()
    private val quickRow = ui.row()
    private val input = EditText(a).apply {
        hint = "What should your kenku say?"
        textSize = 18f
        imeOptions = EditorInfo.IME_ACTION_GO
        setSingleLine(false); maxLines = 4
        setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { say(); true } else false
        }
    }
    private val result = ui.body("", 17f, R.color.ink)
    private val legend = ui.body("", 12f)
    private val history = ui.column()
    private class Line(val text: String, val voice: String, val audio: FloatArray, val shown: CharSequence, var pinId: String? = null)
    private val lines = ArrayList<Line>()
    private class OldLine(val text: String, val voice: String)
    private val oldLines = ArrayList<OldLine>()          // lines from earlier sessions: text only

    private fun loadOldLines() {
        try {
            val arr = org.json.JSONArray(a.prefs.getString("history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                oldLines += OldLine(o.getString("t"), o.getString("v"))
            }
        } catch (e: Throwable) { /* no history yet */ }
    }

    private fun saveHistory() {
        val arr = org.json.JSONArray()
        for (l in lines) arr.put(org.json.JSONObject().put("t", l.text).put("v", l.voice))
        for (o in oldLines.take(30 - lines.size)) arr.put(org.json.JSONObject().put("t", o.text).put("v", o.voice))
        a.prefs.edit().putString("history", arr.toString()).apply()
    }

    /** A pinned line: kept on disk so quick lines survive restarts. */
    private class Pin(val id: String, val text: String, val voice: String)
    private val pins = ArrayList<Pin>()
    private val pinDir get() = java.io.File(a.filesDir, "pins").apply { mkdirs() }

    private fun loadPins() {
        pins.clear()
        try {
            val arr = org.json.JSONArray(java.io.File(pinDir, "index.json").readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = Pin(o.getString("id"), o.getString("text"), o.getString("voice"))
                if (java.io.File(pinDir, p.id + ".wav").exists()) pins += p
            }
        } catch (e: Throwable) { /* nothing pinned yet */ }
    }

    private fun savePins() {
        val arr = org.json.JSONArray()
        pins.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("text", it.text).put("voice", it.voice)) }
        java.io.File(pinDir, "index.json").writeText(arr.toString())
    }

    private fun pin(l: Line) {
        val id = "pin" + System.currentTimeMillis()
        AudioIO.writeWav(java.io.File(pinDir, "$id.wav"), l.audio)
        pins.add(0, Pin(id, l.text, l.voice))
        l.pinId = id
        savePins()
        renderQuick(); drawHistory()
    }

    private fun unpin(id: String) {
        java.io.File(pinDir, "$id.wav").delete()
        pins.removeAll { it.id == id }
        lines.forEach { if (it.pinId == id) it.pinId = null }
        savePins()
        renderQuick(); drawHistory()
    }

    val view: View = ScrollView(a).apply {
        addView(ui.column().apply {
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24))
            addView(ui.title("Speak"))
            quickWrap.visibility = View.GONE
            quickWrap.addView(ui.body("⚡ Quick lines", 12f, R.color.brass), ui.fill(4))
            quickWrap.addView(HorizontalScrollView(a).apply { addView(quickRow); isHorizontalScrollBarEnabled = false }, ui.fill(8))
            addView(quickWrap)
            addView(ui.body("Whose voice?"), ui.fill(4))
            addView(HorizontalScrollView(a).apply { addView(voiceRow); isHorizontalScrollBarEnabled = false }, ui.fill(10))
            addView(input, ui.fill(8))
            val sayRow = ui.row()
            sayRow.addView(ui.button("🦜  Say it", Ui.Kind.PRIMARY) { say() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(8) })
            sayRow.addView(ui.button("＋ Add") { addMenu() }, ui.wrap())
            sayRow.addView(ui.button("■", Ui.Kind.QUIET) { AudioIO.stop() }.apply { contentDescription = "Stop speaking" }, ui.wrap(0))
            addView(sayRow, ui.fill(10))
            addView(ui.body("Drop sounds into a line with brackets: Pay up [coins] or else."), ui.fill(8))
            addView(ui.body("How to mimic:"), ui.fill(4))
            addView(HorizontalScrollView(a).apply { addView(howRow); isHorizontalScrollBarEnabled = false }, ui.fill(8))
            addView(ui.body("Voice effect:"), ui.fill(4))
            addView(HorizontalScrollView(a).apply { addView(effectRow); isHorizontalScrollBarEnabled = false }, ui.fill(8))
            addView(ui.body("Voice changes (a kenku speaks in every voice it knows):"), ui.fill(4))
            addView(HorizontalScrollView(a).apply { addView(rotateRow); isHorizontalScrollBarEnabled = false }, ui.fill(8))
            addView(ui.body("When stitching, for words they never said:"), ui.fill(4))
            addView(HorizontalScrollView(a).apply { addView(modeRow); isHorizontalScrollBarEnabled = false }, ui.fill(12))
            addView(result, ui.fill(4))
            addView(legend, ui.fill(16))
            addView(ui.heading("Said before"), ui.fill(6))
            addView(history)
        })
        loadPins()
        loadOldLines()
        a.main.post { renderQuick(); drawHistory() }
    }

    /** Insert text (a [sound tag] or phrase) at the cursor. */
    fun insert(text: String) {
        val pos = input.selectionStart.coerceAtLeast(0)
        val cur = input.text.toString()
        val pad = if (pos > 0 && !cur[pos - 1].isWhitespace()) " " else ""
        input.text.insert(pos, "$pad$text ")
        input.requestFocus()
    }

    /** The ＋ Add button: a ready-made phrase or a sound effect. */
    private fun addMenu() {
        android.app.AlertDialog.Builder(a)
            .setItems(arrayOf("📜  A phrase from the phrasebook", "🔔  A sound effect")) { _, i -> if (i == 0) pickPhrase() else pickSound() }
            .show()
    }

    private fun pickPhrase() {
        val sections = Phrasebook.sections
        android.app.AlertDialog.Builder(a).setTitle("📜 Phrasebook")
            .setItems(sections.map { it.first }.toTypedArray()) { _, i ->
                val (title, phrases) = sections[i]
                android.app.AlertDialog.Builder(a).setTitle(title)
                    .setItems(phrases.map { "“$it”" }.toTypedArray()) { _, j -> insert(phrases[j]) }
                    .setNegativeButton("Back") { _, _ -> pickPhrase() }
                    .show()
            }.show()
    }

    /** Pick a category (any sound from it) or a specific sound; inserts a [tag] at the cursor. */
    private fun pickSound() {
        val cats = listOf(SfxCategory(MimicStore.CATEGORY, "My Mimicry", "🦜", "", MimicStore.list(a))) + SfxLibrary.categories(a)
        android.app.AlertDialog.Builder(a).setTitle("🔔 Sounds")
            .setItems(cats.map { "${it.icon} ${it.title}" }.toTypedArray()) { _, i ->
                val c = cats[i]
                if (c.sounds.isEmpty()) { a.toast("Nothing recorded there yet."); return@setItems }
                val labels = listOf("🎲 Any ${c.title} sound") + c.sounds.map { it.label }
                android.app.AlertDialog.Builder(a).setTitle("${c.icon} ${c.title}")
                    .setItems(labels.toTypedArray()) { _, j -> insert("[${if (j == 0) c.key else c.sounds[j - 1].label}]") }
                    .setNegativeButton("Back") { _, _ -> pickSound() }
                    .show()
            }.show()
    }

    private fun renderQuick() {
        quickRow.removeAllViews()
        quickWrap.visibility = if (pins.isEmpty()) View.GONE else View.VISIBLE
        for (p in pins) {
            val label = if (p.text.length > 24) p.text.take(23) + "…" else p.text
            quickRow.addView(ui.button("▶ $label") {
                a.work({ AudioIO.decodeFile(java.io.File(pinDir, p.id + ".wav").absolutePath) }) { r ->
                    r.onSuccess { AudioIO.play(it) }
                }
            }.apply { setOnLongClickListener { unpin(p.id); true } }, ui.wrap())
        }
    }

    fun refresh() {
        voiceRow.removeAllViews()
        val vs = if (a.engine.ready) a.engine.voices else emptyList()
        if (voice !in vs) voice = vs.firstOrNull()
        if (vs.isEmpty()) voiceRow.addView(ui.body(if (a.engine.ready) "Add a voice in the Voices tab first." else "Loading…"))
        for (v in vs) voiceRow.addView(chip("🦜 ${v.name}", v === voice) { voice = v; refresh() }, ui.wrap())
        howRow.removeAllViews()
        listOf("mimic" to "Every sound (smooth)", "stitch" to "Their exact words")
            .forEach { (h, label) -> howRow.addView(chip(label, h == how) { how = h; refresh() }, ui.wrap()) }
        effectRow.removeAllViews()
        LineEffect.choices.forEach { (k, label) ->
            effectRow.addView(chip(label, k == effect) { effect = k; a.prefs.edit().putString("effect", k).apply(); refresh() }, ui.wrap())
        }
        rotateRow.removeAllViews()
        listOf("off" to "One voice", "sentence" to "Each sentence", "w3" to "Every 3 words", "w1" to "Every word")
            .forEach { (k, label) -> rotateRow.addView(chip(label, k == rotate) { rotate = k; a.prefs.edit().putString("rotate", k).apply(); refresh() }, ui.wrap()) }
        modeRow.removeAllViews()
        listOf(Synth.Fallback.AUTO to "Fill gaps", Synth.Fallback.PHONES to "Their sounds only", Synth.Fallback.SILENT to "Skip them")
            .forEach { (m, label) -> modeRow.addView(chip(label, m == mode) { mode = m; refresh() }, ui.wrap()) }
    }

    private fun chip(label: String, on: Boolean, click: () -> Unit) =
        ui.button(label, if (on) Ui.Kind.PRIMARY else Ui.Kind.NORMAL, click)

    private var speaking = false

    private fun say() {
        val v = voice ?: run { a.toast("Add a voice first."); return }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!a.engine.ready) { a.toast("Still unpacking the voice tools — one moment."); return }
        if (speaking) { a.toast("Still speaking — one moment."); return }
        speaking = true
        ui.tick(input)
        a.setStatus("Mimicking ${v.name}…")
        val opts = Synth.Opts(fallback = mode)
        val fxKey = effect
        val rotateKey = rotate
        val t0 = System.nanoTime()
        val firstSound = longArrayOf(0L)                           // when the first piece reached the speaker
        val player = AudioIO.openStream()                          // starts sounding at the first finished piece
        a.work({
            val dspSr = Dsp.SR
            // the voices that take turns when voice changes are on; the chosen voice leads
            val cast = if (rotateKey == "off") listOf(v)
                else listOf(v) + a.engine.voices.filter { it !== v && it.recs.isNotEmpty() }
            var castAt = 0
            val gap = FloatArray(dspSr / 8)                         // 125 ms: the deliberate cut
            val xf = dspSr * 40 / 1000                              // 40 ms: the fluid join
            val fx = LineEffect(fxKey)
            val clear = Clarity()
            val pieces = ArrayList<FloatArray>()
            val words = ArrayList<Pair<String, Synth.Source>>()
            var seams = 0
            var spoke = false
            var held: FloatArray? = null                            // the last piece's tail, kept for a crossfade
            var loudTarget = 0.0
            fun push(y: FloatArray) {
                if (y.isEmpty()) return
                pieces += y; player.feed(y)
                if (firstSound[0] == 0L) firstSound[0] = System.nanoTime()
            }
            fun flush() { held?.let { push(fx.process(it)) }; held = null }
            /** A spoken piece. [fluid] crossfades into what came before; otherwise a clean cut. */
            fun emitSpeech(raw: FloatArray, fluid: Boolean) {
                if (raw.isEmpty()) return
                // every voice levels to its own loudness; one line needs one level
                val loud = Dsp.loudness(raw)
                if (loudTarget == 0.0) loudTarget = loud
                var y = if (loud < -60) raw else Dsp.scale(raw, Math.min(4.0, Math.pow(10.0, (loudTarget - loud) / 20)))
                y = clear.process(y)
                SfxDsp.fadeEdges(y, 4)
                val prev = held
                if (prev != null && fluid) {
                    val n = minOf(xf, prev.size, y.size / 2)
                    val merged = FloatArray(n)
                    for (i in 0 until n) {
                        val g = i / n.toFloat()
                        merged[i] = prev[i] * kotlin.math.cos(g * Math.PI / 2).toFloat() + y[i] * kotlin.math.sin(g * Math.PI / 2).toFloat()
                    }
                    push(fx.process(merged))
                    y = y.copyOfRange(n, y.size)
                } else if (prev != null) {
                    push(fx.process(prev)); push(fx.process(gap.copyOf()))
                } else if (pieces.isNotEmpty()) {
                    push(fx.process(gap.copyOf()))
                }
                if (y.size > xf) { push(fx.process(y.copyOfRange(0, y.size - xf))); held = y.copyOfRange(y.size - xf, y.size) }
                else held = y
            }
            fun speak(chunk: String, speaker: VoiceBank): Pair<FloatArray, Synth.Report> =
                if (how == "mimic") a.engine.sayBest(speaker, chunk, opts) else a.engine.synth.speak(speaker, chunk, opts)
            for (m in Regex("\\[([^\\]]+)\\]|[^\\[]+").findAll(text)) {
                val tag = m.groups[1]?.value
                if (tag != null) {
                    val s = SfxLibrary.find(a, tag) ?: continue
                    val au = try {
                        if (s.isUserFile) AudioIO.decodeFile(s.file) else AudioIO.decodeAsset(a, s.file)
                    } catch (e: Throwable) { continue }
                    flush()
                    if (pieces.isNotEmpty()) push(fx.process(gap.copyOf()))
                    // a bundled sound can be far louder than the voice: pull it toward the line's level
                    var sfxAu = au
                    if (loudTarget != 0.0) {
                        val l = Dsp.loudness(sfxAu)
                        if (l > loudTarget + 6) sfxAu = Dsp.scale(sfxAu, Math.pow(10.0, (loudTarget + 6 - l) / 20))
                    }
                    push(fx.process(SfxDsp.fadeEdges(sfxAu, 4)))
                } else {
                    val part = m.value.trim()
                    if (part.isEmpty()) continue
                    val sentences = Regex("[^.!?…]+[.!?…]*").findAll(part).map { it.value.trim() }
                        .filter { TextNorm.words(it).isNotEmpty() }
                    for (sent in sentences) {
                        // the rotation: whole sentences, or small word-groups spoken by turns
                        val groups = when (rotateKey) {
                            "w1", "w3" -> {
                                val n = if (rotateKey == "w1") 1 else 3
                                TextNorm.words(sent).chunked(n).map { it.joinToString(" ") }
                            }
                            else -> {
                                // long sentences stream in small fluid pieces, so the first sound
                                // arrives in a couple of seconds instead of after the whole sentence
                                val ws = TextNorm.words(sent)
                                if (how == "mimic" && ws.size > 8) {
                                    (listOf(ws.take(4)) + ws.drop(4).chunked(7)).map { it.joinToString(" ") }
                                } else listOf(sent)
                            }
                        }
                        groups.forEachIndexed { gi, chunk ->
                            val speaker = cast[castAt % cast.size]
                            if (cast.size > 1) castAt++
                            val (au, rep) = speak(chunk, speaker)
                            // mid-sentence handoffs flow together; a new sentence is a clean cut
                            emitSpeech(au, fluid = gi > 0)
                            words += rep.words
                            seams += rep.seams
                            spoke = true
                        }
                    }
                }
            }
            flush()
            if (pieces.isEmpty()) { player.cancel(); throw IllegalArgumentException("Nothing to say — no words and no matching sounds.") }
            val tail = fx.tail()
            if (tail.isNotEmpty()) push(tail)
            player.finish()
            val audio = Dsp.concat(pieces)
            audio to Synth.Report(if (spoke) words else TextNorm.words(text).map { it to Synth.Source.NEURAL }, seams)
        }) { r ->
            speaking = false
            a.setStatus(null)
            r.onSuccess { (audio, rep) ->
                val shown = describe(rep)
                result.text = shown
                val first = if (firstSound[0] > 0) (firstSound[0] - t0) / 1e9 else 0.0
                val whole = (System.nanoTime() - t0) / 1e9
                val rescued = player.failed
                if (rescued) AudioIO.play(audio)     // the stream couldn't open: play the finished line whole
                legend.text = "%s\n⏱ first sound %.1f s · whole line %.1f s%s"
                    .format(summary(rep), first, whole, if (rescued) " · played after making (streaming unavailable)" else "")
                lines.add(0, Line(text, v.name, audio, shown))
                if (lines.size > 30) lines.removeAt(lines.size - 1)
                oldLines.removeAll { it.text == text }
                saveHistory()
                drawHistory()
            }.onFailure { player.cancel(); a.toast("Couldn't say that: ${it.message}") }
        }
    }

    private fun describe(rep: Synth.Report): CharSequence {
        val sb = SpannableStringBuilder()
        for ((w, src) in rep.words) {
            val start = sb.length
            sb.append(w.lowercase()).append(' ')
            val color = when (src) {
                Synth.Source.REAL -> R.color.ink
                Synth.Source.SPLICED -> R.color.brass
                Synth.Source.NEURAL -> R.color.accent
                Synth.Source.MISSING -> R.color.danger
            }
            sb.setSpan(ForegroundColorSpan(ui.c(color)), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (src == Synth.Source.MISSING) sb.setSpan(StrikethroughSpan(), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun summary(rep: Synth.Report): String {
        val n = rep.words.size.coerceAtLeast(1)
        val real = rep.words.count { it.second == Synth.Source.REAL }
        if (how == "mimic") return "Every 20 ms of sound comes from their recordings; the flow comes from a natural reading."
        return "${real * 100 / n}% their real words · black = real, gold = built from their sounds, blue = AI patch, red = skipped"
    }

    private fun drawHistory() {
        history.removeAllViews()
        for (l in lines) {
            val c = ui.card()
            c.addView(TextView(a).apply { text = l.shown; textSize = 15f })
            c.addView(ui.body("as ${l.voice}", 12f), ui.fill(6))
            val row = ui.row()
            row.addView(ui.button("▶ Again") { AudioIO.play(l.audio) }, ui.wrap())
            row.addView(ui.button(if (l.pinId == null) "⚡ Pin" else "Unpin") { l.pinId?.let { unpin(it) } ?: pin(l) }, ui.wrap())
            row.addView(ui.button("Edit") { input.setText(l.text); input.setSelection(l.text.length) }, ui.wrap())
            row.addView(ui.button("Share", Ui.Kind.QUIET) { share(l) }, ui.wrap())
            c.addView(row)
            history.addView(c, ui.fill(8))
        }
        // lines from earlier sessions: the text survives, the sound is one tap away
        for (o in oldLines) {
            if (lines.any { it.text == o.text }) continue
            val c = ui.card()
            c.addView(TextView(a).apply { text = o.text.lowercase(); textSize = 15f; setTextColor(ui.c(R.color.muted)) })
            c.addView(ui.body("as ${o.voice} · from last time", 12f), ui.fill(6))
            val row = ui.row()
            row.addView(ui.button("🦜 Say again") { input.setText(o.text); input.setSelection(o.text.length); say() }, ui.wrap())
            row.addView(ui.button("Edit", Ui.Kind.QUIET) { input.setText(o.text); input.setSelection(o.text.length) }, ui.wrap())
            c.addView(row)
            history.addView(c, ui.fill(8))
        }
    }

    private fun share(l: Line) {
        val dir = File(a.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "kenku.wav")
        AudioIO.writeWav(f, l.audio)
        val uri = FileProvider.getUriForFile(a, "com.kenku.mimic.files", f)
        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share line"))
    }
}
