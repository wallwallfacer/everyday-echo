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
 * Thin client over Anthropic Messages API. Raw OkHttp + JSON, no Retrofit.
 */
class AnthropicClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = LlmProvider.ANTHROPIC.defaultBaseUrl,
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
            return@withContext Result.failure(IllegalStateException("Anthropic 的 API key 还没有填，请在「设置」里粘贴 sk-ant-... key"))
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(maxTokens))
            put("temperature", JsonPrimitive(temperature))
            put("system", JsonPrimitive(system))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(userMessage))
                })
            })
        }.toString()

        val request = Request.Builder()
            .url(baseUrl.trim().trimEnd('/') + "/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Anthropic API ${response.code}: ${text.take(400)}")
                }
                val parsed = json.parseToJsonElement(text).jsonObject
                val content = parsed["content"]?.jsonArray
                content?.firstOrNull()
                    ?.jsonObject?.get("text")
                    ?.jsonPrimitive?.content
                    ?: error("Unexpected response shape: ${text.take(200)}")
            }
        }
    }
}
