package com.everydayecho.memorykeeper.record

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Listens to the microphone and runs a local VAD (Silero via sherpa-onnx) on the
 * stream. Only speech is kept: each utterance (pauses < 1.5 s merged, max 30 s,
 * ~0.3 s padding) is saved as its own AAC file under [dirFor]; silence and
 * noise-only stretches are dropped. Transcription happens later, in batch.
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
        val rate = SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate * 2),
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
        val vad = try {
            Vad(
                context.assets,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 1.5f,
                        minSpeechDuration = 0.5f,
                        windowSize = FRAME,
                        maxSpeechDuration = 30f,
                    ),
                    sampleRate = rate,
                    numThreads = 1,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "VAD load failed", t)
            onError("语音检测模型加载失败：${t.message}")
            rec.release()
            return
        }

        val recStart = LocalDateTime.now()
        // Recent PCM, so each segment can be padded and cut out by absolute sample index.
        val ring = ArrayDeque<ShortArray>()
        var ringFirstSample = 0L
        var total = 0L

        fun cut(from: Long, to: Long): ShortArray {
            val s = maxOf(from, ringFirstSample)
            val e = minOf(to, total)
            if (e <= s) return ShortArray(0)
            val out = ShortArray((e - s).toInt())
            var pos = s
            while (pos < e) {
                val fi = ((pos - ringFirstSample) / FRAME).toInt()
                val off = ((pos - ringFirstSample) % FRAME).toInt()
                val frame = ring[fi]
                val n = minOf(FRAME - off, (e - pos).toInt())
                System.arraycopy(frame, off, out, (pos - s).toInt(), n)
                pos += n
            }
            return out
        }

        fun drain() {
            while (!vad.empty()) {
                val sg: SpeechSegment = vad.front()
                vad.pop()
                val pcm = cut(sg.start - PAD_SAMPLES, sg.start + sg.samples.size + PAD_SAMPLES)
                if (pcm.isNotEmpty()) {
                    submit(pcm, recStart.plusNanos(sg.start.toLong() * 1_000_000_000L / rate))
                }
            }
        }

        rec.startRecording()
        onStatus("监听中：等待有效语音")
        val frameBuf = ShortArray(FRAME)
        val floats = FloatArray(FRAME)
        try {
            while (running) {
                var filled = 0
                while (filled < FRAME && running) {
                    val n = rec.read(frameBuf, filled, FRAME - filled)
                    if (n < 0) { onError("录音读取失败 ($n)"); running = false; break }
                    filled += n
                }
                if (filled < FRAME) break
                ring.addLast(frameBuf.copyOf())
                total += FRAME
                while (ring.size > RING_FRAMES) { ring.removeFirst(); ringFirstSample += FRAME }
                for (i in 0 until FRAME) floats[i] = frameBuf[i] / 32768f
                vad.acceptWaveform(floats)
                drain()
            }
            vad.flush()
            drain()
        } finally {
            runCatching { rec.stop() }
            rec.release()
            vad.release()
        }
    }

    private fun submit(pcm: ShortArray, start: LocalDateTime) {
        val file = File(dirFor(start).also { it.mkdirs() }, "${start.format(FMT)}.aac")
        runCatching {
            encoder.execute {
                try {
                    AacEncoder.encode(pcm, file)
                    savedSegments++
                    savedSeconds += pcm.size / SAMPLE_RATE
                    onStatus("监听中：已保存 $savedSegments 段有效语音（共 ${savedSeconds / 60} 分钟），待转录")
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
        private const val SAMPLE_RATE = 16000
        private const val FRAME = 512
        private const val PAD_SAMPLES = 4800        // 0.3 s
        private const val RING_FRAMES = 1900        // ~60 s of history (segments are <= 30 s)
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}
