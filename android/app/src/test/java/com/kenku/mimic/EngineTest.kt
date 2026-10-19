package com.kenku.mimic

import com.kenku.mimic.engine.Dsp
import com.kenku.mimic.engine.Segmenter
import com.kenku.mimic.engine.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Checks the phone engine matches the Python reference engine on the same inputs. */
class EngineTest {
    private fun spoken(s: String) = TextNorm.normalize(s).joinToString(" ") {
        when (it) { is TextNorm.Tok.Word -> it.w; is TextNorm.Tok.Pause -> it.kind }
    }

    @Test fun textMatchesPythonReference() {
        // Expected strings produced by engine/text.py
        assertEquals("PAY TWO THOUSAND FIVE HUNDRED GOLD PIECES , OR ELSE !", spoken("Pay 2,500 gp... or else!"))
        assertEquals("ROLL TWO D SIX PLUS THREE FOR THE THIRD TIME .", spoken("Roll 2d6 + 3 for the 3rd time."))
        assertEquals("NO . WHO'S THERE ?", spoken("No. Who's there?"))
        assertEquals("MISTER SMITH OWES ME TWELVE POINT FIVE SILVER .", spoken("Mr. Smith owes me 12.5 silver"))
        assertEquals("IT'S ONE HUNDRED PERCENT TRUE AND HONEST .", spoken("It's 100% true & honest"))
    }

    private fun tone(f: Double, secs: Double): FloatArray = FloatArray((secs * Dsp.SR).toInt()) { i ->
        val t = i.toDouble() / Dsp.SR
        // a buzzy voice-like tone: several harmonics
        ((1..6).sumOf { h -> sin(2 * PI * f * h * t) / h } * 0.2).toFloat()
    }

    @Test fun pitchTrackerFindsPitch() {
        val f0 = Dsp.f0(tone(180.0, 1.0)).filter { it > 0 }
        assertTrue(f0.size > 100)
        assertTrue(abs(Dsp.median(f0) - 180f) < 4f)
    }

    @Test fun psolaShiftsPitchAndKeepsLength() {
        val x = tone(160.0, 1.0)
        val f0 = Dsp.f0(x)
        val y = Dsp.psola(x, f0, DoubleArray(f0.size) { 1.2 }, DoubleArray(f0.size) { 1.0 })
        assertTrue(abs(y.size - x.size) < Dsp.SR / 50)
        val g = Dsp.median(Dsp.f0(y).filter { it > 0 })
        assertTrue("got $g", abs(g - 192f) < 8f)
        val z = Dsp.psola(x, f0, DoubleArray(f0.size) { 1.0 }, DoubleArray(f0.size) { 1.3 })
        assertTrue(abs(z.size - x.size * 1.3) < Dsp.SR / 20)
    }

    @Test fun segmenterSplitsAtPauses() {
        val x = tone(150.0, 2.0) + FloatArray(Dsp.SR / 2) + tone(150.0, 2.0)
        val segs = Segmenter.split(x)
        assertEquals(2, segs.size)
    }

    @Test fun resampleKeepsDuration() {
        val x = FloatArray(48000) { sin(2 * PI * 440 * it / 48000).toFloat() }
        assertEquals(24000, Dsp.resample(x, 48000, 24000).size)
    }
}
