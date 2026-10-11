package com.everydayecho.memorykeeper.voice

import android.content.Context
import com.everydayecho.memorykeeper.record.AacDecoder
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * The user's voiceprint: a speaker embedding (3D-Speaker CAM++, bundled in assets) averaged
 * over the user's voice samples. With it, recordings keep only the stretches where the user
 * is speaking; other voices, and audio with no voice at all, are cut out.
 */
object Voiceprint {
    const val MODEL_ASSET = "speaker_campplus.onnx"
    const val DEFAULT_THRESHOLD = 0.45f
    private const val PREFS = "voiceprint"

    /** Voice samples recorded in Settings or picked from recordings. */
    fun samplesDir(context: Context) = File(context.filesDir, "MemoryKeeper/data/audio/voiceprint")

    /** The sample recorded during onboarding, if any. */
    private fun onboardingSample(context: Context) = File(context.filesDir, "MemoryKeeper/data/audio/voice_self.m4a")

    private fun embeddingFile(context: Context) = File(context.filesDir, "voiceprint.emb")

    fun samples(context: Context): List<File> =
        (listOf(onboardingSample(context)) + samplesDir(context).listFiles().orEmpty().sortedBy { it.name })
            .filter { it.isFile && it.length() > 0 }

    fun isEnrolled(context: Context) = embeddingFile(context).exists()

    fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("enabled", on).apply()

    fun threshold(context: Context) = prefs(context).getFloat("threshold", DEFAULT_THRESHOLD)
    fun setThreshold(context: Context, t: Float) = prefs(context).edit().putFloat("threshold", t).apply()

    /** True when recordings should be filtered down to the user's voice. */
    fun isActive(context: Context) = isEnabled(context) && isEnrolled(context)

    /** Changes whenever the voiceprint or its settings change, so long-lived filters can reload. */
    fun version(context: Context) =
        "${embeddingFile(context).lastModified()}|${threshold(context)}|${isEnabled(context)}"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun newExtractor(context: Context) = SpeakerEmbeddingExtractor(
        context.assets,
        SpeakerEmbeddingExtractorConfig(model = MODEL_ASSET, numThreads = 2),
    )

    /** Recomputes the voiceprint from all samples. Returns the number of samples used. */
    fun rebuild(context: Context): Int {
        val files = samples(context)
        if (files.isEmpty()) {
            embeddingFile(context).delete()
            return 0
        }
        val ex = newExtractor(context)
        try {
            val embs = files.mapNotNull { f ->
                val pcm = runCatching { AacDecoder.decode(f) }.getOrNull() ?: return@mapNotNull null
                val x = Audio.toRate(pcm.samples, pcm.sampleRate, Audio.RATE)
                if (x.size < Audio.RATE || rms(x) < MIN_RMS) null else embed(ex, x)
            }
            if (embs.isEmpty()) {
                embeddingFile(context).delete()
                return 0
            }
            val avg = FloatArray(embs[0].size)
            for (e in embs) for (i in avg.indices) avg[i] += e[i]
            save(embeddingFile(context), normalize(avg))
            return embs.size
        } finally {
            ex.release()
        }
    }

    fun clear(context: Context) {
        samplesDir(context).deleteRecursively()
        onboardingSample(context).delete()
        embeddingFile(context).delete()
    }

    fun load(context: Context): FloatArray? = embeddingFile(context).takeIf { it.exists() }?.let { f ->
        DataInputStream(f.inputStream().buffered()).use { inp -> FloatArray(inp.readInt()) { inp.readFloat() } }
    }

    private fun save(f: File, v: FloatArray) {
        DataOutputStream(f.outputStream().buffered()).use { out ->
            out.writeInt(v.size)
            v.forEach { out.writeFloat(it) }
        }
    }

