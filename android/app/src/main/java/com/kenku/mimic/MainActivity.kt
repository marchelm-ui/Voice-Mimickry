package com.kenku.mimic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** App shell: loads the engine, then shows the Speak and Voices tabs. */
class MainActivity : AppCompatActivity() {
    lateinit var ui: Ui
    lateinit var engine: Engine
    val main = Handler(Looper.getMainLooper())
    val prefs by lazy { getSharedPreferences("mimic", MODE_PRIVATE) }
    private lateinit var speak: SpeakTab
    private lateinit var voicesTab: VoicesTab
    private lateinit var soundsTab: SoundsTab
    val sfx by lazy { SfxPlayer(this) }
    private val tabViews = ArrayList<View>()
    private val tabButtons = ArrayList<TextView>()
    private lateinit var status: TextView
    private var micCallback: ((Boolean) -> Unit)? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micCallback?.invoke(ok); micCallback = null
    }
    val alive get() = !isFinishing && !isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCatcher()
        ui = Ui(this)
        engine = Engine.get(this)
        speak = SpeakTab(this)
        voicesTab = VoicesTab(this)
        soundsTab = SoundsTab(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.c(R.color.bg))
            fitsSystemWindows = true
        }
        status = ui.body("", 13f).apply { setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6)); visibility = View.GONE }
        root.addView(status)
        val content = FrameLayout(this)
        for (v in listOf(speak.view, voicesTab.view, soundsTab.view)) { content.addView(v, FrameLayout.LayoutParams(-1, -1)); tabViews += v }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(ui.c(R.color.line)) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
        val bar = ui.row(Gravity.CENTER).apply { setBackgroundColor(ui.c(R.color.surface)) }
        listOf("🗣️  Speak", "🦜  Voices", "🔔  Sounds").forEachIndexed { i, label ->
            val b = TextView(this).apply {
                text = label; textSize = 16f; gravity = Gravity.CENTER
                setPadding(0, ui.dp(14), 0, ui.dp(14))
                setOnClickListener { show(i) }
            }
            tabButtons += b
            bar.addView(b, ui.weight())
        }
        root.addView(bar)
        setContentView(root)
        show(if (prefs.getBoolean("hasVoice", false)) 0 else 1)
        loadEngine()
    }

    fun show(i: Int) {
        tabViews.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { k, b ->
            b.setTextColor(ui.c(if (k == i) R.color.accent else R.color.muted))
            b.setBackgroundColor(if (k == i) ui.c(R.color.tab_on) else 0)
        }
        when (i) { 0 -> speak.refresh(); 1 -> voicesTab.refresh(); else -> soundsTab.refresh() }
    }

    /** Called from the Sounds tab: drop a [tag] into the line being typed. */
    fun insertIntoSpeak(text: String) {
        show(0)
        speak.insert(text)
    }

    private fun loadEngine() {
        if (engine.ready) { onReady(); return }
        setStatus("Getting ready…")
        Engine.thread.execute {
            try {
                engine.load { msg -> main.post { setStatus(msg) } }
                main.post { onReady() }
            } catch (e: Throwable) {
                main.post { setStatus("Couldn't load the voice tools: ${e.message}") }
            }
        }
    }

    private fun onReady() {
        setStatus(null)
        speak.refresh(); voicesTab.refresh()
    }

    fun setStatus(msg: String?) {
        status.text = msg ?: ""
        status.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    fun withMic(then: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { then(); return }
        micCallback = { ok -> if (ok) then() else toast("Recording needs microphone permission.") }
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** If the app ever dies, the next launch offers the report — the loop can't fix what it can't see. */
    private fun installCrashCatcher() {
        val crashFile = java.io.File(filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (x: Throwable) { "?" }
                crashFile.writeText("Voice Mimickry crash · ${android.os.Build.MODEL} · app $ver\n" +
                    android.util.Log.getStackTraceString(e))
            } catch (x: Throwable) { /* nothing to be done */ }
            previous?.uncaughtException(t, e)
        }
        if (crashFile.exists()) {
            val report = try { crashFile.readText() } catch (e: Throwable) { "" }
            crashFile.delete()
            if (report.isNotBlank()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("The app crashed last time")
                    .setMessage("Want to send the crash report so it can be fixed? It holds only the error and your phone model.")
                    .setPositiveButton("Send") { _, _ ->
                        startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Voice Mimickry crash report")
                            putExtra(android.content.Intent.EXTRA_TEXT, report)
                        }, "Send crash report"))
                    }
                    .setNegativeButton("Not now", null)
                    .show()
            }
        }
    }

    /** Run on the engine thread, then deliver the result on screen. */
    fun <T> work(job: () -> T, done: (Result<T>) -> Unit) {
        Engine.thread.execute {
            val r = runCatching(job)
            main.post { if (alive) done(r) }
        }
    }

    override fun onStop() { super.onStop(); AudioIO.stop() }

    override fun onDestroy() { super.onDestroy(); sfx.release() }
}
