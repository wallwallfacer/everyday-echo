@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.everydayecho.memorykeeper.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.everydayecho.memorykeeper.MemoryKeeperApp
import com.everydayecho.memorykeeper.data.MemoryEntry
import com.everydayecho.memorykeeper.llm.LetterGenerator
import com.everydayecho.memorykeeper.service.MonitoringService
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日，EEEE")

@Composable
fun LetterScreen(
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    val app = MemoryKeeperApp.get()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var listening by remember { mutableStateOf(MonitoringService.isRunning(ctx)) }
    var letter by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pinTarget by remember { mutableStateOf<String?>(null) }

    val today = LocalDate.now()

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            MonitoringService.start(ctx)
            listening = true
        }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result not strictly required */ }

    LaunchedEffect(Unit) {
        letter = app.repository.readLetter(today)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(today.format(DATE_FMT)) },
                actions = {
                    IconButton(onClick = onOpenMemory) {
                        Icon(Icons.Default.Refresh, contentDescription = "记忆")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            ListenToggleCard(
                listening = listening,
                onToggle = { wantOn ->
                    if (wantOn) {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            MonitoringService.start(ctx)
                            listening = true
                        }
                    } else {
                        MonitoringService.stop(ctx)
                        listening = false
                    }
                },
            )
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    generating -> GeneratingState()
                    letter != null -> LetterBody(
                        body = letter!!,
                        onPin = { paragraph -> pinTarget = paragraph },
                    )
                    else -> EmptyState(
                        canGenerate = true,
                        onGenerate = {
                            generating = true
                            error = null
                            scope.launch {
                                val gen = LetterGenerator(app.repository, app.securePrefs)
                                val res = gen.generateToday(today)
                                generating = false
                                res.fold(
                                    onSuccess = { text ->
                                        app.repository.saveLetter(today, text)
                                        letter = text
                                    },
                                    onFailure = { err ->
                                        error = err.message ?: "生成失败"
                                    },
                                )
                            }
                        },
                    )
                }
                if (error != null) {
                    AlertDialog(
                        onDismissRequest = { error = null },
                        confirmButton = {
                            TextButton(onClick = { error = null }) { Text("好") }
                        },
                        title = { Text("没能生成今天的纪录") },
                        text = { Text(error!!) },
                    )
                }
            }

            AnimatedVisibility(letter != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            generating = true
                            error = null
                            scope.launch {
                                val gen = LetterGenerator(app.repository, app.securePrefs)
                                val res = gen.generateToday(today)
                                generating = false
                                res.fold(
                                    onSuccess = { text ->
                                        app.repository.saveLetter(today, text)
                                        letter = text
                                    },
                                    onFailure = { err ->
                                        error = err.message ?: "生成失败"
                                    },
                                )
                            }
                        },
                    ) { Text("重新生成") }
                }
            }
        }
    }

    if (pinTarget != null) {
        PinDialog(
            paragraph = pinTarget!!,
            onDismiss = { pinTarget = null },
            onConfirm = { tags, note ->
                val target = pinTarget!!
                pinTarget = null
                scope.launch {
                    app.repository.appendMemory(
                        MemoryEntry(
                            date = today.toString(),
                            kind = "moment",
                            text = target.take(120),
                            tags = tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                            note = note,
                            source = "letter-${today}",
                        )
                    )
                }
            },
        )
    }
}

@Composable
private fun ListenToggleCard(listening: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (listening) Icons.Default.Mic else Icons.Outlined.MicOff,
            contentDescription = null,
            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.padding(horizontal = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (listening) "正在监听" else "未监听",
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                if (listening) "醒着即 on，超过 16 小时自动停"
                else "打开后会在通知栏显示，随时关掉",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Switch(checked = listening, onCheckedChange = onToggle)
    }
}

@Composable
private fun LetterBody(body: String, onPin: (String) -> Unit) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        body.split("\n\n").filter { it.isNotBlank() }.forEach { paragraph ->
            Text(
                paragraph.trim(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { onPin(paragraph.trim()) },
                    )
                    .padding(vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "长按任意一段：记住这件事 / 这不是我 / 这不重要",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GeneratingState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text("档案员正在写…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyState(canGenerate: Boolean, onGenerate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "今天还没有纪录。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "可以现在生成（基于今天已捕捉到的 transcript），或者继续监听到晚一点。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
        )
        if (canGenerate) {
            TextButton(onClick = onGenerate) { Text("现在生成今天的纪录") }
        }
    }
}

@Composable
private fun PinDialog(
    paragraph: String,
    onDismiss: () -> Unit,
    onConfirm: (tags: String, note: String) -> Unit,
) {
    var tags by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(tags, note) }) { Text("Pin") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("记住这件事") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    paragraph.take(160) + if (paragraph.length > 160) "…" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("tags（逗号分隔，可选）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("一句备注（可选）") },
                    singleLine = false,
                )
            }
        },
    )
}
