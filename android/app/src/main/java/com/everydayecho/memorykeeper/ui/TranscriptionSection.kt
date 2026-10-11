package com.everydayecho.memorykeeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.everydayecho.memorykeeper.MemoryKeeperApp
import com.everydayecho.memorykeeper.transcribe.DownloadState
import com.everydayecho.memorykeeper.transcribe.ModelManager
import com.everydayecho.memorykeeper.transcribe.Transcriber
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Settings block: local ASR model download + manual "transcribe now". */
@Composable
fun TranscriptionSection() {
    val ctx = LocalContext.current
    val app = MemoryKeeperApp.get()
    val scope = rememberCoroutineScope()

    var ready by remember { mutableStateOf(ModelManager.isReady(ctx)) }
    var dl by remember { mutableStateOf<DownloadState?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var customUrl by remember { mutableStateOf(ModelManager.customUrl(ctx)) }
    var pending by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun refreshPending() {
        pending = app.repository.listRecordingDays().sumOf { app.repository.pendingRecordings(it.date).size }
    }
    LaunchedEffect(Unit) { refreshPending() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "录音由本地模型（SenseVoice，开源）转成文字，全程在手机上完成，不上传音频。" +
                "手机充电且空闲时会自动批量转录；生成信件前也会补转剩余的。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            if (ready) "模型：已就绪　　待转录语音：$pending 段" else "模型：未下载（约 160–230 MB，建议在 Wi-Fi 下载）",
            style = MaterialTheme.typography.bodyMedium,
        )
        dl?.let { d ->
            val frac = if (d.total > 0) (d.downloaded.toFloat() / d.total).coerceIn(0f, 1f) else null
            if (frac != null) {
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                buildString {
                    append("${d.phase} · ${d.source}")
                    if (d.attempt > 1) append("（第 ${d.attempt} 次尝试，已从断点续传）")
                    append("\n${mb(d.downloaded)}")
                    if (d.total > 0) append(" / ${mb(d.total)}（${(frac!! * 100).toInt()}%）")
                    if (d.phase == "下载中" && d.bytesPerSec > 0) {
                        append(" · ${"%.2f".format(d.bytesPerSec / 1048576.0)} MB/s")
                        if (d.total > d.downloaded) append(" · 剩余约 ${eta((d.total - d.downloaded) / d.bytesPerSec)}")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!ready) {
            if (dl == null) {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it; ModelManager.setCustomUrl(ctx, it) },
                    label = { Text("自定义下载地址（可选）") },
                    supportingText = {
                        Text("网络不通时可填模型包 .tar.bz2 直链，或 model.int8.onnx 直链（同目录需有 tokens.txt）")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        message = null
                        dl = DownloadState("连接中", "下载中", 0, 0, 0)
                        job = scope.launch {
                            try {
                                val r = ModelManager.download(ctx) { dl = it }
                                ready = ModelManager.isReady(ctx)
                                message = r.fold({ "下载完成" }, { "下载失败：${it.message}" })
                            } finally {
                                dl = null
                                job = null
                            }
                        }
                    },
                ) { Text(if (ModelManager.hasPartial(ctx)) "继续下载（断点续传）" else "下载语音转文字模型") }
            } else {
                OutlinedButton(onClick = {
                    job?.cancel()
                    message = "已暂停，已下载的部分会保留，下次点「继续下载」从断点接着下"
                }) { Text("暂停") }
            }
        } else {
            OutlinedButton(
                enabled = !working && pending > 0,
                onClick = {
                    working = true
                    message = "转录中…"
                    scope.launch {
                        val r = Transcriber(ctx, app.repository).transcribePending { d, t -> message = "转录中 $d / $t" }
                        working = false
                        refreshPending()
                        message = r.fold({ "已转录 $it 段" }, { "转录失败：${it.message}" })
                    }
                },
            ) { Text("立即转录待处理语音") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)

private fun eta(seconds: Long): String = when {
    seconds < 90 -> "${seconds} 秒"
    seconds < 5400 -> "${seconds / 60} 分钟"
    else -> "${seconds / 3600} 小时以上"
}
