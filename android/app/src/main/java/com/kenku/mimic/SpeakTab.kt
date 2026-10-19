package com.kenku.mimic

import android.content.Intent
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.kenku.mimic.engine.Synth
import com.kenku.mimic.engine.VoiceBank
import java.io.File

/** Speak: type a line, pick whose voice, and the kenku says it with what it has heard. */
class SpeakTab(private val a: MainActivity) {
    private val ui = a.ui
    private var voice: VoiceBank? = null
    private var mode = Synth.Fallback.AUTO
    private var how = "mimic"      // "mimic": frame-level mimicry; "stitch": word stitching
    private val howRow = ui.row()
    private val voiceRow = ui.row()
    private val modeRow = ui.row()
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
    private class Line(val text: String, val voice: String, val audio: FloatArray, val shown: CharSequence)
    private val lines = ArrayList<Line>()

    val view: View = ScrollView(a).apply {
        addView(ui.column().apply {
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24))
            addView(ui.title("Speak"))
            addView(ui.body("Whose voice?"), ui.fill(4))
            addView(HorizontalScrollView(a).apply { addView(voiceRow); isHorizontalScrollBarEnabled = false }, ui.fill(10))
            addView(input, ui.fill(8))
            addView(ui.button("🦜  Say it", Ui.Kind.PRIMARY) { say() }, ui.fill(10))
            addView(ui.body("How to mimic:"), ui.fill(4))
            addView(HorizontalScrollView(a).apply { addView(howRow); isHorizontalScrollBarEnabled = false }, ui.fill(8))
            addView(ui.body("When stitching, for words they never said:"), ui.fill(4))
            addView(HorizontalScrollView(a).apply { addView(modeRow); isHorizontalScrollBarEnabled = false }, ui.fill(12))
            addView(result, ui.fill(4))
            addView(legend, ui.fill(16))
            addView(ui.heading("Said before"), ui.fill(6))
            addView(history)
        })
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
        modeRow.removeAllViews()
        listOf(Synth.Fallback.AUTO to "Fill gaps", Synth.Fallback.PHONES to "Their sounds only", Synth.Fallback.SILENT to "Skip them")
            .forEach { (m, label) -> modeRow.addView(chip(label, m == mode) { mode = m; refresh() }, ui.wrap()) }
    }

    private fun chip(label: String, on: Boolean, click: () -> Unit) =
        ui.button(label, if (on) Ui.Kind.PRIMARY else Ui.Kind.NORMAL, click)

    private fun say() {
        val v = voice ?: run { a.toast("Add a voice first."); return }
        val text = input.text.toString().trim()
        if (text.isEmpty() || !a.engine.ready) return
        ui.tick(input)
        a.setStatus("Mimicking ${v.name}…")
        val opts = Synth.Opts(fallback = mode)
        a.work({
            if (how == "mimic") {
                val x = a.engine.sayMimic(v, text)
                if (x != null && x.isNotEmpty()) x to Synth.Report(com.kenku.mimic.engine.TextNorm.words(text).map { it to Synth.Source.NEURAL }, 0)
                else a.engine.synth.speak(v, text, opts)
            } else a.engine.synth.speak(v, text, opts)
        }) { r ->
            a.setStatus(null)
            r.onSuccess { (audio, rep) ->
                val shown = describe(rep)
                result.text = shown
                legend.text = summary(rep)
                AudioIO.play(audio)
                lines.add(0, Line(text, v.name, audio, shown))
                if (lines.size > 30) lines.removeAt(lines.size - 1)
                drawHistory()
            }.onFailure { a.toast("Couldn't say that: ${it.message}") }
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
            row.addView(ui.button("Edit") { input.setText(l.text); input.setSelection(l.text.length) }, ui.wrap())
            row.addView(ui.button("Share", Ui.Kind.QUIET) { share(l) }, ui.wrap())
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
