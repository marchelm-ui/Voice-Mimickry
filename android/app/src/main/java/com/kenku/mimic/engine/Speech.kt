package com.kenku.mimic.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import java.io.File

/** On-device speech recognition (Zipformer transducer via sherpa-onnx): what was said. */
class Recognizer(dir: File) {
    private val rec = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "decoder.onnx").absolutePath,
                    joiner = File(dir, "joiner.int8.onnx").absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = Dsp.threads(),
                modelType = "transducer",
            ),
        ),
    )

    /** 24 kHz audio -> recognized words (upper case). Pads with silence: the model tends to
     *  drop a first word that starts at time zero (measured). */
    @Synchronized
    fun words(audio24: FloatArray): List<String> {
        val pad = FloatArray(6400)
        val x = pad + Dsp.resample(audio24, Dsp.SR, 16000) + pad
        val s = rec.createStream()
        s.acceptWaveform(x, 16000)
        rec.decode(s)
        val text = rec.getResult(s).text
        s.release()
        return text.split(' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    }
}

/** Neural voice clone (Pocket TTS via sherpa-onnx): last resort for sounds the voice never made. */
class NeuralClone(dir: File) {
    private val tts = OfflineTts(
        null,
        OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                pocket = OfflineTtsPocketModelConfig(
                    lmFlow = File(dir, "lm_flow.int8.onnx").absolutePath,
                    lmMain = File(dir, "lm_main.int8.onnx").absolutePath,
                    encoder = File(dir, "encoder.onnx").absolutePath,
                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                    textConditioner = File(dir, "text_conditioner.onnx").absolutePath,
                    vocabJson = File(dir, "vocab.json").absolutePath,
                    tokenScoresJson = File(dir, "token_scores.json").absolutePath,
                ),
                numThreads = Dsp.threads(),
                provider = "cpu",
            ),
        ),
    )

    @Synchronized
    fun say(text: String, reference24: FloatArray): FloatArray {
        val cfg = GenerationConfig(referenceAudio = reference24, referenceSampleRate = Dsp.SR)
        val g = tts.generateWithConfig(text, cfg)
        return Dsp.resample(g.samples, g.sampleRate, Dsp.SR)
    }
}
