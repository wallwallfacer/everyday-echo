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

/** Google Gemini (generativelanguage API), text only. */
class GeminiClient(
    private val apiKey: String,
    private val model: String,
    baseUrl: String = LlmProvider.GEMINI.defaultBaseUrl,
) : LlmClient {
    private val base = baseUrl.trim().trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(system: String, userMessage: String, maxTokens: Int, temperature: Double): Result<String> =
        withContext(Dispatchers.IO) {
        runCatching {
            requireKey()
            val body = buildJsonObject {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(system)) }) })
                })
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(userMessage)) }) })
                    })
                })
                put("generationConfig", buildJsonObject {
                    put("temperature", JsonPrimitive(temperature))
                    // Thinking models spend part of this budget on reasoning.
                    put("maxOutputTokens", JsonPrimitive(maxOf(maxTokens, 4096)))
                })
            }.toString()
            val request = Request.Builder()
                .url("$base/v1beta/models/${model.trim()}:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("Gemini API ${r.code}: ${text.take(400)}")
                val root = json.parseToJsonElement(text).jsonObject
                val out = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                    ?.joinToString("")
                if (out.isNullOrBlank()) error("Gemini 没有返回文本：${text.take(300)}")
                out
            }
        }
    }

    private fun requireKey() {
        check(apiKey.isNotBlank()) { "Gemini 的 API key 还没有填，请在「设置」里粘贴 AIza... key" }
    }
}
