package com.everydayecho.memorykeeper.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface GeminiPart {
    data class Text(val text: String) : GeminiPart
    data class FileRef(val uri: String, val mimeType: String) : GeminiPart
}

/**
 * Google Gemini (generativelanguage API). The only provider here that takes
 * audio directly: audio is uploaded through the Files API and referenced from
 * the prompt, so the model "listens" and writes the letter in one call.
 */
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
        generateParts(system, listOf(GeminiPart.Text(userMessage)), maxTokens, temperature)

    suspend fun generateParts(
        system: String,
        parts: List<GeminiPart>,
        maxTokens: Int = 2048,
        temperature: Double = 0.7,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            requireKey()
            val body = buildJsonObject {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(system)) }) })
                })
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("parts", JsonArray(parts.map { part ->
                            when (part) {
                                is GeminiPart.Text -> buildJsonObject { put("text", JsonPrimitive(part.text)) }
                                is GeminiPart.FileRef -> buildJsonObject {
                                    put("fileData", buildJsonObject {
                                        put("mimeType", JsonPrimitive(part.mimeType))
                                        put("fileUri", JsonPrimitive(part.uri))
                                    })
                                }
                            }
                        }))
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

    /** Uploads [data] via the resumable Files API and waits until it is ACTIVE. Returns the file URI. */
    suspend fun uploadFile(data: ByteArray, mimeType: String, displayName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireKey()
                val start = Request.Builder()
                    .url("$base/upload/v1beta/files")
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("X-Goog-Upload-Protocol", "resumable")
                    .addHeader("X-Goog-Upload-Command", "start")
                    .addHeader("X-Goog-Upload-Header-Content-Length", data.size.toString())
                    .addHeader("X-Goog-Upload-Header-Content-Type", mimeType)
                    .post(
                        """{"file":{"display_name":"$displayName"}}""".toRequestBody("application/json".toMediaType()),
                    )
                    .build()
                val uploadUrl = http.newCall(start).execute().use { r ->
                    if (!r.isSuccessful) error("Gemini 上传初始化失败 ${r.code}: ${r.body?.string().orEmpty().take(300)}")
                    r.header("x-goog-upload-url") ?: error("Gemini 上传初始化没有返回 upload url")
                }
                val upload = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("X-Goog-Upload-Offset", "0")
                    .addHeader("X-Goog-Upload-Command", "upload, finalize")
                    .post(data.toRequestBody(mimeType.toMediaType()))
                    .build()
                val info = http.newCall(upload).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    if (!r.isSuccessful) error("Gemini 上传失败 ${r.code}: ${t.take(300)}")
                    json.parseToJsonElement(t).jsonObject["file"]!!.jsonObject
                }
                val uri = info["uri"]!!.jsonPrimitive.content
                val name = info["name"]!!.jsonPrimitive.content
                var state = info["state"]?.jsonPrimitive?.content
                var tries = 0
                while (state == "PROCESSING" && tries++ < 60) {
                    delay(2000)
                    val poll = Request.Builder().url("$base/v1beta/$name").addHeader("x-goog-api-key", apiKey).build()
                    state = http.newCall(poll).execute().use { r ->
                        json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["state"]?.jsonPrimitive?.content
                    }
                }
                if (state == "FAILED") error("Gemini 处理音频失败")
                uri
            }
        }

    suspend fun uploadFile(file: File, mimeType: String): Result<String> =
        uploadFile(file.readBytes(), mimeType, file.name)

    private fun requireKey() {
        check(apiKey.isNotBlank()) { "Gemini 的 API key 还没有填，请在「设置」里粘贴 AIza... key" }
    }
}
