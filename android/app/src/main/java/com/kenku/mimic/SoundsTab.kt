package com.kenku.mimic

import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.kenku.mimic.engine.Dsp

/**
 * Sounds tab (from Mocking Bird): a medieval soundboard. Big category buttons across the top
 * (Doors & Locks, Coins & Treasure, Tavern…), a grid of sounds below. Tap a sound to play it,
 * tap again to stop, hold it for more (loop, favorite). "My Mimicry" holds real sounds you
 * record yourself, just like a kenku.
 */
class SoundsTab(private val act: MainActivity) {
    private val ui = act.ui
    val view: View

    private val categories by lazy { try { SfxLibrary.categories(act) } catch (e: Exception) { emptyList() } }
    private lateinit var chipRow: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var header: TextView
    private lateinit var sub: TextView
    private lateinit var search: EditText
    private lateinit var stopAll: Button
    private val chips = mutableMapOf<String, LinearLayout>()
    private val tiles = mutableMapOf<String, Pair<LinearLayout, TextView>>()
    private var selected = act.prefs.getString("sfx_cat", null)
    private val favorites: MutableSet<String> = (act.prefs.getStringSet("sfx_fav", emptySet()) ?: emptySet()).toMutableSet()

    init {
        view = build()
        act.sfx.volume = act.prefs.getFloat("sfx_vol", 1f)
        act.sfx.onChange = { act.main.post { refreshTiles(); stopAll.visibility = if (act.sfx.anyPlaying) View.VISIBLE else View.GONE } }
        val valid = setOf(FAV, MimicStore.CATEGORY) + categories.map { it.key }
        if (selected == null || selected !in valid) selected = categories.firstOrNull()?.key
        showCategory(selected ?: FAV)
    }

    fun refresh() = showCurrent()

