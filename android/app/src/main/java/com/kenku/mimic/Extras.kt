package com.kenku.mimic

import com.kenku.mimic.engine.Dsp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** Ready-made kenku lines: things a kenku might have overheard, grouped by situation. */
object Phrasebook {
    val sections: List<Pair<String, List<String>>> = listOf(
        "⚔️ Combat" to listOf(
            "Roll for initiative!", "Behind you!", "Hold the line!", "Fall back, fall back!",
            "For the king!", "Is that all you've got?", "Take the shot!", "Protect the healer!",
            "They're flanking us!", "Get down!", "I'll hold them off. Run!", "Finish him!",
        ),
        "⚠️ Warnings" to listOf(
            "Danger. Run. Run now.", "Someone's coming!", "It's a trap!", "Don't touch that!",
            "The guards are here!", "Stay quiet. Something is listening.", "Not that door!",
            "The floor is moving!", "Too late. They've seen us.", "Hide!",
        ),
        "🍺 Tavern" to listOf(
            "Another round for my friends!", "Barkeep! Your finest ale!", "Who wants to hear a story?",
            "That's my seat.", "Cheers!", "Keep the change.", "Have you heard the rumor about the old mine?",
            "I'll have what he's having.", "Rooms are two silver a night.",
        ),
        "💰 Trading" to listOf(
            "How much for the shiny?", "That's daylight robbery!", "Ten gold. Final offer.",
            "I'll take it.", "Do you take copper?", "Shiny. Want the shiny.", "No refunds.",
            "What a bargain!", "Pleasure doing business.",
        ),
        "👋 Greetings" to listOf(
            "Hello, friend!", "Well met, traveler.", "Good morning!", "Thank you, kind stranger.",
            "Safe travels.", "Farewell!", "Nice to meet you.", "Help! Help! Over here!",
        ),
        "😈 Taunts" to listOf(
            "You call that a sword?", "Missed me!", "Hold still, little bird.", "Is that the best you can do?",
            "Run home to your mother!", "You fight like a farmer.", "Come and get me!",
        ),
        "❓ Questions" to listOf(
            "Who goes there?", "Where is the treasure?", "What was that noise?", "Can you keep a secret?",
            "Are we there yet?", "Which way to the castle?", "Did you hear that?",
        ),
    )
}

/** A stateful filter section, so effects run smoothly across a whole line. */
internal class Biquad(var b0: Double, var b1: Double, var b2: Double, var a1: Double, var a2: Double) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    fun step(v: Double): Double {
        val o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = v; y2 = y1; y1 = o
        return o
    }

    companion object {
        private fun w(fc: Double) = 2 * PI * fc / Dsp.SR
        fun lowPass(fc: Double, q: Double = 0.707): Biquad {
            val w = w(fc); val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
        }
        fun highPass(fc: Double, q: Double = 0.707): Biquad {
            val w = w(fc); val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
        }
        /** RBJ peaking EQ: lift or dip [db] around [fc]. */
        fun peak(fc: Double, db: Double, q: Double = 0.9): Biquad {
            val a = Math.pow(10.0, db / 40); val w = w(fc); val al = sin(w) / (2 * q); val c = cos(w)
            val a0 = 1 + al / a
            return Biquad((1 + al * a) / a0, -2 * c / a0, (1 - al * a) / a0, -2 * c / a0, (1 - al / a) / a0)
        }
    }
}

/**
 * A gentle clarity pass on every spoken piece: rolls off rumble the vocoder sometimes leaves,
 * lifts the 3 kHz presence band ears use to tell consonants apart, and soft-limits the result.
 */
class Clarity {
    private val hp = Biquad.highPass(70.0)
    private val presence = Biquad.peak(3000.0, 2.5)
    fun process(x: FloatArray): FloatArray = FloatArray(x.size) { i ->
        val v = presence.step(hp.step(x[i].toDouble()))
        (if (kotlin.math.abs(v) < 0.9) v else tanh(v * 1.05) / tanh(1.05)).toFloat().coerceIn(-0.98f, 0.98f)
    }
}

/**
 * Optional atmosphere on a whole line:
 *  - Cave echo: a stone-hall echo.
 *  - Distant: muffled, as if heard through a wall or from across a room.
 *  - Old memory: thin and slightly crackly, like a sound the kenku is replaying from long ago.
 *  - Phonograph: a wax-cylinder horn — narrow band, wobble, crackle and hiss.
 *  - Glitched: a failing sending-stone — dropouts, stutters, crushed bursts.
 *  - Old TV: a worn broadcast — compressed, humming, dusted with static.
 */
