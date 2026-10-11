package com.everydayecho.memorykeeper.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.everydayecho.memorykeeper.MemoryKeeperApp
import com.everydayecho.memorykeeper.transcribe.ModelManager
import com.everydayecho.memorykeeper.transcribe.Transcriber
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

private data class DaySummary(val date: LocalDate, val segments: Int, val pending: Int, val hasTranscript: Boolean)

private data class Segment(
    val file: File,
    val time: String,
    val text: String?,
    val transcribed: Boolean,
    val failed: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(onBack: () -> Unit) {
    val app = MemoryKeeperApp.get()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var days by remember { mutableStateOf<List<DaySummary>>(emptyList()) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    var exportAll by remember { mutableStateOf(false) }

    suspend fun loadDays() {
        val rec = app.repository.listRecordingDays().associateBy { it.date }
        val tr = app.repository.listTranscriptDates().toSet()
        days = (rec.keys + tr).sortedDescending().map { d ->
            DaySummary(d, rec[d]?.files ?: 0, app.repository.pendingRecordings(d).size, d in tr)
        }
    }
    LaunchedEffect(selected) { if (selected == null) loadDays() }

    val day = selected
    if (day != null) {
        DayView(day, onBack = { selected = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("录音与转录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { exportAll = true }, enabled = days.any { it.hasTranscript }) { Text("导出全部转录") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    "只保留最近 7 天。点某一天可以播放录音、查看转录，并对没转录的录音手动转录。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
            if (days.isEmpty()) item { Text("（暂无录音或转录）", modifier = Modifier.padding(vertical = 16.dp)) }
            items(days) { d ->
                Column(Modifier.fillMaxWidth().clickable { selected = d.date }.padding(vertical = 14.dp)) {
                    Text(d.date.toString(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append("${d.segments} 段录音")
                            if (d.pending > 0) append(" · ${d.pending} 段待转录")
                            if (d.hasTranscript) append(" · 有转录")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                HorizontalDivider()
            }
        }
    }

    if (exportAll) {
        ConfirmShareDialog(
            title = "导出全部转录？",
            onDismiss = { exportAll = false },
            onConfirm = {
                exportAll = false
                scope.launch { app.repository.exportTranscripts(null)?.let { share(ctx, it, "application/zip") } }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayView(date: LocalDate, onBack: () -> Unit) {
    val app = MemoryKeeperApp.get()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var full by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchStatus by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) } // "transcript" | "audio"
    val modelReady = ModelManager.isReady(ctx)
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playing = null
    }

    fun play(file: File) {
        val wasPlaying = playing == file
        stopPlayback()
        if (wasPlaying) return
        runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { stopPlayback() }
            mp.prepare()
            mp.start()
            player = mp
            playing = file
        }.onFailure { message = "无法播放：${it.message}"; stopPlayback() }
    }

    suspend fun load() {
        val files = app.repository.listRecordingFiles(date)
        val map = app.repository.segmentTranscripts(date)
        val done = app.repository.transcribedNames(date)
        val failed = app.repository.failedNames(date)
        segments = files.map { f ->
            val key = f.nameWithoutExtension
            val t = if (key.length == 6) "${key.substring(0, 2)}:${key.substring(2, 4)}:${key.substring(4)}" else key
            Segment(f, t, map[key], f.name in done, f.name in failed)
        }
        full = app.repository.readTranscript(date)
    }
    LaunchedEffect(date) { load() }
    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    val pendingCount = segments.count { !it.transcribed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(date.toString()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirm = "transcript" }, enabled = full.isNotBlank()) { Text("导出转录") }
                    OutlinedButton(onClick = { confirm = "audio" }, enabled = segments.isNotEmpty()) { Text("导出录音") }
                }
            }
            if (segments.isNotEmpty() && pendingCount > 0) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            enabled = modelReady && batchStatus == null,
                            onClick = {
                                batchStatus = "转录中…"
                                scope.launch {
                                    val r = Transcriber(ctx, app.repository)
                                        .transcribePending(date) { d, t -> batchStatus = "转录中 $d / $t" }
                                    batchStatus = null
                                    message = r.fold({ it.describe() }, { "转录失败：${it.message}" })
                                    load()
                                }
                            },
                        ) { Text(batchStatus ?: "转录全部待处理（$pendingCount 段）") }
                        if (!modelReady) {
                            Text(
                                "还没下载语音转文字模型：请到「设置 → 本地语音转文字」下载。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            message?.let { item { Text(it, style = MaterialTheme.typography.labelMedium) } }
            if (segments.isEmpty()) item { Text("（这一天没有保存的录音）", color = MaterialTheme.colorScheme.secondary) }
            items(segments) { seg ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { play(seg.file) }) {
                                Icon(
                                    if (playing == seg.file) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    contentDescription = if (playing == seg.file) "停止" else "播放",
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(seg.time, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "约 ${maxOf(1L, seg.file.length() * 8 / 32000)} 秒",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            if (seg.file.name in busy) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else if (seg.text == null) {
                                TextButton(
                                    enabled = modelReady,
                                    onClick = {
                                        busy = busy + seg.file.name
                                        scope.launch {
                                            val r = Transcriber(ctx, app.repository).transcribeFile(date, seg.file)
                                            busy = busy - seg.file.name
                                            r.onFailure { message = "转录失败：${it.message}" }
                                            load()
                                        }
                                    },
                                ) { Text(if (seg.transcribed) "重新转录" else "转录") }
                            }
                        }
                        when {
                            seg.text != null -> SelectionContainer { Text(seg.text, style = MaterialTheme.typography.bodyMedium) }
                            seg.transcribed -> Text("（没有识别出文字）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                            seg.failed -> Text("（转录时出错，已跳过；可点「转录」重试）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            else -> Text("（未转录）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            if (full.isNotBlank()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    Text("当天完整转录", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                item {
                    SelectionContainer {
                        Text(full, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
            item { Column(Modifier.padding(bottom = 32.dp)) {} }
        }
    }

    confirm?.let { kind ->
        ConfirmShareDialog(
            title = if (kind == "audio") "导出 $date 的录音？" else "导出 $date 的转录？",
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                scope.launch {
                    if (kind == "audio") {
                        app.repository.exportRecordings(date)?.let { share(ctx, it, "application/zip") }
                    } else {
                        app.repository.exportTranscripts(date)?.let { share(ctx, it, "text/markdown") }
                    }
                }
            },
        )
    }
}

@Composable
private fun ConfirmShareDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text("内容包含你和身边人的对话。分享出去后，文件不再受本 App 保护，请只发给你信任的地方。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("继续导出") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun share(ctx: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "导出").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
