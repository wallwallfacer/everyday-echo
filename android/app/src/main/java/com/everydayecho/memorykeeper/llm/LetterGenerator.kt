package com.everydayecho.memorykeeper.llm

import com.everydayecho.memorykeeper.data.FileRepository
import com.everydayecho.memorykeeper.data.SecurePreferences
import com.everydayecho.memorykeeper.data.UserProfile
import java.time.LocalDate

/**
 * Composes the per-spec prompt for tonight's letter and calls Anthropic.
 *
 * Prompt shape (§9.2):
 *   [SOUL.md (slots filled)] + [USER.md] + [MEMORY.md last-14d + retrieved]
 *   + [today's transcript, voice-filtered]
 *   + [generation instruction]
 */
class LetterGenerator(
    private val repo: FileRepository,
    private val prefs: SecurePreferences,
) {

    suspend fun generateToday(date: LocalDate = LocalDate.now()): Result<String> {
        val profile = repo.loadProfile()
        val soulRaw = repo.ensureSoul()
        val soul = fillSlots(soulRaw, profile)

        val userMd = repo.readUserMd()
        val memoryMd = trimMemoryForPrompt(repo.readMemory(), date)
        val transcript = sanitizeTranscript(repo.readTranscript(date), profile)

        val audioFiles = repo.listRecordingFiles(date)
        val config = prefs.activeConfig()
        if (audioFiles.isNotEmpty() && config.provider != LlmProvider.GEMINI) {
            return Result.failure(
                IllegalStateException(
                    "今天有 ${audioFiles.size} 段录音，但只有 Google Gemini 能直接听音频。请在「设置」里把 AI 服务切换为 Gemini 并填入 API key。",
                ),
            )
        }

        val userMessage = buildString {
            appendLine("# USER.md")
            appendLine(userMd)
            appendLine()
            appendLine("# MEMORY.md (近 14 天，已为今晚检索)")
            appendLine(memoryMd)
            appendLine()
            if (audioFiles.isNotEmpty()) {
                appendLine("# 今日录音")
                appendLine("下面会依次给出今天的录音（已由本地 VAD 去掉静音，只含有人声的片段，按时间顺序，每组前有开始时间）。")
                appendLine("请直接听录音理解今天发生的事。")
                if (profile.noGoTopics.isNotEmpty()) {
                    appendLine("录音中若涉及这些禁区话题，请完全忽略、不要在信里提及：${profile.noGoTopics.joinToString(", ")}。")
                }
                appendLine("录音里可能有他人的声音或电视/视频声音，无法确定是用户本人说的内容请谨慎措辞。")
            } else {
                appendLine("# 今日 transcript")
                if (transcript.isBlank()) appendLine("（今日 transcript 为空）") else appendLine(transcript)
            }
        }
        val instruction = buildString {
            appendLine("---")
            appendLine("请按照档案员的规则，为 ${profile.preferredName.ifBlank { "用户" }} 生成今天 ${date} 的纪录。")
            appendLine("输出**只**包含信件正文，不要包含任何解释、Markdown 标题、metadata。")
            appendLine("若素材为空或全是噪音，按 SOUL.md 的「降级」规则诚实告知，不要凑字数。")
        }

        if (audioFiles.isEmpty()) {
            return config.createClient().generate(
                system = soul,
                userMessage = userMessage + "\n" + instruction,
                maxTokens = 2048,
                temperature = 0.7,
            )
        }

        val gemini = GeminiClient(config.apiKey, config.model, config.baseUrl)
        val parts = mutableListOf<GeminiPart>(GeminiPart.Text(userMessage))
        for ((idx, group) in groupByTime(audioFiles).withIndex()) {
            val bytes = java.io.ByteArrayOutputStream().also { out -> group.forEach { out.write(it.readBytes()) } }.toByteArray()
            val uri = gemini.uploadFile(bytes, "audio/aac", "${date}-${idx + 1}").getOrElse { return Result.failure(it) }
            val times = group.joinToString(", ") { formatStart(it.nameWithoutExtension) }
            parts += GeminiPart.Text("【录音第 ${idx + 1} 组，片段开始时间：$times】")
            parts += GeminiPart.FileRef(uri, "audio/aac")
        }
        parts += GeminiPart.Text(instruction)
        return gemini.generateParts(soul, parts, maxTokens = 2048, temperature = 0.7)
    }

    /** ADTS .aac files can be concatenated byte-for-byte; group ~15 min (≈3.6 MB at 32 kbps) per upload. */
    private fun groupByTime(files: List<java.io.File>): List<List<java.io.File>> {
        val groups = mutableListOf<MutableList<java.io.File>>()
        var size = 0L
        for (f in files) {
            if (groups.isEmpty() || size + f.length() > GROUP_BYTES) { groups += mutableListOf(); size = 0 }
            groups.last() += f
            size += f.length()
        }
        return groups
    }

    private fun formatStart(hhmmss: String): String =
        if (hhmmss.length == 6) "${hhmmss.substring(0, 2)}:${hhmmss.substring(2, 4)}:${hhmmss.substring(4)}" else hhmmss

    private companion object {
        const val GROUP_BYTES = 3_600_000L
    }

    private fun fillSlots(soul: String, p: UserProfile): String {
        val family = if (p.family.isEmpty()) "（暂无家庭成员信息）"
        else p.family.joinToString("; ") { "${it.address}=${it.name}" }
        val workPeople = if (p.workPeople.isEmpty()) "（in-context 累积中）"
        else p.workPeople.joinToString("; ") { it.name }
        val terms = if (p.termDictionary.isEmpty()) "（in-context 累积中）"
        else p.termDictionary.joinToString(", ")
        val noGo = if (p.noGoTopics.isEmpty()) "（无）"
        else p.noGoTopics.joinToString(", ")
        return soul
            .replace("{{USER.preferred_name}}", p.preferredName.ifBlank { "用户" })
            .replace("{{USER.family}}", family)
            .replace("{{USER.work_people}}", workPeople)
            .replace("{{USER.term_dictionary}}", terms)
            .replace("{{USER.no_go_topics}}", noGo)
            .replace("{{USER.language_preference}}", "primary=${p.language.primary}")
    }

    /**
     * §12.5: only last 14 days plus retrieved-by-topic stay in the prompt.
     * For MVP we keep last 14 days verbatim and skip semantic retrieval.
     */
    private fun trimMemoryForPrompt(memory: String, today: LocalDate): String {
        if (memory.isBlank()) return "（暂无）"
        val cutoff = today.minusDays(14)
        val sections = memory.split(Regex("(?m)^## "))
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && it.first().isDigit() }
        val kept = sections.filter {
            val date = runCatching {
                LocalDate.parse(it.lineSequence().first().trim().take(10))
            }.getOrNull()
            date != null && !date.isBefore(cutoff)
        }
        if (kept.isEmpty()) return "（最近 14 天暂无被 pin 的瞬间）"
        return kept.joinToString("\n\n") { "## $it" }
    }

    /**
     * §18.5 + §H.5: drop transcript lines whose body contains any no_go topic.
     * Naive substring match for MVP; production would use proper classification.
     */
    private fun sanitizeTranscript(transcript: String, profile: UserProfile): String {
        if (transcript.isBlank() || profile.noGoTopics.isEmpty()) return transcript
        val lowered = profile.noGoTopics.map { it.lowercase() }
        return transcript.lineSequence()
            .filter { line -> lowered.none { it.isNotBlank() && line.lowercase().contains(it) } }
            .joinToString("\n")
    }
}
