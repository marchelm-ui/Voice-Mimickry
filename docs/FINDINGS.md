# What makes a voice sound human: ranked findings

A survey of current text-to-speech and voice-cloning engines (open source and commercial),
their published code, benchmarks, and user reviews, bug reports and forum complaints,
distilled into what matters most, ordered from most to least important for a kenku app
that has to fool people at a game table.

You asked for 1,000 items. There aren't 1,000 *distinct* lessons in this material, so
this list stops where the real findings stop rather than padding with repeats. Items
marked **[engine]** are built into Voice Mimickry; **[app]** are planned for the phone app;
**[measured]** were verified in this repo's benchmarks.

---

## Tier 1: decides whether it sounds human at all

1. **Prosody beats timbre.** Listeners forgive a slightly-off voice color far more than a wrong melody, rhythm or stress. Every leaderboard leader wins on prosody; the commonest complaint about cheaper engines is "flat" or "monotone". [engine: prosody transplant]
2. **Real recorded audio is the most human-sounding material there is.** Concatenating actual speech avoids every neural artifact (buzz, metallic ring, mumbling). Its weaknesses are seams and wrong intonation, which are fixable. This is the core bet of this engine. [engine]
3. **Seams are the classic giveaway of concatenation.** Pitch jumps, loudness jumps and spectral discontinuities at joins are what made 2000s-era unit-selection voices sound "cut and pasted". [engine: pitch-matched seams, crossfades, measured seam score]
4. **Bigger units mean fewer seams.** Choosing the longest stretches of naturally-connected speech (whole phrases, then words) keeps the speaker's real coarticulation. [engine: span-based unit selection]
5. **Accurate cut points matter more than clever smoothing.** Cutting 100 ms off leaves fragments of neighbouring words that no crossfade can hide. Recognizer timestamps alone were 110–125 ms off (median); our alignment brought that to 5–8 ms. [engine, measured]
6. **Hallucination is the top failure of neural TTS.** Skipped words, repeated words, invented words and garbled endings are reported across Bark, Chatterbox, XTTS, MiniMax and others, especially on long or very short inputs. A concatenative engine cannot invent words. [engine]
7. **Mispronounced numbers, abbreviations and symbols** are the most frequent concrete bug report (e.g. "No." read as "number", symbols read aloud, currency/dates garbled). [engine: text normalizer]
8. **Phrase-final behaviour.** Real speakers fall in pitch and slow down at the end of statements and rise on many questions. Missing final lengthening is an instant tell. [engine]
9. **Natural pauses.** Pauses at punctuation of roughly 150–400 ms, never dead digital silence. [engine: room-tone filled pauses]
10. **Dead silence is unnatural.** Real recordings have room tone; splicing in pure zeros sounds like the line cut out. [engine]
11. **Consistency across a long passage.** Neural engines drift in voice color, pace and loudness over minutes (reported for MiniMax, XTTS, Bark). [engine: loudness matching, same source voice]
12. **Garbage in, garbage out for cloning.** Reference audio quality (noise, reverb, clipping, music) is the biggest driver of cloning quality. [app: recording checks]
13. **Speaker similarity is measurable** with speaker-verification embeddings; use it rather than guessing. [measured]
14. **Intelligibility is measurable** with a recognizer (word error rate). [measured]
15. **Blind, randomized listening is the only honest naturalness test.** Vendor studies (e.g. a 65% preference win claimed by one vendor against a competitor) are not independent. [eval]

## Measured in this project (most important first)

These come from Voice Mimickry's own benchmarks and a blind listening test, and they reorder
some of the list above.

- **Frame-level mimicry beats word stitching on every measure.** Rebuilding a reference reading
  frame by frame from the person's recordings (kNN-VC: WavLM layer-6 features, 4-NN cosine,
  HiFi-GAN) scored 6.2% misheard / 0.736 speaker similarity across 7 voices, vs 32.9% / 0.547
  for unit-selection stitching. It also solves the small-bank problem: every sound exists in
  any few minutes of speech, where whole words mostly don't.
- **The guide reading matters less than expected — and stock beats clone.** Guiding the
  conversion with the neutral Kokoro reader beat guiding with a Pocket clone of the target
  voice (6.2% vs 6.9% misheard, 0.736 vs 0.691 similarity). Every frame gets replaced anyway;
  a cleaner, more intelligible guide survives conversion better than a voice-matched one.
