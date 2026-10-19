package com.kenku.mimic.engine

/**
 * Turns what the player types into speakable words plus pause marks.
 * Port of engine/text.py; keep the two in step.
 */
object TextNorm {

    sealed class Tok {
        /** [end] is the pause that follows ("," "." "?" "!") or null if another word follows. */
        data class Word(val w: String, var start: Boolean = false, var end: String? = null) : Tok()
        data class Pause(val kind: String) : Tok()
    }

    private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val ORD = mapOf("one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
        "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth")
    private val ABBR = mapOf(
        "mr" to "mister", "mrs" to "missus", "ms" to "miz", "dr" to "doctor", "st" to "saint", "lt" to "lieutenant",
        "capt" to "captain", "sgt" to "sergeant", "gp" to "gold pieces", "sp" to "silver pieces", "cp" to "copper pieces",
        "hp" to "hit points", "dm" to "D M", "npc" to "N P C", "ac" to "armor class", "xp" to "experience",
        "vs" to "versus", "etc" to "et cetera", "lb" to "pounds", "lbs" to "pounds", "ft" to "feet",
    )
    private val SYMBOLS = linkedMapOf("&" to " and ", "%" to " percent ", "+" to " plus ", "=" to " equals ",
        "@" to " at ", "#" to " number ")

    fun numWords(n: Long): String {
        if (n < 0) return "minus " + numWords(-n)
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) return TENS[(n / 10).toInt()] + if (n % 10 == 0L) "" else " " + ONES[(n % 10).toInt()]
        if (n < 1000) {
            val r = n % 100
            return ONES[(n / 100).toInt()] + " hundred" + if (r == 0L) "" else " and " + numWords(r)
        }
        for ((div, name) in listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1000L to "thousand")) {
            if (n >= div) {
                val r = n % div
                val tail = if (r == 0L) "" else (if (r < 100) " and " else " ") + numWords(r)
                return numWords(n / div) + " " + name + tail
            }
        }
        return n.toString()
    }

    fun ordinal(n: Long): String {
        val w = numWords(n).split(" ").toMutableList()
        val last = w.last()
        w[w.size - 1] = ORD[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        return w.joinToString(" ")
    }

    private fun num(s: String) = s.toLongOrNull()?.let { numWords(it) } ?: s

    fun expand(text: String): String {
        var t = text.replace('\u2019', '\'').replace('\u2018', '\'').replace('\u201c', '"').replace('\u201d', '"')
        t = Regex("\\.{3,}|\u2026").replace(t, " , ")
        t = Regex("(\\d+)d(\\d+)").replace(t) { "${num(it.groupValues[1])} D ${num(it.groupValues[2])}" }
        t = Regex("(\\d{1,3}(?:,\\d{3})+)").replace(t) { it.value.replace(",", "") }
        t = Regex("(\\d+)(st|nd|rd|th)\\b").replace(t) { ordinal(it.groupValues[1].toLong()) }
        t = Regex("(\\d+)\\.(\\d+)").replace(t) { m ->
            num(m.groupValues[1]) + " point " + m.groupValues[2].map { ONES[it - '0'] }.joinToString(" ")
        }
        t = Regex("\\d+").replace(t) { " " + num(it.value) + " " }
        for ((s, w) in SYMBOLS) t = t.replace(s, w)
        t = Regex("\\b(mr|mrs|ms|dr|st|capt|sgt|lt)\\.(?=\\s)", RegexOption.IGNORE_CASE).replace(t) { ABBR[it.groupValues[1].lowercase()]!! }
        t = Regex("\\b(gp|sp|cp|hp|xp|npc|dm|vs|lbs?|ft)\\b", RegexOption.IGNORE_CASE).replace(t) { ABBR[it.groupValues[1].lowercase()]!! }
        return t
    }

    private val RANK = mapOf("," to 0, "." to 1, "!" to 2, "?" to 3)

    fun normalize(text: String): List<Tok> {
        val raw = mutableListOf<Tok>()
        for (m in Regex("[A-Za-z]+(?:'[A-Za-z]+)?|[.,!?;:\\-\u2014()]").findAll(expand(text))) {
            val s = m.value
            raw += when {
                s in setOf(".", "!", "?") -> Tok.Pause(s)
                s.length == 1 && !s[0].isLetter() -> Tok.Pause(",")
                else -> Tok.Word(s.uppercase())
            }
        }
        val clean = mutableListOf<Tok>()
        for (t in raw) {
            val prev = clean.lastOrNull()
            if (t is Tok.Pause && prev is Tok.Pause) {
                if (RANK[t.kind]!! > RANK[prev.kind]!!) clean[clean.size - 1] = t
                continue
            }
            clean += t
        }
        while (clean.isNotEmpty() && clean.first() is Tok.Pause) clean.removeAt(0)
        if (clean.isNotEmpty() && clean.last() !is Tok.Pause) clean += Tok.Pause(".")
        for (i in clean.indices) {
            val t = clean[i] as? Tok.Word ?: continue
            t.end = (clean.getOrNull(i + 1) as? Tok.Pause)?.kind
            t.start = i == 0 || clean[i - 1] is Tok.Pause
        }
        return clean
    }

    fun words(text: String): List<String> = normalize(text).filterIsInstance<Tok.Word>().map { it.w }

    /** Phrases = runs of words between pauses, with the pause that ends each. */
    fun phrases(text: String): List<Pair<List<Tok.Word>, String>> {
        val out = mutableListOf<Pair<List<Tok.Word>, String>>()
        var cur = mutableListOf<Tok.Word>()
        for (t in normalize(text)) {
            when (t) {
                is Tok.Word -> cur += t
                is Tok.Pause -> { if (cur.isNotEmpty()) out += cur to t.kind; cur = mutableListOf() }
            }
        }
        if (cur.isNotEmpty()) out += cur to "."
        return out
    }
}
