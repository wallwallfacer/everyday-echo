package com.everydayecho.memorykeeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.everydayecho.memorykeeper.MemoryKeeperApp
import com.everydayecho.memorykeeper.data.MemoryEntry
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val app = MemoryKeeperApp.get()
    val scope = rememberCoroutineScope()

    var memoryMd by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        memoryMd = app.repository.readMemory()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MEMORY.md") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        editingText = memoryMd
                        editing = true
                    }) { Text("编辑") }
                    TextButton(onClick = { showAdd = true }) { Text("+ 速记") }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "档案员只读它，你是唯一的写者。最近 14 天 + 主题相关的旧条目会自动进入每天的信件 prompt。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            HorizontalDivider()
            Text(
                memoryMd.ifBlank { "（还没有 pin 过任何瞬间）" },
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(48.dp))
        }
    }

    if (showAdd) {
        var text by remember { mutableStateOf("") }
        var tags by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            confirmButton = {
                TextButton(onClick = {
                    val entry = MemoryEntry(
                        date = LocalDate.now().toString(),
                        kind = "note",
                        text = text,
                        tags = tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                        source = "manual",
                    )
                    showAdd = false
                    scope.launch {
                        app.repository.appendMemory(entry)
                        memoryMd = app.repository.readMemory()
                    }
                }, enabled = text.isNotBlank()) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } },
            title = { Text("速记一条") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("内容") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text("tags（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
        )
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            confirmButton = {
                Button(onClick = {
                    val updated = editingText
                    editing = false
                    scope.launch {
                        app.repository.writeMemory(updated)
                        memoryMd = app.repository.readMemory()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
            title = { Text("编辑 MEMORY.md") },
            text = {
                OutlinedTextField(
                    value = editingText,
                    onValueChange = { editingText = it },
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                )
            },
        )
    }
}
