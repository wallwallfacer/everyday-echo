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

        val userMessage = buildString {
            appendLine("# USER.md")
            appendLine(userMd)
            appendLine()
            appendLine("# MEMORY.md (近 14 天，已为今晚检索)")
            appendLine(memoryMd)
            appendLine()
            appendLine("# 今日 transcript")
            if (transcript.isBlank()) {
                appendLine("（今日 transcript 为空）")
            } else {
                appendLine(transcript)
            }
            appendLine()
            appendLine("---")
            appendLine("请按照档案员的规则，为 ${profile.preferredName.ifBlank { "用户" }} 生成今天 ${date} 的纪录。")
            appendLine("输出**只**包含信件正文，不要包含任何解释、Markdown 标题、metadata。")
            appendLine("若 transcript 为空或全是噪音，按 SOUL.md 的「降级」规则诚实告知，不要凑字数。")
        }

        val client = AnthropicClient(prefs.apiKey, prefs.apiModel)
        return client.generate(
            system = soul,
            userMessage = userMessage,
            maxTokens = 2048,
            temperature = 0.7,
        )
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
