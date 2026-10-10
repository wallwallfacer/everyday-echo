package com.everydayecho.memorykeeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.everydayecho.memorykeeper.transcribe.ModelManager
import com.everydayecho.memorykeeper.transcribe.Transcriber
import kotlinx.coroutines.launch

/** Settings block: local ASR model download + manual "transcribe now". */
@Composable
fun TranscriptionSection() {
    val ctx = LocalContext.current
    val app = MemoryKeeperApp.get()
    val scope = rememberCoroutineScope()

    var ready by remember { mutableStateOf(ModelManager.isReady(ctx)) }
    var progress by remember { mutableStateOf<Float?>(null) }
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
            if (ready) "模型：已就绪　　待转录语音：$pending 段" else "模型：未下载（约 160 MB，建议在 Wi-Fi 下载）",
            style = MaterialTheme.typography.bodyMedium,
        )
        progress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        }
        if (!ready) {
            Button(
                enabled = progress == null,
                onClick = {
                    message = null
                    progress = 0f
                    scope.launch {
                        val r = ModelManager.download(ctx) { progress = it }
                        progress = null
                        ready = ModelManager.isReady(ctx)
                        message = r.fold({ "下载完成" }, { "下载失败：${it.message}" })
                    }
                },
            ) { Text(if (progress == null) "下载语音转文字模型" else "下载中…") }
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
