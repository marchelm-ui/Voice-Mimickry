package com.kenku.mimic

import android.app.AlertDialog
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.kenku.mimic.engine.Dsp
import com.kenku.mimic.engine.Segmenter
import com.kenku.mimic.engine.TextNorm
import com.kenku.mimic.engine.VoiceBank

/**
 * Voices: everyone the kenku has listened to. Record someone talking (or import a file);
 * the app works out what was said, cuts it into words and remembers how each one sounded.
 */
class VoicesTab(private val a: MainActivity) {
    private val ui = a.ui
    private val list = ui.column()
    val view: View = ScrollView(a).apply {
        addView(ui.column().apply {
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24))
            addView(ui.title("Voices"))
            addView(ui.body("Record a friend talking for a minute or two (ask first!). The more they say, the more " +
                "words your kenku can repeat in their real voice."), ui.fill(14))
            addView(ui.button("＋  New voice", Ui.Kind.PRIMARY) { newVoice() }, ui.fill(16))
            addView(list)
        })
    }
    private var recorder: AudioIO.Recorder? = null
    private var importTarget: VoiceBank? = null
    private val pickFile = a.registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val v = importTarget ?: return@registerForActivityResult
        if (uri != null) learnFromFile(v, uri)
    }

    fun refresh() {
        list.removeAllViews()
        if (!a.engine.ready) { list.addView(ui.body("Loading…")); return }
        if (a.engine.voices.isEmpty()) list.addView(ui.body("No voices yet."))
        for (v in a.engine.voices) {
            list.addView(card(v), ui.fill(12))
            if (v.recs.any { it.words.isEmpty() }) polish(v)   // resume word-learning after a restart
        }
        val have = a.engine.voices.map { it.name }.toSet()
        val starters = a.engine.presets().filter { it.name !in have }
        if (starters.isNotEmpty()) {
            list.addView(ui.heading("Starter voices"), ui.fill(16))
            list.addView(ui.body("Ready-made voices that come with the app — two real readers and three characters. " +
                "Adding one takes a minute while the kenku listens to it."), ui.fill(8))
            for (p in starters) list.addView(presetCard(p), ui.fill(12))
        }
    }

    private fun presetCard(p: Engine.Preset) = ui.card().apply {
        addView(ui.heading("✨ ${p.name}"))
        addView(ui.body(p.blurb + " · ${p.seconds / 60} min of speech"), ui.fill(8))
        addView(ui.button("＋ Add ${p.name}", Ui.Kind.PRIMARY) { installPreset(p) }, ui.fill(6))
    }

    private val installing = HashSet<String>()

    private fun installPreset(p: Engine.Preset) {
        if (!a.engine.ready) { a.toast("Still unpacking the voice tools — one moment."); return }
        if (!installing.add(p.id)) return
        a.setStatus("Learning ${p.name}…")
        a.work({
            a.engine.installPreset(p) { msg -> a.main.post { a.setStatus(msg) } }
        }) { r ->
            installing.remove(p.id)
            a.setStatus(null)
            r.onSuccess { v ->
                a.prefs.edit().putBoolean("hasVoice", true).apply()
                refresh(); a.toast("${p.name} can speak now — try the Speak tab.")
                polish(v)                                    // word positions arrive in the background
            }.onFailure { a.toast("Couldn't add ${p.name}: ${it.message}") }
        }
    }

    private fun card(v: VoiceBank) = ui.card().apply {
        addView(ui.heading("🦜 ${v.name}"))
        val mins = v.seconds / 60
        val left = v.recs.count { it.words.isEmpty() }
        val state = when {
            v.recs.isEmpty() -> "Nothing heard yet"
            left > 0 -> "%.1f min heard · ready to speak · 🪶 learning words: %d of %d recordings left".format(mins, left, v.recs.size)
            else -> "%.1f min heard · %d words · %d different words".format(mins, v.wordCount, v.vocabulary().size)
        }
        addView(ui.body(state), ui.fill(8))
        val row = ui.row()
        row.addView(ui.button("🎙 Record", Ui.Kind.PRIMARY) { record(v) }, ui.wrap())
        row.addView(ui.button("📂 Import") { importTarget = v; pickFile.launch("audio/*") }, ui.wrap())
        if (v.recs.isNotEmpty()) row.addView(ui.button("▶ Test") { test(v) }, ui.wrap())
        addView(row, ui.fill(6))
        val row2 = ui.row()
        row2.addView(ui.button("📝 Check words") { review(v) }, ui.wrap())
        row2.addView(ui.button("Rename", Ui.Kind.QUIET) { rename(v) }, ui.wrap())
        row2.addView(ui.button("Delete", Ui.Kind.DANGER) { delete(v) }, ui.wrap())
        addView(row2)
    }

    private var testing = false

    /** One tap to hear the voice, no typing: a stock kenku line through frame mimicry. */
    private fun test(v: VoiceBank) {
        if (!a.engine.ready || testing) return
        testing = true
        a.setStatus("Mimicking ${v.name}…")
        val line = listOf("Well met, traveler.", "Who goes there?", "The guards are here!", "Shiny. Want the shiny.").random()
        a.work({ a.engine.sayBest(v, line).first }) { r ->
            testing = false
            a.setStatus(null)
            r.onSuccess { AudioIO.play(it) }.onFailure { a.toast("Couldn't test that voice: ${it.message}") }
        }
    }

    private fun newVoice() {
        val name = EditText(a).apply { hint = "Whose voice? (e.g. Dungeon Master)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        val ok = CheckBox(a).apply { text = "They know and agree that I'm copying their voice." }
        val box = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(name); addView(ok) }
        AlertDialog.Builder(a).setTitle("New voice").setView(box)
            .setPositiveButton("Create") { _, _ ->
                val n = name.text.toString().trim()
                when {
                    n.isEmpty() -> a.toast("Give the voice a name.")
                    !ok.isChecked -> a.toast("Only copy voices of people who agree to it.")
                    else -> { val v = a.engine.newVoice(n); a.prefs.edit().putBoolean("hasVoice", true).apply(); refresh(); record(v) }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun record(v: VoiceBank) {
        if (!a.engine.ready) { a.toast("Still unpacking the voice tools — one moment."); return }
        a.withMic {
            val rec = AudioIO.Recorder()
            recorder = rec
            val meter = TextView(a).apply { textSize = 15f; gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(16)) }
            val dlg = AlertDialog.Builder(a).setTitle("Listening to ${v.name}…")
                .setMessage("Let them talk naturally: stories, table chatter, anything. Tap Done when finished.")
                .setView(meter)
                .setPositiveButton("Done") { _, _ -> finishRecording(v) }
                .setNegativeButton("Cancel") { _, _ -> rec.stop(); recorder = null }
                .setCancelable(false).show()
            try { rec.start() } catch (e: Throwable) {
                recorder = null; dlg.dismiss()
                a.toast(e.message ?: "The microphone wouldn't open.")
                return@withMic
            }
            val start = System.currentTimeMillis()
            val tick = object : Runnable {
                override fun run() {
                    if (recorder !== rec || !dlg.isShowing) return
                    val secs = (System.currentTimeMillis() - start) / 1000
                    val bars = "▮".repeat((rec.level * 20).toInt().coerceIn(0, 20))
                    meter.text = "%d:%02d   %s%s".format(secs / 60, secs % 60, bars, if (rec.clipped) "\n⚠ Too loud: move the phone back" else "")
                    a.main.postDelayed(this, 100)
                }
            }
            a.main.post(tick)
        }
    }

    private fun finishRecording(v: VoiceBank) {
        val rec = recorder ?: return
        recorder = null
        val audio = rec.stop()
        if (audio.size < Dsp.SR) { a.toast("That was too short."); return }
        learn(v, audio)
    }

    private fun learnFromFile(v: VoiceBank, uri: Uri) {
        a.setStatus("Opening the file…")
        a.work({ AudioIO.decode(a, uri) }) { r ->
            r.onSuccess { learn(v, it) }.onFailure { a.setStatus(null); a.toast("Couldn't read that file: ${it.message}") }
        }
    }

    /**
     * Mimic-first learning: store the speech and build the sound pool, which is everything
     * frame mimicry needs — the voice can speak within seconds. What was said (for stitching
     * mode and "Check words") is worked out afterwards in the background.
     */
    private fun learn(v: VoiceBank, audio: FloatArray) {
        a.setStatus("Learning ${v.name}'s voice…")
        a.work({
            val segs = Segmenter.split(audio)
            var n = 0
            for (s in segs) {
                val piece = audio.copyOfRange(s[0], s[1])
                if (piece.size < Dsp.SR / 2) continue
                val r = com.kenku.mimic.engine.Recording("r" + System.nanoTime(), piece, "", emptyList())
                v.add(r); v.saveRecording(r); n++
            }
            if (n > 0) {
                v.hintStats(audio)
                a.engine.pool(v)                       // the voice can mimic the moment this returns
            }
            n
        }) { r ->
            a.setStatus(null)
            r.onSuccess { n ->
                a.toast(if (n == 0) "That was too short or too quiet." else "${v.name} can speak now. Learning their words in the background…")
                refresh()
                if (n > 0) polish(v)
            }.onFailure { a.toast("Something went wrong: ${it.message}") }
        }
    }

    // ---------- background word-learning (recognize + align, one recording at a time) ----------

    private val polishing = HashSet<String>()           // voices being worked through
    private val polishTried = HashSet<String>()         // recordings that yielded no words this session

    /** Work out word positions for any recording that lacks them, without blocking speech. */
    fun polish(v: VoiceBank) {
        if (!polishing.add(v.dir.name)) return
        polishNext(v)
    }

    private fun polishNext(v: VoiceBank) {
        val r = if (v in a.engine.voices) v.recs.firstOrNull { it.words.isEmpty() && it.id !in polishTried } else null
        if (r == null) { polishing.remove(v.dir.name); refresh(); return }
        a.work({
            // shipped starter voices carry exact transcripts; recorded voices get the recognizer
            val typed = r.transcript.isNotBlank()
            val words = if (typed) TextNorm.words(r.transcript) else a.engine.recognizer().words(r.audio)
            if (words.isEmpty()) null
            else VoiceBank.capture(r.id, r.audio, words, if (typed) 1.0 else 0.9, a.engine.aligner)
        }) { res ->
            polishTried.add(r.id)
            res.onSuccess { nr ->
                if (nr != null && nr.words.isNotEmpty()) { v.remove(r.id); v.add(nr); v.saveRecording(nr) }
            }
            if (view.visibility == android.view.View.VISIBLE) refresh()   // live word-learning progress on the cards
            polishNext(v)
        }
    }

    /** Show each recording's transcript so the player can correct misheard words. */
    private fun review(v: VoiceBank) {
        val col = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        val dlg = AlertDialog.Builder(a).setTitle("What ${v.name} said").setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("Close", null).create()
        if (v.recs.isEmpty()) col.addView(ui.body("Nothing recorded yet."))
        for (r in v.recs.toList()) {
            val row = ui.card()
            val shown = if (r.words.isEmpty() && r.transcript.isBlank()) "🪶 (still working out these words…)" else r.transcript.lowercase()
            row.addView(ui.body(shown, 15f, R.color.ink), ui.fill(6))
            val btns = ui.row()
            btns.addView(ui.button("▶") { AudioIO.play(r.audio) }, ui.wrap())
            btns.addView(ui.button("Fix words") { dlg.dismiss(); fix(v, r) }, ui.wrap())
            btns.addView(ui.button("Remove", Ui.Kind.DANGER) { v.remove(r.id); v.saveMeta(); dlg.dismiss(); refresh(); review(v) }, ui.wrap())
            row.addView(btns)
            col.addView(row, ui.fill(8))
        }
        dlg.show()
    }

    private fun fix(v: VoiceBank, r: com.kenku.mimic.engine.Recording) {
        val edit = EditText(a).apply { setText(r.transcript.lowercase()); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        AlertDialog.Builder(a).setTitle("Type exactly what they said").setView(edit)
            .setPositiveButton("Save") { _, _ ->
                val words = TextNorm.words(edit.text.toString())
                if (words.isEmpty()) return@setPositiveButton
                a.setStatus("Re-learning that recording…")
                a.work({ VoiceBank.capture(r.id, r.audio, words, 1.0, a.engine.aligner) }) { res ->
                    a.setStatus(null)
                    res.onSuccess { nr -> v.remove(r.id); v.add(nr); v.saveRecording(nr); refresh() }
                        .onFailure { a.toast("Couldn't re-learn it: ${it.message}") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun rename(v: VoiceBank) {
        val e = EditText(a).apply { setText(v.name) }
        AlertDialog.Builder(a).setTitle("Rename").setView(e).setPositiveButton("Save") { _, _ ->
            val n = e.text.toString().trim(); if (n.isNotEmpty()) { v.name = n; v.saveMeta(); refresh() }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun delete(v: VoiceBank) {
        AlertDialog.Builder(a).setTitle("Delete ${v.name}?")
            .setMessage("This erases every recording of this voice from the phone.")
            .setPositiveButton("Delete") { _, _ -> a.engine.deleteVoice(v); refresh() }
            .setNegativeButton("Keep", null).show()
    }
}
