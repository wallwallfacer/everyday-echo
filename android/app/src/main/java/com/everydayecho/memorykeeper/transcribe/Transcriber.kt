package com.everydayecho.memorykeeper.transcribe

import android.content.Context
import com.everydayecho.memorykeeper.data.FileRepository
import com.everydayecho.memorykeeper.data.TranscriptFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Result of a batch run: [failed] segments crashed the recognizer and are skipped from now on. */
data class TranscribeOutcome(
    val done: Int,
    val failed: Int = 0,
    val crashReason: String? = null,
    val removed: Int = 0,
) {
    fun describe() = buildString {
        append("已转录 $done 段")
        if (removed > 0) append("，其中 $removed 段没有人声，已删除")
        if (failed > 0) append("；$failed 段导致转录进程崩溃，已跳过（$crashReason）")
    }
}

/**
 * Batch, on-device transcription of saved speech segments into the daily transcript.
 * Recognition itself runs in a separate process ([RemoteAsr]/[AsrService]), so a native
 * crash only fails the transcription instead of closing the app.
 */
class Transcriber(private val context: Context, private val repo: FileRepository) {

    /** Transcribes every untranscribed segment (all retained days, or only [onlyDate]). */
    suspend fun transcribePending(
        onlyDate: LocalDate? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<TranscribeOutcome> = withContext(Dispatchers.Default) {
        lock.withLock {
            runCatching {
                check(ModelManager.isReady(context)) { "语音转文字模型还没下载" }
                val days = repo.listRecordingDays().map { it.date }.filter { onlyDate == null || it == onlyDate }
                    .sorted()
                val work = days.flatMap { d -> repo.pendingRecordings(d).map { d to it } }
                if (work.isEmpty()) return@runCatching TranscribeOutcome(0)
                val dateOf = work.associate { it.second.absolutePath to it.first }
                var done = 0
                var failed = 0
                var reason: String? = null
                var removed = 0
                var remaining = work.map { it.second }
                while (remaining.isNotEmpty()) {
                    val finished = HashSet<String>()
                    try {
                        RemoteAsr.run(context, remaining) { file, text ->
                            val date = dateOf.getValue(file.absolutePath)
                            if (!save(date, file, text)) removed++
                            finished += file.absolutePath
                            done++
                            onProgress(done + failed, work.size)
                        }
                        remaining = emptyList()
                    } catch (e: AsrCrashException) {
                        val culprit = remaining.firstOrNull { it.absolutePath == e.culprit } ?: throw loadCrash(e)
                        recordCrash(e.reason)
                        repo.markFailed(dateOf.getValue(culprit.absolutePath), culprit)
                        failed++
                        reason = e.reason
                        onProgress(done + failed, work.size)
                        if (failed >= MAX_CRASHES) error("转录进程连续崩溃 $failed 次，已停止（${e.reason}）")
                        remaining = remaining.filter { it.absolutePath !in finished && it != culprit }
                    }
                }
                work.map { it.first }.distinct().forEach { repo.sortTranscript(it) }
                TranscribeOutcome(done, failed, reason, removed)
            }
        }
    }

    /** Transcribes a single segment on demand. Returns the recognized text (blank if nothing was recognized). */
    suspend fun transcribeFile(date: LocalDate, file: File): Result<String> = withContext(Dispatchers.Default) {
        lock.withLock {
            runCatching {
                check(ModelManager.isReady(context)) { "语音转文字模型还没下载" }
                var result: String? = null
                try {
                    RemoteAsr.run(context, listOf(file)) { _, text -> result = text ?: error("无法解码这段录音") }
                } catch (e: AsrCrashException) {
                    if (e.culprit == null) throw loadCrash(e)
                    recordCrash(e.reason)
                    repo.markFailed(date, file)
                    error("转录这段录音时转录进程崩溃（${e.reason}）")
                }
                val text = result.orEmpty()
                save(date, file, text)
                repo.sortTranscript(date)
                text
            }
        }
    }

    /**
     * Stores the result of one segment. A segment in which nothing was recognized has no voice
     * worth keeping, so it is deleted; returns false in that case.
     */
    private suspend fun save(date: LocalDate, file: File, text: String?): Boolean {
        if (text != null && !hasWords(text)) {
            repo.deleteRecordings(date, listOf(file))
            return false
        }
        if (!text.isNullOrBlank()) {
            repo.appendTranscript(
                date,
                TranscriptFragment(
                    timestamp = LocalDateTime.of(date, parseTime(file.nameWithoutExtension)),
                    text = text,
                    speaker = "self?",
                ),
            )
        }
        repo.markTranscribed(date, file)
        return true
    }

    /** False for empty or punctuation-only results, which SenseVoice gives for audio without speech. */
    private fun hasWords(text: String) = text.any { it.isLetterOrDigit() }

    private fun loadCrash(e: AsrCrashException): Exception {
        recordCrash("加载模型时：" + e.reason)
        return IllegalStateException(
            "转录进程在加载模型时崩溃（${e.reason}）。模型文件可能损坏，可在「设置 → 本地语音转文字」删除模型后重新下载。",
        )
    }

    private fun recordCrash(reason: String) {
        runCatching {
            lastCrashFile(context).writeText("${LocalDateTime.now().format(STAMP_FMT)}  $reason")
        }
    }

    private fun parseTime(name: String): LocalTime =
        runCatching { LocalTime.parse(name, DateTimeFormatter.ofPattern("HHmmss")) }.getOrDefault(LocalTime.MIDNIGHT)

    companion object {
        /** Serializes everything that reads or rewrites saved segments (transcription, cleanup). */
        val lock = Mutex()
        private const val MAX_CRASHES = 3
        private val STAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        private fun lastCrashFile(context: Context) = File(context.filesDir, "asr_last_crash.txt")

        /** The most recent crash of the transcription process, for display in Settings. */
        fun lastCrash(context: Context): String? =
            lastCrashFile(context).takeIf { it.exists() }?.readText()?.ifBlank { null }
    }
}