- **int8 ruins a feature encoder that fp16 preserves.** Dynamic-int8 WavLM features matched
  fp32 at only 0.86 cosine (0.37 without per-channel weights) — and those features are the
  kNN search keys. fp16 matched at 0.99999 for half the fp32 size. Quantize vocoders, not
  the features you search with.
- **fp16 ONNX needs ARM.** onnxruntime emulates fp16 on x64, turning a seconds-long check into
  40+ minutes of CI hang; converting with `disable_shape_infer=True` is also mandatory on
  big models, where whole-graph shape inference itself runs for the better part of an hour.
- **The mimicry engine is at a measurable plateau.** Pitch-matching the guide to the speaker
  before conversion and doubling the neighbours to 8 both changed nothing (speaker similarity
  0.730–0.732, misheard 5.2–5.5% across 8 voices, all within noise of the current engine).
  WavLM features barely carry pitch, so bending the guide's pitch cannot steer the output, and
  4 neighbours already average out selection noise. The next real gains live in the vocoder
  (prematched HiFi-GAN is the ceiling) and in listening tests, not in search parameters.
- **Human ears beat metrics.** In a blind A/B test the neural clone sounded more human than the word-stitching engine 6 of 6 times, and "sounds like the person" was a 3-3 tie, while the speaker-similarity metric scored the stitched voice far higher (0.71-0.81 vs 0.37). Speaker-embedding similarity rewards real recorded sound but can't hear unnatural seams or melody.
- **Stitched words are hard to understand when the bank is small.** With 3.5 minutes of a real reader, a recognizer misheard 65% of words in new sentences (vs 8% for a neural clone), mostly because new sentences need many words she never said.
- **Neighbouring-word fragments were the #1 cause of garbled words.** Ending each word at the first matching moment and starting the next at the last cut misheard words from 79% to about 23%.
- **For unheard words, a neural patch beats splicing sounds:** 20-28% vs 64-96% misheard.
- **Snapping cuts to quiet points makes them worse** (median error 10 to 40 ms); pre-roll for soft consonants also hurt (22-24% to 26%).
- **Matching the alignment reader's pitch range matters:** 70 ms vs 5 ms median boundary error on a female voice.
- **Neighbour-context unit preference:** no measurable gain.
- **Half-resolution time warping** keeps boundary accuracy (96% within 25 ms) at a quarter of the memory, which matters on a phone.
- **ElevenLabs via a proxy can trip its abuse detection** ("unusual activity", free tier disabled).

## Tier 2: big quality differences between engines

