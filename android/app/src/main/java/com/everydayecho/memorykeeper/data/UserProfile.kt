package com.everydayecho.memorykeeper.data

import kotlinx.serialization.Serializable

@Serializable
data class FamilyMember(
    val name: String = "",
    val address: String = "",
    val voiceFingerprintStatus: String = "pending",
)

@Serializable
data class WorkPerson(
    val name: String = "",
    val relationship: String = "",
)

@Serializable
data class LanguagePreference(
    val primary: String = "zh",
    val preserveEnglish: List<String> = listOf("tech_terms"),
)

@Serializable
data class Preferences(
    val letterLengthTarget: Int = 300,
    val monitoringMaxHours: Int = 16,
    val enableNotification: Boolean = false,
    val enableEcho: Boolean = true,
)

@Serializable
data class UserProfile(
    val preferredName: String = "",
    val language: LanguagePreference = LanguagePreference(),
    val family: List<FamilyMember> = emptyList(),
    val workPeople: List<WorkPerson> = emptyList(),
    val termDictionary: List<String> = emptyList(),
    val noGoTopics: List<String> = emptyList(),
    val voiceFingerprintRecordedAt: String = "",
    val onboardingCompleted: Boolean = false,
    val preferences: Preferences = Preferences(),
) {
    fun toMarkdown(): String = buildString {
        appendLine("# USER.md — Memory Keeper 用户上下文")
        appendLine("# 由 onboarding 初始化，可随时编辑")
        appendLine()
        appendLine("preferred_name: ${preferredName.ifBlank { "\"\"" }}")
        appendLine()
        appendLine("language_preference:")
        appendLine("  primary: ${language.primary}")
        appendLine("  preserve_english:")
        language.preserveEnglish.forEach { appendLine("    - $it") }
        appendLine()
        appendLine("family:")
        if (family.isEmpty()) {
            appendLine("  # 暂无")
        } else {
            family.forEach {
                appendLine("  - name: \"${it.name}\"")
                appendLine("    address: \"${it.address}\"")
                appendLine("    voice_fingerprint_status: \"${it.voiceFingerprintStatus}\"")
            }
        }
        appendLine()
        appendLine("work_people:")
        if (workPeople.isEmpty()) {
            appendLine("  # 通过 correction loop 累积")
        } else {
            workPeople.forEach {
                appendLine("  - name: \"${it.name}\"")
                if (it.relationship.isNotBlank()) appendLine("    relationship: \"${it.relationship}\"")
            }
        }
        appendLine()
        appendLine("term_dictionary:")
        if (termDictionary.isEmpty()) {
            appendLine("  # 通过 in-context 学习累积")
        } else {
            termDictionary.forEach { appendLine("  - $it") }
        }
        appendLine()
        appendLine("no_go_topics:")
        if (noGoTopics.isEmpty()) {
            appendLine("  []")
        } else {
            noGoTopics.forEach { appendLine("  - $it") }
        }
        appendLine()
        appendLine("voice_fingerprints:")
        appendLine("  self:")
        appendLine("    recorded_at: \"${voiceFingerprintRecordedAt}\"")
        appendLine("    status: \"${if (voiceFingerprintRecordedAt.isNotBlank()) "active" else "pending"}\"")
        appendLine()
        appendLine("# 高级设置")
        appendLine("preferences:")
        appendLine("  letter_length_target: ${preferences.letterLengthTarget}")
        appendLine("  monitoring_max_hours: ${preferences.monitoringMaxHours}")
        appendLine("  enable_notification: ${preferences.enableNotification}")
        appendLine("  enable_echo: ${preferences.enableEcho}")
    }
}
