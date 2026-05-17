package com.everydayecho.memorykeeper.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.everydayecho.memorykeeper.MemoryKeeperApp
import com.everydayecho.memorykeeper.data.FamilyMember
import com.everydayecho.memorykeeper.data.UserProfile
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val VOICE_TEXT = """今天的天气和昨天差不多，傍晚有点风。
我在阳台站了一会儿，没说话。
楼下小孩在喊"妈我饿了"，喊了三遍。
我没下楼，回屋把那本翻了三天的书又翻开。"""

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val app = MemoryKeeperApp.get()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var familyRaw by remember { mutableStateOf("") }
    var hasRecording by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voiceFile by remember { mutableStateOf<File?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                0 -> WelcomeStep(onNext = { step = 1 })
                1 -> NameStep(
                    name = name,
                    onName = { name = it },
                    onNext = { if (name.isNotBlank()) step = 2 },
                )
                2 -> FamilyStep(
                    text = familyRaw,
                    onText = { familyRaw = it },
                    onNext = { step = 3 },
                    onSkip = { familyRaw = ""; step = 3 },
                )
                3 -> VoiceStep(
                    hasRecording = hasRecording,
                    recording = recording,
                    permissionDenied = permissionDenied,
                    onStart = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            return@VoiceStep
                        }
                        val file = File(app.repository.audioDir(), "voice_self.m4a")
                        voiceFile = file
                        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            MediaRecorder(ctx)
                        } else @Suppress("DEPRECATION") MediaRecorder()
                        rec.apply {
                            setAudioSource(MediaRecorder.AudioSource.MIC)
                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                            setOutputFile(file.absolutePath)
                            prepare()
                            start()
                        }
                        recorder = rec
                        recording = true
                    },
                    onStop = {
                        runCatching {
                            recorder?.stop()
                            recorder?.release()
                        }
                        recorder = null
                        recording = false
                        hasRecording = voiceFile?.exists() == true
                    },
                    onNext = { step = 4 },
                )
                4 -> ExpectationStep(onFinish = {
                    scope.launch {
                        val family = parseFamily(familyRaw)
                        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                        val profile = UserProfile(
                            preferredName = name.trim(),
                            family = family,
                            voiceFingerprintRecordedAt = if (hasRecording) today else "",
                            onboardingCompleted = true,
                        )
                        app.repository.saveProfile(profile)
                        onDone()
                    }
                })
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Text("Memory Keeper", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
    Text(
        "在开始之前，我需要 3 分钟问你几件事。\n答错没关系——所有内容存在 USER.md 里，你随时可以改。",
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onNext) { Text("开始") }
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit, onNext: () -> Unit) {
    Text("Q1", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
    Text(
        "你希望我在每天的纪录里怎么称呼你？",
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
    )
    Text(
        "你的名字或常用昵称，比如 \"Tao\" 或 \"陶 X X\" 或英文名。",
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
    )
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        placeholder = { Text("Tao") },
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onNext, enabled = name.isNotBlank()) { Text("下一步") }
}

@Composable
private fun FamilyStep(text: String, onText: (String) -> Unit, onNext: () -> Unit, onSkip: () -> Unit) {
    Text("Q2", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
    Text(
        "和你住在一起的家人有谁？他们的名字是什么，你平时怎么叫他们？",
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
    )
    Text(
        "一次写完即可。例：\n老婆叫王 X X，我叫她「老婆」；女儿叫陶 X X，我叫她「小七」。",
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
    )
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        modifier = Modifier.fillMaxWidth().height(140.dp),
        placeholder = { Text("老婆叫…我叫她「…」") },
    )
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("下一步") }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("暂时不想列 —— 之后录到他们的声音时再说")
        }
    }
}

@Composable
private fun VoiceStep(
    hasRecording: Boolean,
    recording: Boolean,
    permissionDenied: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
) {
    Text("Q3", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
    Text(
        "接下来请念一段话，大约 30 秒。\n这是用来识别你的声音的——之后我只会写你说的话，不会把背景里的播客、家人的对话当成你的。",
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
    )
    Text("请用平常的语速，不用刻意慢。",
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(VOICE_TEXT, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    if (permissionDenied) {
        Text(
            "没有麦克风权限就没法录——请在系统设置里授予后再回来。",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
    if (!recording && !hasRecording) {
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("开始录音") }
    } else if (recording) {
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止录音") }
        Text("录音中…", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
    } else {
        Text("录音已保存。",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("下一步") }
            TextButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("重新录一次")
            }
        }
    }
}

@Composable
private fun ExpectationStep(onFinish: () -> Unit) {
    Text(
        "OK，最低限度的 setup 完成了。",
        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
    )
    Text(
        """接下来怎么走：
1. 在主页可以手动打开监听 toggle（醒着即 on，超过 16 小时自动停）
2. 当晚回来打开 app 就能读到今天的纪录
3. 第一周会有点奇怪——AI 还在学。每条错的内容长按可以纠正
4. USER.md 在设置里随时可以打开编辑

不需要每天打开 USER.md。它会跟你一起慢慢长。""",
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onFinish) { Text("好") }
}

private fun parseFamily(raw: String): List<FamilyMember> {
    if (raw.isBlank()) return emptyList()
    // Best-effort parse: split by line, then by punctuation. We don't try to
    // perfectly parse the user's free-form sentence — they can edit USER.md.
    val out = mutableListOf<FamilyMember>()
    val lines = raw.split('\n', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }
    for (line in lines) {
        val addressMatch = Regex("叫她?「(.+?)」").find(line)
            ?: Regex("叫她?\"(.+?)\"").find(line)
            ?: Regex("叫她?'(.+?)'").find(line)
        val address = addressMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val nameMatch = Regex("(?:叫|是|名字是|叫做)?\\s*([一-鿿A-Za-z·]{1,12})").find(line)
        val name = nameMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (address.isNotBlank() || name.isNotBlank()) {
            out += FamilyMember(name = name, address = address.ifBlank { name })
        }
    }
    if (out.isEmpty()) {
        out += FamilyMember(name = "", address = raw.trim())
    }
    return out
}
