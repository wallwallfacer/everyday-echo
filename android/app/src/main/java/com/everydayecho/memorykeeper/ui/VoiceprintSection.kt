package com.everydayecho.memorykeeper.ui

import android.media.MediaRecorder
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.everydayecho.memorykeeper.service.MonitoringService
import com.everydayecho.memorykeeper.voice.Voiceprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Settings block: the user's voiceprint, used to keep only the user's own voice in recordings. */
@Composable
fun VoiceprintSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var enrolled by remember { mutableStateOf(Voiceprint.isEnrolled(ctx)) }
    var sampleCount by remember { mutableIntStateOf(Voiceprint.samples(ctx).size) }
    var enabled by remember { mutableStateOf(Voiceprint.isEnabled(ctx)) }
    var threshold by remember { mutableStateOf(Voiceprint.threshold(ctx)) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var resumeMonitoring by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(recorder) {
        seconds = 0
        while (recorder != null) {
            delay(1000)
            seconds++
        }
    }

    suspend fun rebuild(): Int {
        working = true
        message = "正在计算声纹…"
        val n = withContext(Dispatchers.Default) { runCatching { Voiceprint.rebuild(ctx) } }
        working = false
        enrolled = Voiceprint.isEnrolled(ctx)
        sampleCount = Voiceprint.samples(ctx).size
        return n.getOrElse { message = "计算声纹失败：${it.message}"; -1 }
    }

    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        runCatching { rec.stop() }
        rec.release()
        val f = recFile
        if (resumeMonitoring) {
            resumeMonitoring = false
            MonitoringService.start(ctx)
        }
        if (seconds < 5) {
            f?.delete()
            message = "录得太短了（至少 5 秒，建议 15 秒左右）"
            return
        }
        scope.launch {
            val used = rebuild()
            val total = sampleCount
            message = when {
                used < 0 -> message
                used == 0 -> "样本几乎没有声音，没用上。请靠近手机正常说话再录一次。"
                used < total -> "声纹已更新（用到 $used 段样本，${total - used} 段几乎没有声音被忽略）"
                else -> "声纹已更新（用到 $used 段样本）"
            }
        }
    }
    DisposableEffect(Unit) { onDispose { stopRecording() } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "录入你的声纹后，录音只保留你说话的部分：别人的声音、没有人声的部分会在保存前就去掉。" +
                "声纹模型（3D-Speaker CAM++，开源）在手机上运行。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            if (enrolled) "声纹：已录入（$sampleCount 段样本）" else "声纹：还没录入，目前会保留所有人的声音",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (recorder == null) {
            Button(
                enabled = !working,
                onClick = {
                    message = null
                    scope.launch {
                        if (MonitoringService.isRunning(ctx)) {
                            // The microphone can only be used by one recorder at a time.
                            resumeMonitoring = true
                            MonitoringService.stop(ctx)
                            delay(800)
                        }
                        val dir = Voiceprint.samplesDir(ctx).also { it.mkdirs() }
                        val file = File(dir, "sample_${System.currentTimeMillis()}.m4a")
                        runCatching {
                            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
                            rec.apply {
                                setAudioSource(MediaRecorder.AudioSource.MIC)
                                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                setAudioSamplingRate(16000)
                                setOutputFile(file.absolutePath)
                                prepare()
                                start()
                            }
                        }.onSuccess {
                            recFile = file
                            recorder = it
                        }.onFailure {
                            file.delete()
                            message = "无法录音：${it.message}"
                            if (resumeMonitoring) {
                                resumeMonitoring = false
                                MonitoringService.start(ctx)
                            }
                        }
                    }
                },
            ) { Text(if (enrolled) "再录一段声纹样本" else "录一段声纹（约 15 秒）") }
        } else {
            Text(
                "录音中 $seconds 秒…用平常的语气随便说点什么，比如介绍一下今天做了什么。15 秒左右就够。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { stopRecording() }) { Text("完成") }
        }
        Text(
            "也可以在「录音与转录」里，对某段确实是你说话的录音点「⋮ → 这是我的声音」，用真实环境的录音补充声纹，效果更好。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (enrolled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("只保留我的声音", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it; Voiceprint.setEnabled(ctx, it) })
            }
            if (enabled) {
                Text(
                    "判定严格度：${"%.2f".format(threshold)}（越高越容易把别人去掉，但也更可能误删你自己的话；默认 ${Voiceprint.DEFAULT_THRESHOLD}）",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    onValueChangeFinished = { Voiceprint.setThreshold(ctx, threshold) },
                    valueRange = 0.30f..0.65f,
                    steps = 6,
                )
            }
            OutlinedButton(enabled = !working && recorder == null, onClick = { confirmClear = true }) { Text("清空声纹") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空声纹？") },
            text = { Text("所有声纹样本都会删除。之后录音会保留所有人的声音，直到你重新录入。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    Voiceprint.clear(ctx)
                    enrolled = false
                    sampleCount = 0
                    message = "声纹已清空"
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}
