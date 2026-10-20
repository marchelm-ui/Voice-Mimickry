# Voice Mimickry

A voice engine for playing a **kenku** in D&D, built from scratch and running entirely on an
Android phone. A kenku can only speak with sounds it has heard, so this engine does the same:
it listens to people talk, remembers their real voice, and says new lines using only sounds
from those recordings.

The app speaks two ways, and you pick per line:

- **Every sound (smooth)** — the default. A reference reading of the line is rebuilt frame by
  frame (every 20 ms) from the closest-sounding moments of the person's real recordings
  (kNN voice conversion: WavLM features matched by cosine, HiFi-GAN vocoder). On 7 real test
  voices this was almost as intelligible as the person's own recording (6.2% vs 4.7% misheard
  words) with by far the highest speaker similarity of any system tested.
- **Their exact words** — word stitching: whole words cut from the real recordings, joined and
  reshaped (unit selection + PSOLA). Keeps genuinely real words intact — the truest kenku —
  but reads as rougher and harder to understand (32.9% misheard).

It also carries everything from Mocking Bird, the earlier kenku app, merged in:

- **🔔 Sounds** — a medieval soundboard: 467 CC0 sounds in 11 categories (doors, coins, tavern,
  weapons, monsters, magic…), with search, favorites, looping, and **My Mimicry** — real sounds
  you record at the table and replay like a kenku.
- **Sounds inside lines** — type `Pay up [coins] or else` and the sound plays in the middle of
  the sentence; `[tag]` matches sound names, categories, or your own recordings.
- **📜 Phrasebook** — ready-made kenku lines (combat, warnings, tavern, taunts…) via ＋ Add.
- **Voice effects** — Cave echo, Distant (through a wall), Old memory (lo-fi) on any line.
- **⚡ Quick lines** — pin a spoken line and replay it with one tap, even after a restart.
- **Starter voices** — five ready-made voices (two real public-domain readers, three synthetic
  characters) so the app speaks well before you record anyone.

## How word stitching works

1. **Listen.** Record someone talking (with their permission) or import an audio file. The
   recording is split at pauses and an on-device recognizer works out what was said. You can
   correct any misheard words.
2. **Cut words precisely.** A reference reader says the same words with exact phoneme timings,
   and dynamic time warping maps those timings onto the real recording. This put word
   boundaries within about 5–10 ms (median), versus 110–125 ms from the recognizer alone.
3. **Choose the best pieces.** For a new line, unit selection picks the longest, best-fitting
   stretches of real speech (whole phrases where possible), scoring prosodic position,
   question vs statement, neighbouring words, recognition confidence, and how smoothly each
   seam joins.
4. **Make it sound like one sentence.** The reference reader predicts how the new line should
   rise, fall and pace. Each piece is nudged toward that in the speaker's own pitch range with
   PSOLA, seams are pitch-matched, loudness is levelled with a natural slight fall, phrase
   ends are lengthened, and pauses are filled with the room's own background tone.
5. **Words it never heard** are built from that same voice's phoneme pieces, or, as a last
   resort, borrowed from a neural clone of the voice. The app shows which words were real.

## Repository

| Path | What it is |
|---|---|
| `engine/` | Reference engine in Python (the version experiments run on) |
| `android/` | The phone app; `engine/` code ported to Kotlin, checked against the Python version by unit tests |
| `eval/` | Benchmark: stand-in voices with exact timings, scoring, A/B tools, listening test builder |
| `docs/FINDINGS.md` | Ranked findings from surveying other TTS engines, reviews and bug reports |
| `.github/workflows/` | Builds the APK; runs the benchmark on GitHub's machines |

## Install

Open this repository's **Releases** page on your Android phone, download
**VoiceMimickry.apk** from the newest build and open it (allow installs from your browser if
asked). Built for 64-bit ARM phones such as Pixels.

## Benchmark

Latest full run (7 voices: 4 real LibriSpeech speakers, LJSpeech, 2 synthetic stand-ins;
15 lines each; full table on the `eval-results` branch, `SUMMARY.txt`):

| system | misheard words | speaker similarity | predicted quality | pitch-contour match |
|---|---|---|---|---|
| the real person | 4.7% | 0.873 | 3.21 | 1.00 |
| **this app — every sound** | **6.2%** | **0.736** | **3.16** | **0.57** |
| this app — exact words | 32.9% | 0.547 | 2.65 | 0.43 |
| Pocket TTS voice clone | 7.5% | 0.386 | 2.99 | 0.33 |
| ElevenLabs (stock voice) | 3.1% | 0.181 | 3.08 | 0.24 |

Columns: word error rate when a recognizer listens (lower is better), speaker similarity to
the real voice (higher is better), predicted audio quality (DNSMOS), and pitch-contour match
against the real person saying the same line.

Honest limits: ElevenLabs was scored on a stock voice, not a clone of the test speaker, so its
similarity is a floor, not a fair comparison; a home-built engine without GPU training does
not beat top neural systems on arbitrary new sentences; and quality depends on how much of a
person's speech the kenku has heard.

## Credits and licenses

- Kokoro-82M by hexgrad (Apache-2.0): reference reader for alignment, prosody and the mimic guide
- kNN-VC by Baas, van Niekerk, Kamper (MIT): WavLM-Large encoder + prematched HiFi-GAN, exported
  to fp16 ONNX for the phone
- Zipformer English recognizer, sherpa-onnx by k2-fsa (Apache-2.0)
- Pocket TTS by Kyutai, ONNX export via sherpa-onnx (non-commercial license): fallback reader
- onnxruntime by Microsoft (MIT)
- Sound effects: CC0 (public domain) packs from OpenGameArt.org, listed in
  `android/app/src/main/assets/sfx/CREDITS.txt`
- Starter voices: LibriVox public-domain recordings via LibriSpeech (CC BY 4.0)
- Benchmark only: WeSpeaker ResNet34 speaker model, Microsoft DNSMOS