    internal fun embed(ex: SpeakerEmbeddingExtractor, x: FloatArray): FloatArray {
        val s = ex.createStream()
        try {
            s.acceptWaveform(x, Audio.RATE)
            s.inputFinished()
            return normalize(ex.compute(s))
        } finally {
            s.release()
        }
    }

    /** Samples quieter than this are treated as silent (e.g. the mic was busy) and ignored. */
    private const val MIN_RMS = 0.003f

    private fun rms(x: FloatArray): Float {
        var e = 0.0
        for (v in x) e += v * v
        return sqrt(e / x.size).toFloat()
    }

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n).coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / n }
    }
}

/**
 * Finds where in a piece of audio the user is speaking. Scores 2 s windows (0.5 s hop) against
 * the voiceprint, averages the scores per 0.5 s block, keeps blocks above the threshold, bridges
 * gaps of up to 1 s and drops runs shorter than 0.8 s. Not thread-safe; [release] when done.
 */
class SpeakerFilter private constructor(
    private val ex: SpeakerEmbeddingExtractor,
    private val ref: FloatArray,
    private val threshold: Float,
) {
    /** Sample ranges (end exclusive) of 16 kHz [x] that are the user's voice. */
    fun userRegions(x: FloatArray): List<IntRange> {
        if (x.size < MIN_KEEP) return emptyList()
        if (x.size < WIN) return if (score(x) >= threshold) listOf(0 until x.size) else emptyList()
        val nb = (x.size + HOP - 1) / HOP
        val sum = FloatArray(nb)
        val cnt = IntArray(nb)
        val starts = (0..x.size - WIN step HOP).toMutableList()
        if (starts.last() + WIN < x.size) starts += x.size - WIN
        for (s in starts) {
            val v = score(x.copyOfRange(s, s + WIN))
            for (b in s / HOP until minOf(nb, (s + WIN + HOP - 1) / HOP)) {
                sum[b] += v
                cnt[b]++
            }
        }
        val runs = mutableListOf<IntArray>()
        var i = 0
        while (i < nb) {
            if (cnt[i] > 0 && sum[i] / cnt[i] >= threshold) {
                var j = i
                while (j < nb && cnt[j] > 0 && sum[j] / cnt[j] >= threshold) j++
                val last = runs.lastOrNull()
                if (last != null && i - last[1] <= BRIDGE_BLOCKS) last[1] = j else runs += intArrayOf(i, j)
                i = j
            } else {
                i++
            }
        }
        return runs.map { (it[0] * HOP) until minOf(x.size, it[1] * HOP) }.filter { it.last - it.first + 1 >= MIN_KEEP }
    }

    private fun score(x: FloatArray): Float {
        val e = Voiceprint.embed(ex, x)
        var d = 0f
        for (k in e.indices) d += e[k] * ref[k]
        return d
    }

    fun release() = ex.release()

    companion object {
        private const val WIN = 2 * Audio.RATE
        private const val HOP = Audio.RATE / 2
        private const val BRIDGE_BLOCKS = 2
        private const val MIN_KEEP = Audio.RATE * 8 / 10

        /** A filter for the current voiceprint, or null if the user hasn't enrolled or turned it off. */
        fun create(context: Context): SpeakerFilter? {
            if (!Voiceprint.isActive(context)) return null
            val ref = Voiceprint.load(context) ?: return null
            return SpeakerFilter(Voiceprint.newExtractor(context), ref, Voiceprint.threshold(context))
        }
    }
}

object Audio {
    const val RATE = 16000

    /** Linear resampling; a no-op when the rate already matches. */
    fun toRate(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || from <= 0) return x
        val n = (x.size.toLong() * to / from).toInt()
        return FloatArray(n) { i ->
            val pos = i.toDouble() * from / to
            val j = pos.toInt()
            val f = (pos - j).toFloat()
            val a = x[minOf(j, x.size - 1)]
            val b = x[minOf(j + 1, x.size - 1)]
            a + (b - a) * f
        }
    }
}
