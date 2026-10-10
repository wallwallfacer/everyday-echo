package com.everydayecho.memorykeeper.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client for any OpenAI-compatible `/chat/completions` endpoint
 * (OpenAI, DeepSeek, GLM, OpenRouter, custom gateways).
 */
class OpenAiCompatClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val provider: LlmProvider,
) : LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(
        system: String,
        userMessage: String,
        maxTokens: Int,
        temperature: Double,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("${provider.label} 的 API key 还没有填，请在「设置」里填写"),
            )
        }
        if (baseUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("请在「设置」里填写 Base URL"))
        }
        if (model.isBlank()) {
            return@withContext Result.failure(IllegalStateException("请在「设置」里填写模型 ID"))
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(maxTokens))
            put("temperature", JsonPrimitive(temperature))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(system))
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(userMessage))
                })
            })
        }.toString()

        val builder = Request.Builder()
            .url(chatUrl(baseUrl))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
        if (provider == LlmProvider.OPENROUTER) {
            builder.addHeader("X-Title", "Memory Keeper")
        }
        val request = builder.post(body.toRequestBody("application/json".toMediaType())).build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("${provider.label} API ${response.code}: ${text.take(400)}")
                }
                val parsed = json.parseToJsonElement(text).jsonObject
                parsed["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")
                    ?.jsonObject?.get("content")
                    ?.jsonPrimitive?.content
                    ?.trim()
                    ?: error("Unexpected response shape: ${text.take(200)}")
            }
        }
    }

    companion object {
        /** Accepts a base URL with or without a trailing `/chat/completions`. */
        fun chatUrl(base: String): String {
            val b = base.trim().trimEnd('/')
            return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
        }
    }
}