class LineEffect(val kind: String) {
    private val delay = FloatArray(Dsp.SR)              // up to 1 s of echo memory
    private var pos = 0
    private val lp1 = Biquad.lowPass(when (kind) { "distant" -> 2200.0; "phonograph" -> 3000.0; "tv" -> 4800.0; else -> 3400.0 })
    private val lp2 = Biquad.lowPass(when (kind) { "distant" -> 2200.0; "phonograph" -> 3000.0; else -> 3400.0 })
    private val hp = Biquad.highPass(when (kind) { "memory" -> 320.0; "phonograph" -> 380.0; "tv" -> 120.0; else -> 60.0 })
    private val fbLp = Biquad.lowPass(2500.0)

    private val delaySamples = when (kind) { "cave" -> Dsp.SR * 240 / 1000; "distant" -> Dsp.SR * 55 / 1000; else -> 1 }
    private val feedback = when (kind) { "cave" -> 0.38; "distant" -> 0.25; else -> 0.0 }
    private val wet = when (kind) { "cave" -> 0.42; "distant" -> 0.3; else -> 0.0 }

    private var t = 0L                                   // samples processed, for slow wobbles and hum
    private var glitchLeft = 0                           // samples left in the current glitch
    private var glitchGap = Dsp.SR / 2                   // samples until the next glitch
    private var glitchMode = 0                           // 0 mute, 1 crush, 2 stutter
    private val stutter = FloatArray(Dsp.SR * 35 / 1000)
    private var stutterPos = 0

    val active get() = kind in setOf("cave", "distant", "memory", "phonograph", "glitch", "tv")

    fun process(x: FloatArray): FloatArray {
        if (!active) return x
        return FloatArray(x.size) { i ->
            var v = x[i].toDouble()
            when (kind) {
                "distant" -> v = lp2.step(lp1.step(v)) * 0.85
                "memory" -> {
                    v = lp2.step(lp1.step(hp.step(v)))
                    v = tanh(v * 1.6) / 1.4 + Random.nextDouble(-0.004, 0.004)
                    if (Random.nextInt(9000) == 0) v += Random.nextDouble(-0.08, 0.08) // a rare crackle
                }
                "phonograph" -> {
                    v = lp1.step(hp.step(v))
                    v *= 1.0 + 0.09 * sin(2 * PI * 0.9 * t / Dsp.SR)        // the cylinder's slow wobble
                    v += Random.nextDouble(-0.0035, 0.0035)                  // surface hiss
                    if (Random.nextInt(2400) == 0) v += Random.nextDouble(-0.10, 0.10)   // dust pops
                    v = tanh(v * 1.8) / 1.6                                  // the horn's shout
                }
                "glitch" -> {
                    stutter[stutterPos] = v.toFloat(); stutterPos = (stutterPos + 1) % stutter.size
                    if (glitchLeft > 0) {
                        glitchLeft--
                        v = when (glitchMode) {
                            0 -> 0.0
                            1 -> kotlin.math.floor(v * 24) / 24 * 1.15      // crushed to 24 steps
                            else -> stutter[(stutterPos + 1 + (glitchLeft % stutter.size)) % stutter.size].toDouble()
                        }
                    } else if (--glitchGap <= 0) {
                        glitchMode = Random.nextInt(3)
                        glitchLeft = Dsp.SR * Random.nextInt(25, 80) / 1000
                        glitchGap = Dsp.SR * Random.nextInt(300, 1300) / 1000
                    }
                }
                "tv" -> {
                    v = lp1.step(hp.step(v))
                    v = tanh(v * 1.9) / 1.5                                  // broadcast compression
                    v += 0.004 * sin(2 * PI * 60 * t / Dsp.SR)               // mains hum
                    v += Random.nextDouble(-0.0030, 0.0030)                  // static dust
                }
            }
            if (feedback > 0) {
                val read = (pos - delaySamples + delay.size) % delay.size
                val echo = delay[read].toDouble()
                delay[pos] = (v + fbLp.step(echo) * feedback).toFloat()
                pos = (pos + 1) % delay.size
                v = v * (1 - wet * 0.3) + echo * wet
            }
            t++
            v.toFloat().coerceIn(-0.98f, 0.98f)
        }
    }

    /** The echo that rings on after the last word. */
    fun tail(): FloatArray {
        if (feedback <= 0) return FloatArray(0)
        val ms = if (kind == "cave") 900 else 300
        return process(FloatArray(Dsp.SR * ms / 1000))
    }

    companion object {
        val choices = listOf("none" to "None", "cave" to "Cave echo", "distant" to "Distant", "memory" to "Old memory",
            "phonograph" to "Phonograph", "glitch" to "Glitched", "tv" to "Old TV")
    }
}
