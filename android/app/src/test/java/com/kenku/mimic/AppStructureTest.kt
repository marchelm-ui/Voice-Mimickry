package com.kenku.mimic

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Structural checks that need a real Android context: every bundled sound must exist and be
 * findable, table recordings must survive a round trip, starter packs must be well-formed.
 */
@RunWith(RobolectricTestRunner::class)
class AppStructureTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun everyBundledSoundExistsAndIsLabelled() {
        val cats = SfxLibrary.categories(ctx)
        assertTrue("sound catalog is empty", cats.isNotEmpty())
        val total = cats.sumOf { it.sounds.size }
        assertTrue("suspiciously few sounds: $total", total > 400)
        val ids = HashSet<String>()
        for (c in cats) {
            assertTrue("category ${c.key} has no title", c.title.isNotBlank())
            for (s in c.sounds) {
                assertTrue("duplicate sound id ${s.id}", ids.add(s.id))
                assertTrue("${s.id} has no label", s.label.isNotBlank())
                assertTrue("${s.id} has a silly duration ${s.seconds}", s.seconds > 0f && s.seconds < 120f)
                ctx.assets.open(s.file).use { }        // throws if the file is missing from the APK
            }
        }
    }

    @Test fun soundTagsFindWhatPlayersType() {
        for (tag in listOf("coins", "door", "sword", "wolf", "magic", "rain")) {
            val s = SfxLibrary.find(ctx, tag)
            assertTrue("nothing found for [$tag]", s != null)
        }
        assertEquals(null, SfxLibrary.find(ctx, "zzzz qqqq"))
    }

    @Test fun tableRecordingsSurviveTheRoundTrip() {
        val clip = FloatArray(com.kenku.mimic.engine.Dsp.SR / 2) { 0.1f }
        val s = MimicStore.add(ctx, "Bram's laugh", clip)
        assertTrue(MimicStore.list(ctx).any { it.id == s.id && it.label == "Bram's laugh" })
        MimicStore.rename(ctx, s, "The laugh")
        assertEquals("The laugh", MimicStore.list(ctx).first { it.id == s.id }.label)
        assertTrue("recorded sounds must be findable as [tags]", SfxLibrary.find(ctx, "the laugh") != null)
        MimicStore.delete(ctx, MimicStore.list(ctx).first { it.id == s.id })
        assertTrue(MimicStore.list(ctx).none { it.id == s.id })
    }

    @Test fun starterPacksAreWellFormedWhenBundled() {
        val index = try { ctx.assets.open("presets/index.json").bufferedReader().readText() } catch (e: Exception) { null }
        assumeTrue("starter packs not bundled in this build", index != null)   // CI downloads them before building
        val arr = org.json.JSONArray(index!!)
        assertTrue(arr.length() >= 3)
        for (i in 0 until arr.length()) {
            val id = arr.getJSONObject(i).getString("id")
            val meta = org.json.JSONObject(ctx.assets.open("presets/$id/voice.json").bufferedReader().readText())
            val utts = meta.getJSONArray("utts")
            assertTrue("$id has no utterances", utts.length() > 0)
            for (j in 0 until utts.length()) {
                val u = utts.getJSONObject(j)
                assertTrue("$id utt $j has no words", u.getString("text").isNotBlank())
                val audio = AudioIO.readWavAsset(ctx, "presets/$id/${u.getString("wav")}")
                assertTrue("$id/${u.getString("wav")} is empty", audio.size > com.kenku.mimic.engine.Dsp.SR / 2)
            }
        }
    }

    @Test fun phrasebookLinesAreSpeakable() {
        for ((section, phrases) in Phrasebook.sections) {
            assertTrue("$section is empty", phrases.isNotEmpty())
            for (p in phrases) assertTrue("'$p' has no speakable words",
                com.kenku.mimic.engine.TextNorm.words(p).isNotEmpty())
        }
    }
}
