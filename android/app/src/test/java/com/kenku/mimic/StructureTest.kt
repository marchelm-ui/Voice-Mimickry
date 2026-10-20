package com.kenku.mimic

import com.kenku.mimic.engine.Dsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Structural checks on the app-layer sound machinery: effects, clarity, trims, assembly. */
class StructureTest {

    private fun tone(f: Double, secs: Double, amp: Double = 0.3): FloatArray =
        FloatArray((secs * Dsp.SR).toInt()) { i -> (amp * sin(2 * PI * f * i / Dsp.SR)).toFloat() }

    private fun rms(x: FloatArray): Double = sqrt(x.sumOf { (it * it).toDouble() } / x.size.coerceAtLeast(1))

    // ------------------------------------------------------------ assembly

    @Test fun concatKeepsOrderAndSize() {
        val a = floatArrayOf(1f, 2f); val b = FloatArray(0); val c = floatArrayOf(3f)
        val out = Dsp.concat(listOf(a, b, c))
        assertEquals(3, out.size)
        assertEquals(1f, out[0], 0f); assertEquals(3f, out[2], 0f)
        assertEquals(0, Dsp.concat(emptyList()).size)
    }

    @Test fun threadsIsSane() {
        assertTrue(Dsp.threads() in 2..4)
    }

    @Test fun fadeEdgesSilencesEndsOnly() {
        val x = FloatArray(2400) { 1f }
        SfxDsp.fadeEdges(x, 10)
        assertEquals(0f, x[0], 1e-4f)
        assertEquals(0f, x[x.size - 1], 1e-4f)
        assertEquals(1f, x[x.size / 2], 1e-4f)
    }

    // ------------------------------------------------------------ effects

    @Test fun everyEffectStaysFiniteAndBounded() {
        for ((kind, _) in LineEffect.choices) {
            val fx = LineEffect(kind)
            val y = fx.process(tone(220.0, 2.0, 0.6)) + fx.tail()
            assertTrue("$kind produced no audio", y.isNotEmpty())
            for (v in y) {
                assertTrue("$kind went non-finite", v.isFinite())
                assertTrue("$kind clipped: $v", abs(v) <= 0.981f)
            }
        }
    }

    @Test fun noneEffectIsExactPassthrough() {
        val x = tone(300.0, 0.5)
        val fx = LineEffect("none")
        assertTrue(!fx.active)
        assertTrue(fx.process(x) === x)
        assertEquals(0, fx.tail().size)
    }

    @Test fun effectsCarryStateAcrossStreamedPieces() {
        // a cave echo must ring into the silence that FOLLOWS a loud piece
        val fx = LineEffect("cave")
        fx.process(tone(220.0, 0.5, 0.6))
        val after = fx.process(FloatArray(Dsp.SR / 2))
        assertTrue("echo did not carry across pieces", rms(after) > 0.005)
    }

    @Test fun onlyEchoEffectsRingOn() {
        for ((kind, _) in LineEffect.choices) {
            val fx = LineEffect(kind)
            fx.process(tone(220.0, 0.5, 0.5))
            val t = fx.tail()
            if (kind in setOf("cave", "distant")) assertTrue("$kind should ring on", t.isNotEmpty())
            else assertEquals("$kind should not ring on", 0, t.size)
        }
    }

    @Test fun glitchActuallyGlitches() {
        val fx = LineEffect("glitch")
        val x = tone(220.0, 4.0, 0.5)
        val y = fx.process(x)
        var changed = 0
        for (i in x.indices) if (abs(y[i] - x[i]) > 1e-3f) changed++
        assertTrue("glitch left the signal untouched", changed > Dsp.SR / 20)   // at least 50 ms affected
    }

    // ------------------------------------------------------------ clarity

    @Test fun clarityLiftsPresenceAndCutsRumble() {
        val presence = rms(Clarity().process(tone(3000.0, 1.0))) / rms(tone(3000.0, 1.0))
        val rumble = rms(Clarity().process(tone(40.0, 1.0))) / rms(tone(40.0, 1.0))
        assertTrue("presence not lifted: $presence", presence > 1.1)
        assertTrue("rumble not cut: $rumble", rumble < 0.6)
    }

    @Test fun clarityStaysBounded() {
        val y = Clarity().process(FloatArray(Dsp.SR) { if (it % 2 == 0) 0.97f else -0.97f })
        for (v in y) { assertTrue(v.isFinite()); assertTrue(abs(v) <= 0.981f) }
    }

    // ------------------------------------------------------------ table-recording cleanup

    @Test fun trimDropsSilenceKeepsSpeech() {
        val pad = FloatArray(Dsp.SR)
        val speech = tone(180.0, 1.0, 0.4)
        val trimmed = SfxDsp.trim(pad + speech + pad, 0.015f, 20)
        assertTrue(trimmed.size < speech.size + Dsp.SR / 2)
        assertTrue(trimmed.size >= speech.size - Dsp.SR / 10)
    }

    @Test fun speechSecondsSeparatesToneFromSilence() {
        assertEquals(0f, SfxDsp.speechSeconds(FloatArray(Dsp.SR)), 1e-3f)
        assertTrue(SfxDsp.speechSeconds(tone(180.0, 1.0, 0.4)) > 0.8f)
    }

    @Test fun matchLoudnessRaisesQuietAudio() {
        val quiet = tone(180.0, 1.0, 0.01)
        val levelled = SfxDsp.matchLoudness(quiet.copyOf(), 0.08f)
        assertTrue(rms(levelled) > rms(quiet) * 2)
        for (v in levelled) assertTrue(abs(v) <= 0.981f)
    }
}
