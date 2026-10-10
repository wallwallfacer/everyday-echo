package com.everydayecho.memorykeeper.transcribe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Local ASR model: SenseVoice-Small int8 (zh/en/ja/ko/yue) from the
 * k2-fsa/sherpa-onnx model releases. Downloaded on first use (~160 MB) because
 * it is too big to ship in the APK or the repo.
 */
object ModelManager {
    private const val URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"

    fun modelDir(context: Context) = File(context.filesDir, "models/sensevoice")
    fun modelFile(context: Context) = File(modelDir(context), "model.int8.onnx")
    fun tokensFile(context: Context) = File(modelDir(context), "tokens.txt")

    fun isReady(context: Context) = modelFile(context).exists() && tokensFile(context).exists()

    fun delete(context: Context) {
        modelDir(context).deleteRecursively()
    }

    /** [onProgress] gets 0f..1f. */
    suspend fun download(context: Context, onProgress: (Float) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = modelDir(context).also { it.mkdirs() }
            val tmp = File(context.cacheDir, "sensevoice.tar.bz2")
            val http = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            http.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                if (!r.isSuccessful) error("下载失败：HTTP ${r.code}")
                val body = r.body ?: error("下载失败：空响应")
                val total = body.contentLength().takeIf { it > 0 } ?: 163_000_000L
                var done = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(0.9f * done / total)
                        }
                    }
                }
            }
            var found = 0
            TarArchiveInputStream(BZip2CompressorInputStream(tmp.inputStream().buffered())).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    if (!e.isDirectory && (name == "model.int8.onnx" || name == "tokens.txt")) {
                        File(dir, name).outputStream().use { tar.copyTo(it) }
                        found++
                    }
                }
            }
            tmp.delete()
            if (found < 2) {
                delete(context)
                error("模型包内容不完整")
            }
            onProgress(1f)
        }
    }
}
