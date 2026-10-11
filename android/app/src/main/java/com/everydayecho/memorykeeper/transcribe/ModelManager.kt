package com.everydayecho.memorykeeper.transcribe

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Live download state shown in Settings. [total] is 0 while unknown. */
data class DownloadState(
    val source: String,
    val phase: String,
    val downloaded: Long,
    val total: Long,
    val bytesPerSec: Long,
    val attempt: Int = 1,
)

/**
 * Local ASR model: SenseVoice-Small int8 (zh/en/ja/ko/yue) from the
 * k2-fsa/sherpa-onnx model releases. Downloaded on first use (~160-230 MB) because
 * it is too big to ship in the APK or the repo.
 *
 * Downloads are resumable (HTTP Range, partial files are kept), retried, and fall
 * back across several sources. A custom URL can be set for restricted networks.
 */
object ModelManager {
    private const val GITHUB_TAR =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    private const val HF_PATH =
        "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/resolve/main/"

    private class Source(val name: String, val tarUrl: String? = null, val modelBase: String? = null)

    private const val MAX_ATTEMPTS = 3

    fun modelDir(context: Context) = File(context.filesDir, "models/sensevoice")
    fun modelFile(context: Context) = File(modelDir(context), "model.int8.onnx")
    fun tokensFile(context: Context) = File(modelDir(context), "tokens.txt")

    fun isReady(context: Context) = modelFile(context).exists() && tokensFile(context).exists()

    fun delete(context: Context) {
        modelDir(context).deleteRecursively()
    }

    /** True if an interrupted download left partial data to resume from. */
    fun hasPartial(context: Context): Boolean =
        File(context.cacheDir, "sensevoice.tar.bz2.part").exists() ||
            File(modelDir(context), "model.int8.onnx.part").exists()

    private fun prefs(context: Context) = context.getSharedPreferences("mk_model", Context.MODE_PRIVATE)
    fun customUrl(context: Context): String = prefs(context).getString("custom_url", "").orEmpty()
    fun setCustomUrl(context: Context, url: String) {
        prefs(context).edit().putString("custom_url", url.trim()).apply()
    }

    private fun sources(context: Context): List<Source> {
        val list = mutableListOf<Source>()
        val custom = customUrl(context)
        if (custom.isNotBlank()) {
            list += if (custom.endsWith(".onnx")) {
                Source("自定义地址", modelBase = custom.substringBeforeLast('/') + "/")
            } else {
                Source("自定义地址", tarUrl = custom)
            }
        }
        list += Source("GitHub", tarUrl = GITHUB_TAR)
        list += Source("Hugging Face", modelBase = "https://huggingface.co/$HF_PATH")
        list += Source("HF 镜像 (hf-mirror.com)", modelBase = "https://hf-mirror.com/$HF_PATH")
        return list
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // stall detection; resumes on retry
        .retryOnConnectionFailure(true)
        .build()

    /** Downloads (resumably) and installs the model. Cancel the calling coroutine to abort; partial data is kept. */
    suspend fun download(context: Context, onState: (DownloadState) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            for (src in sources(context)) {
                var attempt = 1
                while (attempt <= MAX_ATTEMPTS) {
                    try {
                        install(context, src, attempt, onState)
                        return@withContext Result.success(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        // Network problem (timeout, reset, ...): resume from the partial file.
                        if (attempt == MAX_ATTEMPTS) {
                            errors += "${src.name}：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
                        } else {
                            delay(2000L * attempt)
                        }
                        attempt++
                    } catch (e: Throwable) {
                        errors += "${src.name}：${e.message ?: e.javaClass.simpleName}"
                        break
                    }
                }
            }
            Result.failure(IllegalStateException("所有下载源都失败了\n" + errors.joinToString("\n")))
        }

    private suspend fun install(context: Context, src: Source, attempt: Int, onState: (DownloadState) -> Unit) {
        val dir = modelDir(context).also { it.mkdirs() }
        if (src.tarUrl != null) {
            val part = File(context.cacheDir, "sensevoice.tar.bz2.part")
            downloadTo(src.tarUrl, part, src.name, "下载中", attempt, onState)
            try {
                extract(part, dir, src.name, onState)
            } catch (e: Throwable) {
                if (e !is CancellationException) part.delete() // corrupt archive: restart from scratch
                throw e
            }
            part.delete()
        } else {
            val base = src.modelBase!!
            downloadTo(base + "tokens.txt", File(dir, "tokens.txt.part"), src.name, "下载中", attempt, onState)
            downloadTo(base + "model.int8.onnx", File(dir, "model.int8.onnx.part"), src.name, "下载中", attempt, onState)
            File(dir, "tokens.txt.part").renameTo(File(dir, "tokens.txt"))
            File(dir, "model.int8.onnx.part").renameTo(File(dir, "model.int8.onnx"))
        }
        check(isReady(context)) { "模型文件不完整" }
    }

    private suspend fun downloadTo(
        url: String,
        part: File,
        source: String,
        phase: String,
        attempt: Int,
        onState: (DownloadState) -> Unit,
    ) {
        val have = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { r ->
            if (r.code == 416) return // partial file already complete
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}（$url）")
            val body = r.body ?: throw IOException("空响应")
            val resumed = r.code == 206
            val start = if (resumed) have else 0L
            val total = if (resumed) {
                r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: 0L
            } else {
                body.contentLength().takeIf { it > 0 } ?: 0L
            }
            var done = start
            var lastTick = System.nanoTime()
            var lastBytes = done
            var speed = 0L
            java.io.FileOutputStream(part, resumed).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastTick >= 500_000_000L) {
                            val inst = (done - lastBytes) * 1_000_000_000L / (now - lastTick)
                            speed = if (speed == 0L) inst else (speed * 0.6 + inst * 0.4).toLong()
                            lastTick = now
                            lastBytes = done
                            onState(DownloadState(source, phase, done, total, speed, attempt))
                        }
                    }
                }
            }
            onState(DownloadState(source, phase, done, total, speed, attempt))
        }
    }

    private suspend fun extract(tar: File, dir: File, source: String, onState: (DownloadState) -> Unit) {
        val size = tar.length()
        var counted = 0L
        val counting = object : FilterInputStream(tar.inputStream().buffered()) {
            override fun read(): Int = super.read().also { if (it >= 0) counted++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) counted += it }
        }
        var found = 0
        var lastTick = 0L
        TarArchiveInputStream(BZip2CompressorInputStream(counting as InputStream)).use { t ->
            while (true) {
                coroutineContext.ensureActive()
                val e = t.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (!e.isDirectory && (name == "model.int8.onnx" || name == "tokens.txt")) {
                    val buf = ByteArray(64 * 1024)
                    File(dir, "$name.part").outputStream().use { out ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = t.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            val now = System.nanoTime()
                            if (now - lastTick >= 300_000_000L) {
                                lastTick = now
                                onState(DownloadState(source, "解压中", counted, size, 0))
                            }
                        }
                    }
                    File(dir, "$name.part").renameTo(File(dir, name))
                    found++
                }
            }
        }
        if (found < 2) {
            dir.listFiles()?.forEach { it.delete() }
            throw IllegalStateException("模型包内容不完整")
        }
    }
}
