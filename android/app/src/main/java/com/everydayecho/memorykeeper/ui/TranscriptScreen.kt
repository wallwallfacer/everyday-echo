package com.everydayecho.memorykeeper.ui

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.everydayecho.memorykeeper.voice.RecordingCleaner
import com.everydayecho.memorykeeper.voice.Voiceprint
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class DaySummary(val date: LocalDate, val segments: Int, val pending: Int, val hasTranscript: Boolean)

private data class Segment(
    val file: File,
    val start: LocalTime?,
    val durationMs: Long,
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
    var cleanStatus by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) } // "transcript" | "audio"
    var selecting by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<Set<File>>(emptySet()) }
    var toDelete by remember { mutableStateOf<Set<File>?>(null) }
    var confirmClean by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<File?>(null) }
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
        segments = withContext(Dispatchers.IO) {
            files.map { f ->
                val key = f.nameWithoutExtension
                val start = runCatching { LocalTime.parse(key, HHMMSS) }.getOrNull()
                Segment(f, start, durationMs(f), map[key], f.name in done, f.name in failed)
            }
        }
        chosen = chosen.filter { c -> segments.any { it.file == c } }.toSet()
        full = app.repository.readTranscript(date)
    }
    LaunchedEffect(date) { load() }
    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    val pendingCount = segments.count { !it.transcribed && !it.failed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "已选 ${chosen.size} 段" else date.toString()) },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) { selecting = false; chosen = emptySet() } else onBack() }) {
                        if (selecting) {
                            Icon(Icons.Filled.Close, contentDescription = "退出选择")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        val all = chosen.size == segments.size && segments.isNotEmpty()
                        TextButton(onClick = { chosen = if (all) emptySet() else segments.map { it.file }.toSet() }) {
                            Text(if (all) "取消全选" else "全选")
                        }
                        IconButton(enabled = chosen.isNotEmpty(), onClick = { toDelete = chosen }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除所选")
                        }
                    } else if (segments.isNotEmpty()) {
                        TextButton(onClick = { stopPlayback(); selecting = true }) { Text("选择") }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!selecting) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirm = "transcript" }, enabled = full.isNotBlank()) { Text("导出转录") }
                        OutlinedButton(onClick = { confirm = "audio" }, enabled = segments.isNotEmpty()) { Text("导出录音") }
                    }
                }
                if (segments.isNotEmpty()) {
                    item {
                        OutlinedButton(
                            enabled = cleanStatus == null && batchStatus == null,
                            onClick = { confirmClean = true },
                        ) { Text(cleanStatus ?: "清理：去掉没有人声 / 不是我的声音的部分") }
                    }
                }
                if (segments.isNotEmpty() && pendingCount > 0) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                enabled = modelReady && batchStatus == null && cleanStatus == null,
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
            }
            message?.let { item { Text(it, style = MaterialTheme.typography.labelMedium) } }
            if (segments.isEmpty()) item { Text("（这一天没有保存的录音）", color = MaterialTheme.colorScheme.secondary) }
            items(segments, key = { it.file.name }) { seg ->
                val isChosen = seg.file in chosen
                Card(
                    Modifier.fillMaxWidth().then(
                        if (selecting) Modifier.clickable { chosen = if (isChosen) chosen - seg.file else chosen + seg.file } else Modifier,
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) {
                                Checkbox(
                                    checked = isChosen,
                                    onCheckedChange = { chosen = if (it) chosen + seg.file else chosen - seg.file },
                                )
                            } else {
                                IconButton(onClick = { play(seg.file) }) {
                                    Icon(
                                        if (playing == seg.file) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                        contentDescription = if (playing == seg.file) "停止" else "播放",
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(seg.timeRange(), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "时长 ${formatDuration(seg.durationMs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            if (!selecting) {
                                if (seg.file.name in busy) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else if (seg.text == null) {
                                    TextButton(
                                        enabled = modelReady && cleanStatus == null,
                                        onClick = {
                                            busy = busy + seg.file.name
                                            scope.launch {
                                                val r = Transcriber(ctx, app.repository).transcribeFile(date, seg.file)
                                                busy = busy - seg.file.name
                                                r.onSuccess { if (!it.any(Char::isLetterOrDigit)) message = "这段没有识别出人声，已删除" }
                                                r.onFailure { message = "转录失败：${it.message}" }
                                                load()
                                            }
                                        },
                                    ) { Text(if (seg.transcribed) "重新转录" else "转录") }
                                }
                                Box {
                                    IconButton(onClick = { menuFor = seg.file }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                                    }
                                    DropdownMenu(expanded = menuFor == seg.file, onDismissRequest = { menuFor = null }) {
                                        DropdownMenuItem(
                                            text = { Text("这是我的声音（加入声纹）") },
                                            onClick = {
                                                menuFor = null
                                                scope.launch {
                                                    message = "正在更新声纹…"
                                                    message = withContext(Dispatchers.Default) {
                                                        runCatching {
                                                            val dir = Voiceprint.samplesDir(ctx).also { it.mkdirs() }
                                                            seg.file.copyTo(File(dir, "${date}_${seg.file.name}"), overwrite = true)
                                                            "已加入声纹（共 ${Voiceprint.rebuild(ctx)} 段样本）"
                                                        }.getOrElse { "加入声纹失败：${it.message}" }
                                                    }
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("删除") },
                                            onClick = { menuFor = null; toDelete = setOf(seg.file) },
                                        )
                                    }
                                }
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
            if (full.isNotBlank() && !selecting) {
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

    toDelete?.let { files ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("删除 ${files.size} 段录音？") },
            text = { Text("录音和对应的转录文字都会删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    if (playing?.let { it in files } == true) stopPlayback()
                    scope.launch {
                        Transcriber.lock.withLock { app.repository.deleteRecordings(date, files) }
                        message = "已删除 ${files.size} 段"
                        selecting = false
                        chosen = emptySet()
                        load()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("取消") } },
        )
    }

    if (confirmClean) {
        val withVoiceprint = Voiceprint.isActive(ctx)
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text("清理这一天的录音？") },
            text = {
                Text(
                    if (withVoiceprint) {
                        "会逐段检查：没有人声的部分、以及不是你的声音的部分会被删掉（录音和对应转录），只留下你说话的片段。删除后无法恢复。"
                    } else {
                        "会逐段检查，删掉没有人声的部分（录音和对应转录）。删除后无法恢复。\n\n你还没有录入声纹，所以别人的声音不会被去掉；可以先到「设置 → 我的声纹」录一段。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClean = false
                    stopPlayback()
                    cleanStatus = "清理中…"
                    scope.launch {
                        val r = RecordingCleaner(ctx, app.repository).cleanDay(date) { d, t -> cleanStatus = "清理中 $d / $t" }
                        cleanStatus = null
                        message = r.fold({ it.describe() }, { "清理失败：${it.message}" })
                        load()
                    }
                }) { Text("开始清理") }
            },
            dismissButton = { TextButton(onClick = { confirmClean = false }) { Text("取消") } },
        )
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

private val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
private val HH_MM_SS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** "开始 – 结束" wall-clock times of a segment, e.g. "01:06:41 – 01:16:50". */
private fun Segment.timeRange(): String {
    val s = start ?: return file.nameWithoutExtension
    if (durationMs <= 0) return s.format(HH_MM_SS)
    return "${s.format(HH_MM_SS)} – ${s.plusNanos(durationMs * 1_000_000).format(HH_MM_SS)}"
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "未知"
    val sec = (ms + 500) / 1000
    return if (sec < 60) "$sec 秒" else "${sec / 60} 分 ${"%02d".format(sec % 60)} 秒"
}

/** Real duration from the container/stream, falling back to a bitrate estimate. */
private fun durationMs(f: File): Long {
    val ex = MediaExtractor()
    return try {
        ex.setDataSource(f.absolutePath)
        (0 until ex.trackCount).map { ex.getTrackFormat(it) }
            .firstOrNull { it.containsKey(MediaFormat.KEY_DURATION) }
            ?.getLong(MediaFormat.KEY_DURATION)?.div(1000)
            ?: (f.length() * 8 / 32)
    } catch (_: Throwable) {
        f.length() * 8 / 32
    } finally {
        ex.release()
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
