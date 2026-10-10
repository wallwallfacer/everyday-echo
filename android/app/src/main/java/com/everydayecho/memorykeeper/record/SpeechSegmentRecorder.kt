package com.everydayecho.memorykeeper.record

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.everydayecho.memorykeeper.vad.SileroVad
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Listens to the microphone, runs a local VAD (Silero) on every 32 ms frame and
 * only keeps audio that contains speech. Each utterance (with ~0.3 s padding,
 * pauses < 1.5 s merged, max 10 min) is saved as its own AAC file under
 * [dirFor]; silence and noise-only stretches are dropped.
 */
class SpeechSegmentRecorder(
    private val context: Context,
    private val dirFor: (LocalDateTime) -> File,
    private val onStatus: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val encoder = Executors.newSingleThreadExecutor()
    @Volatile private var savedSegments = 0
    @Volatile private var savedSeconds = 0L

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "mk-recorder").also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(3000) }
        encoder.shutdown()
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SileroVad.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SileroVad.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SileroVad.SAMPLE_RATE * 2),
            )
        } catch (t: Throwable) {
            onError("无法打开麦克风：${t.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            onError("麦克风初始化失败（权限或被占用）")
            rec.release()
            return
        }
        val vad = runCatching { SileroVad(context) }.getOrElse {
            Log.e(TAG, "VAD load failed", it)
            onError("语音检测模型加载失败，将录下所有声音：${it.message}")
            null
        }

        rec.startRecording()
        onStatus("监听中：等待有效语音")
        val frameBuf = ShortArray(SileroVad.FRAME)
        val preroll = ArrayDeque<ShortArray>()
        var seg: ArrayList<ShortArray>? = null
        var segStart = LocalDateTime.now()
        var speechFrames = 0
        val pendingSilence = ArrayList<ShortArray>()

        fun finish() {
            val s = seg ?: return
            if (speechFrames >= MIN_SPEECH_FRAMES) {
                s.addAll(pendingSilence.take(PAD_FRAMES))
                submit(s, segStart)
            }
            seg = null
            speechFrames = 0
            pendingSilence.clear()
        }

        try {
            while (running) {
                var filled = 0
                while (filled < frameBuf.size && running) {
                    val n = rec.read(frameBuf, filled, frameBuf.size - filled)
                    if (n < 0) { onError("录音读取失败 ($n)"); running = false; break }
                    filled += n
                }
                if (filled < frameBuf.size) break
                val frame = frameBuf.copyOf()
                val p = vad?.speechProbability(frame) ?: 1f
                val inSeg = seg != null
                val speaking = if (inSeg) p >= OFF_THRESHOLD else p >= ON_THRESHOLD

                if (!inSeg) {
                    if (speaking) {
                        segStart = LocalDateTime.now().minusNanos(preroll.size * FRAME_NANOS)
                        seg = ArrayList(preroll).also { it.add(frame) }
                        preroll.clear()
                        speechFrames = 1
                    } else {
                        preroll.addLast(frame)
                        if (preroll.size > PAD_FRAMES) preroll.removeFirst()
                    }
                } else {
                    val s = seg!!
                    if (speaking) {
                        s.addAll(pendingSilence); pendingSilence.clear()
                        s.add(frame); speechFrames++
                    } else {
                        pendingSilence.add(frame)
                        if (pendingSilence.size >= HANGOVER_FRAMES) finish()
                    }
                    if (seg != null && s.size >= MAX_FRAMES) finish()
                }
            }
        } finally {
            finish()
            runCatching { rec.stop() }
            rec.release()
            vad?.close()
        }
    }

    private fun submit(frames: List<ShortArray>, start: LocalDateTime) {
        val pcm = ShortArray(frames.size * SileroVad.FRAME)
        frames.forEachIndexed { i, f -> System.arraycopy(f, 0, pcm, i * SileroVad.FRAME, f.size) }
        val file = File(dirFor(start).also { it.mkdirs() }, "${start.format(FMT)}.aac")
        runCatching {
            encoder.execute {
                try {
                    AacEncoder.encode(pcm, file)
                    savedSegments++
                    savedSeconds += pcm.size / SileroVad.SAMPLE_RATE
                    onStatus("监听中：已保存 $savedSegments 段有效语音（共 ${savedSeconds / 60} 分钟）")
                } catch (t: Throwable) {
                    Log.e(TAG, "encode failed", t)
                    file.delete()
                    onError("保存语音失败：${t.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "MK.Recorder"
        private const val ON_THRESHOLD = 0.5f
        private const val OFF_THRESHOLD = 0.35f
        private const val PAD_FRAMES = 10            // ~0.32 s pre/post padding
        private const val HANGOVER_FRAMES = 47       // ~1.5 s of silence ends a segment
        private const val MIN_SPEECH_FRAMES = 16     // < ~0.5 s of speech is discarded
        private const val MAX_FRAMES = 18_750        // 10 min
        private const val FRAME_NANOS = 32_000_000L
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}
