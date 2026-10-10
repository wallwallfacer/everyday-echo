package com.everydayecho.memorykeeper.record

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Records the microphone continuously as AAC/m4a chunks (~10 min each,
 * 16 kHz mono 32 kbps ≈ 14 MB/hour). Files go to [dirFor] (one folder per day).
 * Used because many devices (e.g. Samsung without Google speech services)
 * have no system speech recognizer, so raw audio is the source of truth.
 */
class ChunkedAudioRecorder(
    private val context: Context,
    private val dirFor: (LocalDateTime) -> File,
    private val onStatus: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val chunkMs: Int = 10 * 60 * 1000,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    @Volatile private var running = false
    private var chunks = 0

    fun start() {
        if (running) return
        running = true
        main.post { beginChunk() }
    }

    fun stop() {
        running = false
        main.post { endChunk() }
    }

    private fun beginChunk() {
        if (!running) return
        val now = LocalDateTime.now()
        val dir = dirFor(now).also { it.mkdirs() }
        val file = File(dir, "${now.format(FMT)}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32000)
            r.setMaxDuration(chunkMs)
            r.setOutputFile(file.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) rotate()
            }
            r.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "recorder error $what/$extra")
                onError("录音出错 ($what/$extra)，重试中")
                rotate(delayMs = 2000)
            }
            r.prepare()
            r.start()
            recorder = r
            currentFile = file
            chunks++
            onStatus("正在录音，本次已保存 ${chunks - 1} 段")
        } catch (t: Throwable) {
            Log.e(TAG, "start chunk failed", t)
            runCatching { r.release() }
            file.delete()
            onError("无法开始录音：${t.message ?: t.javaClass.simpleName}")
            if (running) main.postDelayed({ beginChunk() }, 5000)
        }
    }

    private fun endChunk() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (t: Throwable) {
            // stop() throws if nothing was recorded
            currentFile?.delete()
        }
        runCatching { r.release() }
        currentFile = null
    }

    private fun rotate(delayMs: Long = 0) {
        endChunk()
        if (running) main.postDelayed({ beginChunk() }, delayMs)
    }

    companion object {
        private const val TAG = "MK.Recorder"
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}
