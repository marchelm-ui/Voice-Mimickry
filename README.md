# Voice Mimickry

A voice engine for playing a **kenku** in D&D, built from scratch and running entirely on an
Android phone. A kenku can only speak with sounds it has heard, so this engine does the same:
it listens to people talk, remembers every word in their real voice, and says new lines by
stitching those real recordings together, then reshapes pitch and timing so the new sentence
sounds naturally spoken.

## How it works

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

See the `eval-results` branch for the latest scores (`SUMMARY.txt`). Columns: word error rate
when a recognizer listens (lower is better), speaker similarity to the real voice (higher is
better), predicted audio quality, seam glitchiness relative to real speech (1.0 = as smooth),
spectral distance and pitch-contour match against the real person saying the same line.

Honest limits: the stand-in "people" are synthetic voices, which makes alignment easier than
with real recordings; a home-built engine without GPU training does not beat top neural
systems on arbitrary new sentences; and quality depends on how much of a person's speech the
kenku has heard.

## Credits and licenses

- Kokoro-82M by hexgrad (Apache-2.0): reference reader for alignment and prosody
- Zipformer English recognizer, sherpa-onnx by k2-fsa (Apache-2.0)
- Pocket TTS by Kyutai, ONNX export via sherpa-onnx (non-commercial license): last-resort fallback
- onnxruntime by Microsoft (MIT)
- Benchmark only: WeSpeaker ResNet34 speaker model, Microsoft DNSMOS
