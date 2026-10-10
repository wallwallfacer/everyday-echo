package com.everydayecho.memorykeeper.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.everydayecho.memorykeeper.MemoryKeeperApp
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(onBack: () -> Unit) {
    val app = MemoryKeeperApp.get()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var dates by remember { mutableStateOf<List<LocalDate>>(emptyList()) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    var content by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { dates = app.repository.listTranscriptDates() }
    LaunchedEffect(selected) {
        content = selected?.let { app.repository.readTranscript(it) }.orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.toString() ?: "原始转录") },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) selected = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { pendingExport = true },
                        enabled = selected != null || dates.isNotEmpty(),
                    ) { Text(if (selected != null) "导出" else "导出全部") }
                },
            )
        },
    ) { pad ->
        val day = selected
        if (day == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "监听识别出的文字，仅保留最近 7 天。不保存任何录音。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                HorizontalDivider()
                if (dates.isEmpty()) Text("（暂无转录）")
                dates.forEach { d ->
                    Text(
                        d.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = d }
                            .padding(vertical = 14.dp),
                    )
                    HorizontalDivider()
                }
            }
        } else {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(pad)
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        content.ifBlank { "（空）" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }

    if (pendingExport) {
        AlertDialog(
            onDismissRequest = { pendingExport = false },
            title = { Text("导出转录？") },
            text = { Text("转录包含你和身边人的对话内容。分享出去后，文件不再受本 App 保护，请只发给你信任的地方。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingExport = false
                    scope.launch {
                        val file = app.repository.exportTranscripts(selected)
                        if (file != null) share(ctx, file, if (file.extension == "zip") "application/zip" else "text/markdown")
                    }
                }) { Text("继续导出") }
            },
            dismissButton = { TextButton(onClick = { pendingExport = false }) { Text("取消") } },
        )
    }
}

private fun share(ctx: Context, file: java.io.File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "导出转录").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
