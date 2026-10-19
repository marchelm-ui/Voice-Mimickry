"""Text normalization: turn what the player types into speakable words + prosody marks.

Getting this wrong is the #1 complaint across TTS reviews (numbers read wrong, "No."
read as "number", symbols spoken aloud), so it gets its own careful module.
"""
import re

ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"]
TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]
ORD = {"one": "first", "two": "second", "three": "third", "five": "fifth", "eight": "eighth",
       "nine": "ninth", "twelve": "twelfth"}

ABBR = {
    "mr": "mister", "mrs": "missus", "ms": "miz", "dr": "doctor", "st": "saint", "lt": "lieutenant",
    "capt": "captain", "sgt": "sergeant", "gp": "gold pieces", "sp": "silver pieces", "cp": "copper pieces",
    "hp": "hit points", "dm": "D M", "npc": "N P C", "ac": "armor class", "xp": "experience",
    "vs": "versus", "etc": "et cetera", "lb": "pounds", "lbs": "pounds", "ft": "feet",
}

SYMBOLS = {"&": " and ", "%": " percent ", "+": " plus ", "=": " equals ", "@": " at ", "#": " number "}


def num_words(n):
    n = int(n)
    if n < 0:
        return "minus " + num_words(-n)
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else " " + ONES[n % 10])
    if n < 1000:
        r = n % 100
        return ONES[n // 100] + " hundred" + ("" if r == 0 else " and " + num_words(r))
    for div, name in ((10 ** 9, "billion"), (10 ** 6, "million"), (1000, "thousand")):
        if n >= div:
            r = n % div
            tail = "" if r == 0 else (" and " if r < 100 else " ") + num_words(r)
            return num_words(n // div) + " " + name + tail
    return str(n)


def ordinal(n):
    w = num_words(n).split()
    last = w[-1]
    if last in ORD:
        w[-1] = ORD[last]
    elif last.endswith("y"):
        w[-1] = last[:-1] + "ieth"
    else:
        w[-1] = last + "th"
    return " ".join(w)


def _expand(text):
    t = text.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    t = re.sub(r"\.{3,}|…", " , ", t)                         # ellipsis -> pause
    t = re.sub(r"(\d+)d(\d+)", lambda m: f"{num_words(m.group(1))} D {num_words(m.group(2))}", t)  # 2d6
    t = re.sub(r"(\d{1,3}(?:,\d{3})+)", lambda m: m.group(1).replace(",", ""), t)
    t = re.sub(r"(\d+)(st|nd|rd|th)\b", lambda m: ordinal(m.group(1)), t)
    t = re.sub(r"(\d+)\.(\d+)", lambda m: num_words(m.group(1)) + " point " + " ".join(ONES[int(d)] for d in m.group(2)), t)
    t = re.sub(r"\d+", lambda m: " " + num_words(m.group(0)) + " ", t)
    for s, w in SYMBOLS.items():
        t = t.replace(s, w)

    def abbr(m):
        w = m.group(1)
        return ABBR.get(w.lower(), w) + ("" if w.lower() in ABBR else ".")
    t = re.sub(r"\b(mr|mrs|ms|dr|st|capt|sgt|lt)\.(?=\s)", lambda m: ABBR[m.group(1).lower()], t, flags=re.I)
    t = re.sub(r"\b([A-Za-z]{1,4})\.(?=\s+[a-z])", abbr, t)        # "approx. ten" style mid-sentence
    t = re.sub(r"\b(gp|sp|cp|hp|xp|npc|dm|vs|lbs?|ft)\b", lambda m: ABBR[m.group(1).lower()], t, flags=re.I)
    return t


def normalize(text):
    """-> list of tokens. Words are dicts {w, pos}; pauses are dicts {pause: kind}.
    kinds: ',' short pause, '.' statement end, '?' question end, '!' exclamation end."""
    t = _expand(text)
    out = []
    for tok in re.findall(r"[A-Za-z]+(?:'[A-Za-z]+)?|[.,!?;:\-—()]", t):
        if tok in ".!?":
            out.append({"pause": tok})
        elif tok in ",;:-—()":
            out.append({"pause": ","})
        else:
            out.append({"w": tok.upper()})
    # collapse repeated pauses, keep the strongest
    rank = {",": 0, ".": 1, "!": 2, "?": 3}
    clean = []
    for tok in out:
        if "pause" in tok and clean and "pause" in clean[-1]:
            if rank[tok["pause"]] > rank[clean[-1]["pause"]]:
                clean[-1] = tok
            continue
        clean.append(tok)
    while clean and "pause" in clean[0]:
        clean.pop(0)
    if clean and "pause" not in clean[-1]:
        clean.append({"pause": "."})
    # annotate words with their prosodic role
    for i, tok in enumerate(clean):
        if "w" in tok:
            nxt = clean[i + 1] if i + 1 < len(clean) else {"pause": "."}
            prv = clean[i - 1] if i > 0 else {"pause": "."}
            tok["end"] = nxt.get("pause")            # None if another word follows
            tok["start"] = "pause" in prv
    return clean


def words_only(text):
    return [t["w"] for t in normalize(text) if "w" in t]


if __name__ == "__main__":
    for s in ["Pay 2,500 gp... or else!", "Roll 2d6 + 3 for the 3rd time.", "No. Who's there?",
              "Mr. Smith owes me 12.5 silver", "It's 100% true & honest"]:
        print(s, "->", " ".join(t.get("w", t.get("pause")) for t in normalize(s)))
