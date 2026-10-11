package com.everydayecho.memorykeeper.voice

import android.content.Context
import com.everydayecho.memorykeeper.data.FileRepository
import com.everydayecho.memorykeeper.record.AacDecoder
import com.everydayecho.memorykeeper.record.AacEncoder
import com.everydayecho.memorykeeper.transcribe.Transcriber
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

/**
 * Re-filters already saved recordings: drops stretches with no voice (Silero VAD) and, when the
 * user has a voiceprint, stretches spoken by someone else. Kept parts are saved as new segments
 * (named by their own start time, pending transcription); the originals, with their transcript
 * lines, are deleted. Recordings that are already clean are left untouched.
 */
class RecordingCleaner(private val context: Context, private val repo: FileRepository) {

    data class Outcome(val checked: Int, val removed: Int, val trimmed: Int, val speakerFiltered: Boolean) {
        fun describe() = buildString {
            append("检查了 $checked 段：删除 $removed 段")
            append(if (speakerFiltered) "（没有人声或不是你的声音）" else "（没有人声）")
            if (trimmed > 0) append("，裁剪 $trimmed 段")
            if (!speakerFiltered) append("。录入声纹后还能去掉别人的声音")
        }
    }

    suspend fun cleanDay(date: LocalDate, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result<Outcome> =
        withContext(Dispatchers.Default) {
            Transcriber.lock.withLock {
                runCatching {
                    val files = repo.listRecordingFiles(date)
                    val filter = SpeakerFilter.create(context)
                    var removed = 0
                    var trimmed = 0
                    try {
                        files.forEachIndexed { i, f ->
                            coroutineContext.ensureActive()
                            when (cleanFile(date, f, filter)) {
                                Change.REMOVED -> removed++
                                Change.TRIMMED -> trimmed++
                                Change.NONE -> {}
                            }
                            onProgress(i + 1, files.size)
                        }
                    } finally {
                        filter?.release()
                    }
                    Outcome(files.size, removed, trimmed, filter != null)
                }
            }
        }

    private enum class Change { NONE, REMOVED, TRIMMED }

    private suspend fun cleanFile(date: LocalDate, file: File, filter: SpeakerFilter?): Change {
        val pcm = runCatching { AacDecoder.decode(file) }.getOrNull() ?: return Change.NONE
        val x = Audio.toRate(pcm.samples, pcm.sampleRate, Audio.RATE)
        if (x.isEmpty()) return Change.NONE
        val keep = mutableListOf<IntRange>()
        for (seg in speech(x)) {
            if (filter == null) {
                keep += seg
            } else {
                filter.userRegions(x.copyOfRange(seg.first, seg.last + 1))
                    .forEach { r -> keep += (seg.first + r.first)..(seg.first + r.last) }
            }
        }
        val padded = merge(keep.map { maxOf(0, it.first - PAD)..minOf(x.size - 1, it.last + PAD) })
        if (padded.isEmpty()) {
            repo.deleteRecordings(date, listOf(file))
            return Change.REMOVED
        }
        val keptSamples = padded.sumOf { it.last - it.first + 1 }
        if (padded.size == 1 && keptSamples >= x.size * 9 / 10) return Change.NONE

        val start = runCatching { LocalTime.parse(file.nameWithoutExtension, FMT) }.getOrNull() ?: return Change.NONE
        val dir = file.parentFile ?: return Change.NONE
        val created = mutableListOf<File>()
        for (r in padded) {
            var t = start.plusNanos(r.first.toLong() * 1_000_000_000L / Audio.RATE)
            var out = File(dir, "${t.format(FMT)}.aac")
            while (out.exists() && out != file) {
                t = t.plusSeconds(1)
                out = File(dir, "${t.format(FMT)}.aac")
            }
            if (out == file) out = File(dir, "${t.format(FMT)}.tmp.aac")
            val shorts = ShortArray(r.last - r.first + 1) { i ->
                (x[r.first + i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            }
            AacEncoder.encode(shorts, out)
            created += out
        }
        repo.deleteRecordings(date, listOf(file))
        // A piece that starts in the same second as the original took a temporary name.
        created.filter { it.name.endsWith(".tmp.aac") }.forEach { it.renameTo(File(dir, it.name.replace(".tmp.aac", ".aac"))) }
        return Change.TRIMMED
    }

    /** Speech ranges in 16 kHz [x], via the bundled Silero VAD. */
    private fun speech(x: FloatArray): List<IntRange> {
        val vad = Vad(
            context.assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.6f,
                    minSilenceDuration = 1.0f,
                    minSpeechDuration = 0.5f,
                    windowSize = WINDOW,
                    maxSpeechDuration = 30f,
                ),
                sampleRate = Audio.RATE,
                numThreads = 1,
            ),
        )
        val out = mutableListOf<IntRange>()
        try {
            fun drain() {
                while (!vad.empty()) {
                    val s = vad.front()
                    vad.pop()
                    out += s.start until s.start + s.samples.size
                }
            }
            var i = 0
            while (i < x.size) {
                val end = minOf(i + WINDOW, x.size)
                vad.acceptWaveform(x.copyOfRange(i, end))
                drain()
                i = end
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        return out
    }

    private fun merge(rs: List<IntRange>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (r in rs.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }

    companion object {
        private const val WINDOW = 512
        private const val PAD = Audio.RATE * 3 / 10
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}
