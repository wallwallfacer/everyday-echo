package com.everydayecho.memorykeeper.llm

/**
 * Supported AI endpoints. Everything except [ANTHROPIC] and [GEMINI] speaks the
 * OpenAI-compatible `/chat/completions` protocol.
 */
enum class LlmProvider(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val keyHint: String,
    val editableBaseUrl: Boolean = false,
) {
    ANTHROPIC("Anthropic", "https://api.anthropic.com", "claude-opus-4-7", "sk-ant-..."),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o", "sk-..."),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "sk-..."),
    GLM("GLM (智谱)", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus", "id.secret"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4.5", "sk-or-..."),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com", "gemini-2.5-flash", "AIza..."),
    CUSTOM("自定义 (OpenAI 兼容)", "", "", "API key", editableBaseUrl = true);

    companion object {
        fun fromName(name: String?): LlmProvider =
            entries.firstOrNull { it.name == name } ?: ANTHROPIC
    }
}

/** Resolved connection settings for the active provider. */
data class LlmConfig(
    val provider: LlmProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
)

interface LlmClient {
    suspend fun generate(
        system: String,
        userMessage: String,
        maxTokens: Int = 2048,
        temperature: Double = 0.7,
    ): Result<String>
}

fun LlmConfig.createClient(): LlmClient = when (provider) {
    LlmProvider.ANTHROPIC -> AnthropicClient(apiKey, model, baseUrl)
    LlmProvider.GEMINI -> GeminiClient(apiKey, model, baseUrl)
    else -> OpenAiCompatClient(apiKey, model, baseUrl, provider)
}