    private fun build(): View {
        val root = ui.column()
        val top = ui.column().apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(4)) }
        val titleRow = ui.row()
        titleRow.addView(ui.title("Sounds"), ui.weight())
        stopAll = ui.button("■ Stop all", Ui.Kind.DANGER) { act.sfx.stopAll() }.apply { visibility = View.GONE }
        titleRow.addView(stopAll)
        top.addView(titleRow)

        val volRow = ui.row().apply { setPadding(0, ui.dp(4), 0, 0) }
        volRow.addView(ui.body("🔉", 18f, R.color.ink))
        volRow.addView(SeekBar(act).apply {
            max = 100
            progress = (act.prefs.getFloat("sfx_vol", 1f) * 100).toInt()
            contentDescription = "Sound effects volume"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { act.sfx.volume = p / 100f }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { act.prefs.edit().putFloat("sfx_vol", act.sfx.volume).apply() }
            })
        }, ui.weight())
        volRow.addView(ui.body("🔊", 18f, R.color.ink))
        top.addView(volRow)

        search = EditText(act).apply {
            id = View.generateViewId()
            hint = "🔍  Search sounds (door, coins, goblin…)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 16f
            setTextColor(ui.c(R.color.ink)); setHintTextColor(ui.c(R.color.muted))
            background = ui.rounded(ui.c(R.color.surface), ui.c(R.color.line))
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim().orEmpty()
                    if (q.isEmpty()) showCategory(selected ?: FAV) else showSearch(q)
                }
            })
        }
        top.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        root.addView(top)

        chipRow = ui.row().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)) }
        addChip(FAV, "⭐", "Favorites")
        addChip(MimicStore.CATEGORY, "🦜", "My Mimicry")
        categories.forEach { addChip(it.key, it.icon, it.title) }
        root.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })

        val listCol = ui.column().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(16)) }
        header = ui.heading("")
        sub = ui.body("", 13f).apply { setPadding(0, ui.dp(2), 0, ui.dp(10)) }
        listCol.addView(header)
        listCol.addView(sub)
        grid = ui.column()
        listCol.addView(grid)
        root.addView(ScrollView(act).apply { addView(listCol); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun addChip(key: String, icon: String, title: String) {
        val chip = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = ui.dp(92)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            isClickable = true; isFocusable = true
            contentDescription = "$title sounds"
            setOnClickListener { v -> ui.tick(v); search.setText(""); selected = key; act.prefs.edit().putString("sfx_cat", key).apply(); showCategory(key) }
        }
        chip.addView(TextView(act).apply { text = icon; textSize = 26f; gravity = Gravity.CENTER })
        chip.addView(TextView(act).apply { text = title; textSize = 13f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; maxLines = 2 })
        chipRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(8) })
        chips[key] = chip
    }

    private fun highlightChip(key: String?) {
        chips.forEach { (k, chip) ->
            val on = k == key
            chip.background = ui.ripple(ui.rounded(ui.c(if (on) R.color.tab_on else R.color.surface), ui.c(if (on) R.color.accent else R.color.line), 14), 14)
            (chip.getChildAt(1) as TextView).setTextColor(ui.c(if (on) R.color.accent else R.color.ink))
        }
    }

    private fun allSounds() = MimicStore.list(act) + categories.flatMap { it.sounds }

    private fun showCategory(key: String) {
        highlightChip(key)
        when (key) {
            FAV -> {
                val favs = allSounds().filter { it.id in favorites }
                header.text = "⭐ Favorites"
                sub.text = if (favs.isEmpty()) "Hold any sound and choose Favorite to keep it here for quick use." else "${favs.size} favorite sounds"
                fill(favs, showCategory = true)
                act.sfx.preload(favs)
            }
            MimicStore.CATEGORY -> {
                val mine = MimicStore.list(act)
                header.text = "🦜 My Mimicry"
                sub.text = "Kenku copy sounds they hear. Record a real sound or voice at the table and replay it, or put it in a line like [${mine.firstOrNull()?.label ?: "your sound name"}]."
                fill(mine, showCategory = false, header = ui.button("● Record a new sound", Ui.Kind.PRIMARY) { recordMimic() }.apply { minHeight = ui.dp(56) })
                act.sfx.preload(mine)
            }
            else -> {
                val cat = categories.firstOrNull { it.key == key } ?: return
                header.text = "${cat.icon} ${cat.title}"
                sub.text = "${cat.desc} · ${cat.sounds.size} sounds · tap to play, hold for loop and more"
                fill(cat.sounds, showCategory = false)
                act.sfx.preload(cat.sounds)
            }
        }
    }

    private fun showSearch(q: String) {
        highlightChip(null)
        val words = q.lowercase().split(Regex("\\s+"))
        val titles = categories.associate { it.key to it.title } + (MimicStore.CATEGORY to "My Mimicry")
        val found = allSounds().filter { s ->
            val hay = (s.label + " " + titles[s.category]).lowercase()
            words.all { hay.contains(it) }
        }
        header.text = "Results for “$q”"
        sub.text = if (found.isEmpty()) "No sounds match. Try a simpler word like door, coin, sword or wolf." else "${found.size} sounds"
        fill(found, showCategory = true)
    }

    private fun fill(sounds: List<Sfx>, showCategory: Boolean, header: View? = null) {
        grid.removeAllViews()
        tiles.clear()
        header?.let { grid.addView(it, ui.fill(12)) }
        val icons = categories.associate { it.key to it.icon } + (MimicStore.CATEGORY to "🦜")
        sounds.chunked(2).forEach { pair ->
            val row = ui.row(Gravity.TOP)
            pair.forEachIndexed { i, s ->
                val tile = tile(s, if (showCategory) icons[s.category] else null)
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) rightMargin = ui.dp(5) else leftMargin = ui.dp(5) })
            }
            if (pair.size == 1) row.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = ui.dp(5) })
            grid.addView(row, ui.fill(10))
        }
        refreshTiles()
    }

    private fun tile(s: Sfx, icon: String?): LinearLayout {
        val t = ui.row(Gravity.CENTER_VERTICAL).apply {
            setPadding(ui.dp(12), ui.dp(10), ui.dp(4), ui.dp(10))
            minimumHeight = ui.dp(64)
            isClickable = true; isFocusable = true
            contentDescription = "Play ${s.label}"
            setOnClickListener { v -> ui.tick(v); act.sfx.toggle(s) }
            setOnLongClickListener { v -> ui.tick(v); menu(s); true }
        }
        val texts = ui.column()
        texts.addView(ui.body((icon?.let { "$it " } ?: "") + s.label, 15f, R.color.ink).apply { setTypeface(typeface, Typeface.BOLD) })
        val info = ui.body("", 12f)
        texts.addView(info)
        t.addView(texts, ui.weight())
        val star = TextView(act).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
            minWidth = ui.dp(44)
            isClickable = true
            text = if (s.id in favorites) "★" else "☆"
            setTextColor(ui.c(R.color.brass))
            contentDescription = "Favorite ${s.label}"
            setOnClickListener { toggleFavorite(s); text = if (s.id in favorites) "★" else "☆" }
        }
        t.addView(star)
        tiles[s.id] = t to info
        return t
    }

    private fun infoText(s: Sfx): String = when {
        act.sfx.isLooping(s.id) -> "🔁 looping · tap to stop"
        act.sfx.isPlaying(s.id) && s.seconds >= 3f -> "▶ playing · tap to stop"
        s.seconds >= 3f -> "%.0f s".format(s.seconds)
        else -> "%.1f s".format(s.seconds)
    }

    private fun refreshTiles() {
        val byId = allSounds().associateBy { it.id }
        tiles.forEach { (id, pair) ->
            val (t, info) = pair
            val on = act.sfx.isPlaying(id)
            t.background = ui.ripple(ui.rounded(ui.c(if (on) R.color.tab_on else R.color.surface), ui.c(if (on) R.color.accent else R.color.line), 12))
            byId[id]?.let { info.text = infoText(it) }
        }
    }

    /** Hold a sound: loop it, favorite it, and for your own recordings rename or delete. */
    private fun menu(s: Sfx) {
        val looping = act.sfx.isLooping(s.id)
        val fav = s.id in favorites
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += (if (looping) "⏹  Stop looping" else "🔁  Loop this sound (great for rain, fire or crowds)") to {
            if (looping) act.sfx.stop(s.id) else { act.sfx.stop(s.id); act.sfx.toggle(s, loop = true) }
        }
        items += (if (fav) "☆  Remove from Favorites" else "★  Add to Favorites") to { toggleFavorite(s); showCurrent() }
        items += "💬  Use in a line: [${s.label}]" to { act.insertIntoSpeak("[${s.label}]") }
        if (s.isUserFile) {
            items += "✏️  Rename" to { renameMimic(s) }
            items += "🗑  Delete" to {
                AlertDialog.Builder(act).setMessage("Delete “${s.label}”?")
                    .setPositiveButton("Delete") { _, _ -> act.sfx.forget(s.id); MimicStore.delete(act, s); favorites.remove(s.id); saveFavs(); showCurrent() }
                    .setNegativeButton("Keep", null).show()
            }
        }
        AlertDialog.Builder(act)
            .setTitle(s.label)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun showCurrent() {
        val q = search.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) showCategory(selected ?: FAV) else showSearch(q)
    }

    private fun toggleFavorite(s: Sfx) {
        if (!favorites.add(s.id)) favorites.remove(s.id)
        saveFavs()
        if (selected == FAV && search.text.isNullOrBlank()) showCategory(FAV)
    }

    private fun saveFavs() { act.prefs.edit().putStringSet("sfx_fav", HashSet(favorites)).apply() }

    // ---------- Mimicry recording ----------

    private fun recordMimic() {
        act.withMic {
            val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
            body.addView(ui.body("Point the phone at the sound and tap Record. Up to 15 seconds. Silence at the start and end is trimmed.", 14f))
            val timer = ui.body("0.0 s", 22f, R.color.ink).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(4)) }
            body.addView(timer, LinearLayout.LayoutParams(-1, -2))
            val meter = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            body.addView(meter, ui.fill(8))
            val recBtn = ui.button("● Record", Ui.Kind.PRIMARY) {}.apply { minHeight = ui.dp(56) }
            body.addView(recBtn, ui.fill(8))
            val name = EditText(act).apply {
                id = View.generateViewId()
                hint = "Name it (e.g. Bram's laugh, tavern door)"
                setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                visibility = View.GONE
            }
            body.addView(name)
            var recorder: AudioIO.Recorder? = null
            var clip: FloatArray? = null
            fun finish() {
                val r = recorder ?: return
                recorder = null
                recBtn.text = "● Record again"; meter.progress = 0
                val trimmed = SfxDsp.trim(r.stop(), 0.015f, 60)
                if (trimmed.size < Dsp.SR / 5 || SfxDsp.speechSeconds(trimmed) < 0.1f) {
                    timer.text = "Too quiet, try again closer"; return
                }
                val c = SfxDsp.fadeEdges(SfxDsp.matchLoudness(trimmed, 0.08f), 10)
                clip = c
                timer.text = "Got %.1f s".format(trimmed.size / Dsp.SR.toFloat())
                name.visibility = View.VISIBLE
                name.requestFocus()
                AudioIO.play(c)
            }
            val dialog = AlertDialog.Builder(act)
                .setTitle("🦜 Record a sound")
                .setView(body)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel") { _, _ -> recorder?.stop(); recorder = null }
                .setOnDismissListener { recorder?.stop(); recorder = null }
                .create()
            recBtn.setOnClickListener {
                if (recorder != null) { finish(); return@setOnClickListener }
                val r = AudioIO.Recorder()
                try { r.start() } catch (e: Throwable) { act.toast(e.message ?: "The microphone wouldn't open."); return@setOnClickListener }
                recorder = r
                recBtn.text = "■ Stop"
                val start = System.currentTimeMillis()
                val tick = object : Runnable {
                    override fun run() {
                        if (recorder !== r) return
                        val sec = (System.currentTimeMillis() - start) / 1000f
                        timer.text = "%.1f s".format(sec)
                        meter.progress = (r.level * 140).toInt().coerceIn(0, 100)
                        if (sec >= 15f) { finish(); return }
                        act.main.postDelayed(this, 100)
                    }
                }
                act.main.post(tick)
            }
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val c = clip ?: run { act.toast("Record a sound first"); return@setOnClickListener }
                    val label = name.text.toString().trim().take(40).ifBlank { "Mimic ${MimicStore.list(act).size + 1}" }
                    try { MimicStore.add(act, label, c); act.toast("Saved “$label”") } catch (e: Exception) { act.toast("Couldn't save the sound") }
                    dialog.dismiss()
                    showCategory(MimicStore.CATEGORY)
                }
            }
            dialog.show()
        }
    }

    private fun renameMimic(s: Sfx) {
        val field = EditText(act).apply { setText(s.label); setSelection(s.label.length); setSingleLine(true) }
        AlertDialog.Builder(act).setTitle("Rename sound")
            .setView(ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(field) })
            .setPositiveButton("Save") { _, _ ->
                val n = field.text.toString().trim().take(40)
                if (n.isNotEmpty()) { MimicStore.rename(act, s, n); showCurrent() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    companion object { const val FAV = "favorites" }
}