16. Flow-matching / diffusion models (F5-TTS) give the best open-source long-form prosody but need 12–16 GB of GPU memory; impossible on a phone today.
17. Small on-device models (Kokoro 82M, Piper, Kitten, NeuTTS Nano/Air, Phonon ~100M) are clean but have fixed voices or weak cloning.
18. On-device cloning models reach only ~40–56% speaker similarity in the published small-model comparison; there is a large gap to studio clones.
19. The ~100 MB app-bundle budget is considered the practical ceiling for a mobile voice model.
20. Single-step samplers make small models monotone ("averaged" speech) unless trained to preserve variance.
21. Autoregressive models (Bark, Orpheus, Chatterbox's LM) can run on and on or stop early; stop-token heuristics are fragile (Chatterbox issue: forced end after repeated tokens cutting lines short).
22. Hooks/state leaking between generations progressively degrades output in some open implementations; restart-free reliability matters on a phone.
23. Emotion control via tags works in a few models (CosyVoice 2, Orpheus) but tags can be read aloud or trigger hallucination (MiniMax duration tags; Bark bracket tags).
24. Very short prompts (under ~5 words) raise hallucination rates in Bark-style models.
25. Language mismatch between voice and text raises hallucination and accent bleed.
26. Cloned voices glitch more than stock voices in at least one commercial engine (MiniMax users: system voices clean, cloned voices squeak).
27. Long-form audio shows intermittent squeaks/scratches every few minutes in some engines; chunked generation with checks helps.
28. Multi-speaker dialogue natively (Fish Speech) avoids stitching separate voices.
29. Leaderboard leaders (late 2026 Artificial Analysis arena): ElevenLabs v4/v4 Turbo, Qwen Audio TTS, Sonic, Gemini Flash TTS; best open-weights entries include Fish Audio Drama 3 Preview, Breeze TTS 2 and Ming-3.0.
30. Only about 18 of 97 arena models were open weights at time of reading.
31. Licensing traps: F5-TTS weights are non-commercial (training data), XTTS v2 is non-commercial with no vendor left to license from, Fish Speech open weights are non-commercial, the active Piper fork is GPL-3.0. Pocket TTS (used by Mocking Bird for cloning) is also non-commercial.
32. Some open models watermark every output by default (Chatterbox's PerTh). Good for ethics; worth knowing.
33. Setup difficulty decides adoption: one-command installs win over 90-minute CUDA setups even at lower quality.
34. Deterministic output matters for a game table: Bark's non-determinism makes repeated lines differ unpredictably.
35. Real-time factor on CPU: Piper/Kokoro run faster than real time on phones; cloning models are 3–10x slower on CPU.
36. Mixed-precision training instabilities can silently wreck small models (NaN steps); irrelevant to users but explains uneven quality across releases.
37. Number/date/currency handling has to be trained or normalized explicitly; small models fail on it by default.

## Tier 3: details that separate "good" from "can't tell"

38. Pitch-synchronous modification (PSOLA) shifts pitch/tempo by ±20% with few artifacts if pitch marks are right; beyond ±30% it buzzes. [engine, measured ±1–2% accuracy]
39. Pitch detection octave errors cause chipmunk/growl jumps; median-clean the pitch track. [engine]
40. Resampling to change pitch also shifts formants (the "chipmunk" effect Mocking Bird's own comments note); PSOLA avoids that. [engine]
41. Loudness should be matched on active speech only, not including silences. [engine]
42. Slight declination (getting a little quieter and lower across a sentence) is natural. [engine]
43. Equal-power crossfades of 5–10 ms hide most seams; longer ones smear consonants. [engine]
44. Cut at the aligned boundary, not at the nearest energy dip: measured, snapping to energy dips moved cuts away from true boundaries (median error 10 → 40 ms). [engine, measured]
45. Reference reader voice should match the speaker's pitch range for alignment (male reference on a female voice: median error 70 ms; female reference: 5 ms). [engine, measured]
46. Automatically choosing between two reference readers by alignment cost beat any fixed choice. [engine, measured]
47. Breath sounds at long pauses add realism; removing all breaths sounds edited.
48. Stressed words are longer and higher; content words carry stress, function words don't.
49. Question intonation differs: yes/no questions rise, wh-questions usually fall.
50. Exclamations are louder, higher and faster at the start.
51. Speaking rate varies with phrase length; long phrases are spoken faster.
52. Words at the start of a phrase have slightly higher pitch resets.
53. Coarticulation: a word sounds different depending on its neighbours; keeping original neighbours (multi-word units) preserves this. [engine]
54. Function words ("the", "a", "to") change pronunciation by context ("thuh" vs "thee"); picking the right variant by context matters.
55. Confidence from the recognizer should down-weight doubtful words rather than trusting every transcript word. [engine]
56. Let the user correct a transcript: one fixed word beats any algorithm. [engine API, app]
57. Recognizers split compounds ("TO NIGHT" for "tonight"); matching must tolerate spacing differences. [eval]
58. Recognizers drop a first word that starts at time zero; pad audio with silence first. [engine, measured]
59. Isolated words cut from connected speech are hard even for recognizers to identify; don't use per-word recognition as a quality check. [measured]
60. Spectral tilt (brightness) varies between recordings; match it when mixing sources.
61. Mic distance and room differ between recordings at a table; normalize level and lightly match tone.
62. Background noise in a source recording makes seams obvious when the noise floor changes; matching room tone hides this.
63. Clipping in a recording is unfixable; warn while recording.
64. Short reference clips (5–10 s) are enough for neural cloning to get the voice color, but not the person's habits; more audio helps unit selection far more.
65. For a kenku, 1–3 minutes of someone talking already covers most common English words in their actual voice.
66. The most common 100 English words cover about half of everyday speech; banks fill up fast.
67. Phoneme-level splicing can build unheard words from the same voice, at lower quality than whole words. [engine]
68. When neither words nor sounds are available, fall back to a neural clone of the same voice. [engine]
69. Mixing real words with neural fallback words needs loudness and pitch matching or the fallback words stand out. [engine]

## Tier 4: app and workflow lessons from reviews

70. Latency to first sound under ~1 s matters for live play (Mocking Bird already streams).
71. Offline operation is a top-requested feature; no accounts, no internet at the table.
72. Large app downloads (600 MB) put people off; model size should be justified.
73. One-tap replay of saved lines is used far more than typing new ones mid-game.
74. Pinned "quick lines" and a phrasebook speed up play.
75. Sound effects mid-sentence are popular for kenku play.
76. Recording real sounds at the table ("my mimicry") is the most kenku-authentic feature.
77. Show which parts of a line are real recorded words vs patched, so the player knows how convincing it will be. [engine report]
78. Let the player choose strictness: only real words (pure kenku) vs fill gaps.
79. Voices should only be cloned with permission; make consent explicit in the flow.
80. Users want to edit a generated line (swap one word) without regenerating everything.
81. Export/share audio clips.
82. Volume and speaker routing (phone speaker vs Bluetooth) issues are common app-store complaints.
83. Robotic default system voices are the most common complaint about generic Android TTS apps.
84. App crashes on first load of large models are a frequent complaint; warm up in background.
85. Battery drain from long neural generation; concatenation is far cheaper.
86. Accessibility: large buttons and simple flows for use mid-game.
87. Clear errors when the mic is busy or permission is denied.
88. Keep user data across updates (signing key stability, as Mocking Bird does).
89. Show progress while a voice is being learned.
90. Let users delete a voice and all its recordings in one tap (privacy).

## Tier 5: evaluation practice

91. Report hardware, versions and whether models were cached with any speed number.
92. Separate download time from warm generation time.
93. Use a fixed test script with tricky items decided in advance.
94. Hide engine labels and randomize order in listening tests.
95. Parameter count is not a quality score.
96. Treat vendor benchmarks as marketing until independently reproduced.
97. Save leaderboard snapshots with dates; rankings change monthly.
98. Compare against ground truth (the real person saying the line) whenever possible. [eval]
99. Use several metrics at once; each one can be gamed alone. [eval]
100. Stand-in voices made by one TTS model make alignment look easier than real people; always re-check on real recordings. [eval caveat]

---

## Sources

- [Best Open-Source Voice Cloning Models 2026: 6 tested (RareBuild)](https://rarebuildsoftware.com/blog/best-open-source-voice-cloning-2026)
- [Best Local TTS Models 2026 (LocalAIMaster)](https://localaimaster.com/blog/best-local-tts-models)
- [Text to Speech Leaderboard (Artificial Analysis)](https://artificialanalysis.ai/text-to-speech/leaderboard)
- [TTS Model Quality Ranking 2026 (OfflineTTS)](https://offlinetts.com/blog/tts-model-ranking-2026/)
- [On-Device Text-to-Speech in 2026 (Gradium)](https://gradium.ai/content/on-device-text-to-speech-2026)
- [How we prepared voice cloning TTS to run on-device (DEV)](https://dev.to/voxrtio/how-we-prepared-voice-cloning-tts-to-run-on-device-184)
- [Chatterbox: testing an open-source TTS tool (DEV)](https://dev.to/aairom/chatterbox-testing-an-open-source-tts-tool-my-impressions-726)
- [Voicebox issue #587: Chatterbox stops early](https://github.com/jamiepine/voicebox/issues/587)
- [Bark discussion: model hallucinates almost every run](https://huggingface.co:443/suno/bark/discussions/15)
- [MiniMax discussion: "No" read as "Number"](https://www.huggingface.co/spaces/MiniMaxAI/README/discussions/1)
- [MiniMax discussion: audio scratching in long voiceovers](https://huggingface.co/spaces/MiniMaxAI/README/discussions/3)
- [Kenku guide (RPGBot)](https://rpgbot.net/dnd5/characters/races/kenku/)
- Hunt & Black (1996), unit selection with target and join costs: [paper](https://www.ee.columbia.edu/~dpwe/e6820/papers/HuntB96-speechsynth.pdf)
- Vepa & King (2005), join cost for concatenative synthesis: [paper](https://www.cstr.ed.ac.uk/downloads/publications/2005/vepa_king_ieee2005.pdf)
