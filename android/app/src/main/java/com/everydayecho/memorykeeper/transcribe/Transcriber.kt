package com.everydayecho.memorykeeper.transcribe

import android.content.Context
import com.everydayecho.memorykeeper.data.FileRepository
import com.everydayecho.memorykeeper.data.TranscriptFragment
import com.everydayecho.memorykeeper.record.AacDecoder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Batch, on-device transcription of saved speech segments into the daily transcript. */
class Transcriber(private val context: Context, private val repo: FileRepository) {

    /** Transcribes every untranscribed segment (all retained days). Returns how many were processed. */
    suspend fun transcribePending(
        onlyDate: LocalDate? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Int> = withContext(Dispatchers.Default) {
        lock.withLock {
            runCatching {
                check(ModelManager.isReady(context)) { "语音转文字模型还没下载" }
                val days = repo.listRecordingDays().map { it.date }.filter { onlyDate == null || it == onlyDate }
                    .sorted()
                val work = days.flatMap { d -> repo.pendingRecordings(d).map { d to it } }
                if (work.isEmpty()) return@runCatching 0
                val recognizer = newRecognizer()
                try {
                    var done = 0
                    for ((date, file) in work) {
                        val text = recognize(recognizer, file)
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
                        done++
                        onProgress(done, work.size)
                    }
                    work.map { it.first }.distinct().forEach { repo.sortTranscript(it) }
                    done
                } finally {
                    recognizer.release()
                }
            }
        }
    }

    /** Transcribes a single segment on demand. Returns the recognized text (blank if nothing was recognized). */
    suspend fun transcribeFile(date: LocalDate, file: java.io.File): Result<String> = withContext(Dispatchers.Default) {
        lock.withLock {
            runCatching {
                check(ModelManager.isReady(context)) { "语音转文字模型还没下载" }
                val recognizer = newRecognizer()
                try {
                    val text = recognize(recognizer, file) ?: error("无法解码这段录音")
                    if (text.isNotBlank()) {
                        repo.appendTranscript(
                            date,
                            TranscriptFragment(
                                timestamp = LocalDateTime.of(date, parseTime(file.nameWithoutExtension)),
                                text = text,
                                speaker = "self?",
                            ),
                        )
                        repo.sortTranscript(date)
                    }
                    repo.markTranscribed(date, file)
                    text
                } finally {
                    recognizer.release()
                }
            }
        }
    }

    private fun newRecognizer() = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = ModelManager.modelFile(context).absolutePath,
                    language = "",
                    useInverseTextNormalization = true,
                ),
                tokens = ModelManager.tokensFile(context).absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
        ),
    )

    /** Returns recognized text, or null if the audio could not be decoded. */
    private fun recognize(recognizer: OfflineRecognizer, file: java.io.File): String? {
        val pcm = runCatching { AacDecoder.decode(file) }.getOrNull() ?: return null
        if (pcm.samples.isEmpty()) return null
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(pcm.samples, pcm.sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun parseTime(name: String): LocalTime =
        runCatching { LocalTime.parse(name, DateTimeFormatter.ofPattern("HHmmss")) }.getOrDefault(LocalTime.MIDNIGHT)

    companion object {
        private val lock = Mutex()
    }
}
