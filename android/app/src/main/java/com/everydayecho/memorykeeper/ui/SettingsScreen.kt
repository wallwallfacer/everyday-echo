package com.everydayecho.memorykeeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.everydayecho.memorykeeper.MemoryKeeperApp
import com.everydayecho.memorykeeper.llm.LlmConfig
import com.everydayecho.memorykeeper.llm.LlmProvider
import com.everydayecho.memorykeeper.llm.createClient
import kotlinx.coroutines.launch

private data class TestResult(val ok: Boolean, val message: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = MemoryKeeperApp.get()
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(app.securePrefs.provider) }
    val keys = remember { mutableStateMapOf<LlmProvider, String>().apply {
        LlmProvider.entries.forEach { put(it, app.securePrefs.apiKey(it)) }
    } }
    val models = remember { mutableStateMapOf<LlmProvider, String>().apply {
        LlmProvider.entries.forEach { put(it, app.securePrefs.model(it)) }
    } }
    val urls = remember { mutableStateMapOf<LlmProvider, String>().apply {
        LlmProvider.entries.forEach { put(it, app.securePrefs.baseUrl(it)) }
    } }
    var providerMenu by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var userMd by remember { mutableStateOf("") }
    var noGoTopics by remember { mutableStateOf("") }
    var enableEcho by remember { mutableStateOf(true) }
    var letterTarget by remember { mutableStateOf("300") }
    var maxHours by remember { mutableStateOf("16") }
    var showNuclear by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val profile = app.repository.loadProfile()
        userMd = app.repository.readUserMd()
        noGoTopics = profile.noGoTopics.joinToString(", ")
        enableEcho = profile.preferences.enableEcho
        letterTarget = profile.preferences.letterLengthTarget.toString()
        maxHours = profile.preferences.monitoringMaxHours.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            SectionLabel("AI 服务")
            Box {
                OutlinedButton(
                    onClick = { providerMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("服务商：${provider.label}") }
                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    LlmProvider.entries.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = { provider = p; providerMenu = false },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = keys[provider].orEmpty(),
                onValueChange = { keys[provider] = it },
                label = { Text("API Key (${provider.keyHint})") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = models[provider].orEmpty(),
                onValueChange = { models[provider] = it },
                label = { Text("模型 ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = urls[provider].orEmpty(),
                onValueChange = { urls[provider] = it },
                label = { Text(if (provider.editableBaseUrl) "Base URL（必填，如 https://host/v1）" else "Base URL（一般无需修改）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                "每个服务商各自保存 Key / 模型 / Base URL，切换不会丢。Key 用 EncryptedSharedPreferences 加密存储，" +
                    "只在调用所选服务商的接口时被读取。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedButton(
                onClick = {
                    val p = provider
                    val cfg = LlmConfig(
                        provider = p,
                        apiKey = keys[p].orEmpty().trim(),
                        model = models[p].orEmpty().trim().ifBlank { p.defaultModel },
                        baseUrl = urls[p].orEmpty().trim().ifBlank { p.defaultBaseUrl },
                    )
                    testing = true
                    testResult = null
                    scope.launch {
                        val start = System.nanoTime()
                        val res = cfg.createClient().generate(
                            system = "You are a connectivity test. Reply briefly.",
                            userMessage = "请用一句话回复：连接成功。",
                            maxTokens = 64,
                            temperature = 0.0,
                        )
                        val ms = (System.nanoTime() - start) / 1_000_000
                        testResult = res.fold(
                            onSuccess = { TestResult(true, "${p.label} · ${cfg.model} · ${ms}ms\n回复：${it.take(300)}") },
                            onFailure = { TestResult(false, it.message ?: it.javaClass.simpleName) },
                        )
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("测试中…")
                } else {
                    Text("测试连接（使用当前填写的内容，无需先保存）")
                }
            }
            testResult?.let { r ->
                Text(
                    (if (r.ok) "✓ 测试通过\n" else "✗ 测试失败\n") + r.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel("本地语音转文字")
            TranscriptionSection()

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel("我的声纹")
            VoiceprintSection()

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel("USER.md")
            Text(
                "下面是当前的 USER.md。可以直接改，保存后下一封信生效。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedTextField(
                value = userMd,
                onValueChange = { userMd = it },
                modifier = Modifier.fillMaxWidth().height(280.dp),
                label = { Text("USER.md 内容") },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel("隐私 / 偏好")
            OutlinedTextField(
                value = noGoTopics,
                onValueChange = { noGoTopics = it },
                label = { Text("黑名单话题（逗号分隔）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = letterTarget,
                onValueChange = { letterTarget = it.filter { c -> c.isDigit() } },
                label = { Text("信件目标字数") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = maxHours,
                onValueChange = { maxHours = it.filter { c -> c.isDigit() } },
                label = { Text("监听自动停止小时数（默认 16）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            ToggleRow("启用 Echo（让旧 MEMORY entry callback 进未来的信）", enableEcho) { enableEcho = it }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    app.securePrefs.provider = provider
                    LlmProvider.entries.forEach { p ->
                        app.securePrefs.setApiKey(p, keys[p].orEmpty().trim())
                        app.securePrefs.setModel(p, models[p].orEmpty().trim().ifBlank { p.defaultModel })
                        app.securePrefs.setBaseUrl(p, urls[p].orEmpty().trim().ifBlank { p.defaultBaseUrl })
                    }
                    scope.launch {
                        val existing = app.repository.loadProfile()
                        val profile = existing.copy(
                            noGoTopics = noGoTopics.split(',', '，')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() },
                            preferences = existing.preferences.copy(
                                letterLengthTarget = letterTarget.toIntOrNull() ?: 300,
                                monitoringMaxHours = maxHours.toIntOrNull() ?: 16,
                                enableEcho = enableEcho,
                            ),
                        )
                        app.repository.saveProfile(profile)
                        // Persist hand-edited USER.md text verbatim so manual edits
                        // aren't overwritten. Structured fields still drive prompt slots.
                        java.io.File(app.repository.rootDir(), "USER.md").writeText(userMd)
                        saved = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionLabel("数据")
            TextButton(onClick = { showNuclear = true }, modifier = Modifier.fillMaxWidth()) {
                Text("清空所有数据（不可撤销）", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (saved) {
        AlertDialog(
            onDismissRequest = { saved = false },
            confirmButton = { TextButton(onClick = { saved = false }) { Text("好") } },
            title = { Text("已保存") },
            text = { Text("下一封信会用新的设置。") },
        )
    }

    if (showNuclear) {
        AlertDialog(
            onDismissRequest = { showNuclear = false },
            confirmButton = {
                TextButton(onClick = {
                    showNuclear = false
                    scope.launch {
                        app.repository.deleteAll()
                        onBack()
                    }
                }) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showNuclear = false }) { Text("取消") }
            },
            title = { Text("清空所有数据") },
            text = { Text("会删除 SOUL/USER/MEMORY、所有 transcript 和所有信件。不可撤销。") },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
