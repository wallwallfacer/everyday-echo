package com.everydayecho.memorykeeper.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The three-layer file system per spec §9:
 *   SOUL.md     — persona, mostly immutable
 *   USER.md     — user context, evolves
 *   MEMORY.md   — pinned moments, append-only
 *
 * Plus transient data:
 *   transcripts/yyyy-MM-dd.md — daily transcript (7d TTL)
 *   letters/yyyy-MM-dd.md     — generated letters (kept)
 *
 * Everything lives in app-private storage. Audio is never persisted past
 * the transcription step.
 */
class FileRepository(private val context: Context) {

    private val root: File = File(context.filesDir, "MemoryKeeper").also { it.mkdirs() }
    private val transcriptsDir: File = File(root, "data/transcripts").also { it.mkdirs() }
    private val lettersDir: File = File(root, "data/letters").also { it.mkdirs() }
    private val audioDir: File = File(root, "data/audio").also { it.mkdirs() }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val soulFile = File(root, "SOUL.md")
    private val userFile = File(root, "USER.md")
    private val userJsonFile = File(root, "user_profile.json")
    private val memoryFile = File(root, "MEMORY.md")

    fun rootDir(): File = root
    fun audioDir(): File = audioDir

    // ----- SOUL.md -----
    suspend fun ensureSoul(): String = withContext(Dispatchers.IO) {
        if (!soulFile.exists()) {
            soulFile.writeText(SoulTemplate.SOUL_MD)
        }
        soulFile.readText()
    }

    suspend fun writeSoul(content: String) = withContext(Dispatchers.IO) {
        soulFile.writeText(content)
    }

    // ----- USER.md / UserProfile -----
    suspend fun loadProfile(): UserProfile = withContext(Dispatchers.IO) {
        if (!userJsonFile.exists()) return@withContext UserProfile()
        runCatching { json.decodeFromString<UserProfile>(userJsonFile.readText()) }
            .getOrElse { UserProfile() }
    }

    suspend fun saveProfile(profile: UserProfile) = withContext(Dispatchers.IO) {
        userJsonFile.writeText(json.encodeToString(UserProfile.serializer(), profile))
        userFile.writeText(profile.toMarkdown())
    }

    suspend fun readUserMd(): String = withContext(Dispatchers.IO) {
        if (userFile.exists()) userFile.readText() else loadProfile().toMarkdown()
    }

    // ----- MEMORY.md -----
    suspend fun readMemory(): String = withContext(Dispatchers.IO) {
        if (!memoryFile.exists()) {
            memoryFile.writeText("# MEMORY.md\n# 一个时间倒序的「被 pin 的瞬间」长期日志\n\n")
        }
        memoryFile.readText()
    }

    suspend fun appendMemory(entry: MemoryEntry) = withContext(Dispatchers.IO) {
        val existing = readMemory()
        val header = "## ${entry.date}"
        val block = buildString {
            append("- **${entry.kind}** ${entry.text}")
            appendLine()
            if (entry.tags.isNotEmpty()) appendLine("  - tags: ${entry.tags.joinToString(", ")}")
            appendLine("  - note: ${entry.note.ifBlank { "（空）" }}")
            appendLine("  - source: ${entry.source}")
        }
        val updated = if (existing.contains("\n$header\n") || existing.startsWith("$header\n")) {
            val idx = existing.indexOf(header)
            val before = existing.substring(0, idx + header.length + 1)
            val after = existing.substring(idx + header.length + 1)
            before + block + after
        } else {
            val divider = if (existing.endsWith("\n\n")) "" else if (existing.endsWith("\n")) "\n" else "\n\n"
            existing + divider + header + "\n" + block
        }
        memoryFile.writeText(updated)
    }

    suspend fun writeMemory(content: String) = withContext(Dispatchers.IO) {
        memoryFile.writeText(content)
    }

    // ----- Transcripts -----
    suspend fun appendTranscript(date: LocalDate, fragment: TranscriptFragment) = withContext(Dispatchers.IO) {
        val file = File(transcriptsDir, "${date.format(DATE_FMT)}.md")
        if (!file.exists()) {
            file.writeText("# Transcript ${date.format(DATE_FMT)}\n\n")
        }
        val line = buildString {
            append("- [")
            append(fragment.timestamp.format(TIME_FMT))
            append("] (")
            append(fragment.speaker)
            append(") ")
            append(fragment.text.replace("\n", " "))
        }
        file.appendText(line + "\n")
    }

    suspend fun readTranscript(date: LocalDate): String = withContext(Dispatchers.IO) {
        val file = File(transcriptsDir, "${date.format(DATE_FMT)}.md")
        if (file.exists()) file.readText() else ""
    }

    suspend fun listTranscriptDates(): List<LocalDate> = withContext(Dispatchers.IO) {
        transcriptsDir.listFiles().orEmpty()
            .mapNotNull { runCatching { LocalDate.parse(it.nameWithoutExtension, DATE_FMT) }.getOrNull() }
            .sortedDescending()
    }

    /** Copies one day's transcript (or a zip of all days when [date] is null) to cache for sharing. */
    suspend fun exportTranscripts(date: LocalDate?): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").also { it.deleteRecursively(); it.mkdirs() }
        if (date != null) {
            val src = File(transcriptsDir, "${date.format(DATE_FMT)}.md")
            if (!src.exists()) return@withContext null
            File(dir, src.name).also { src.copyTo(it, overwrite = true) }
        } else {
            val files = transcriptsDir.listFiles().orEmpty().filter { it.extension == "md" }.sortedBy { it.name }
            if (files.isEmpty()) return@withContext null
            val zip = File(dir, "transcripts.zip")
            java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
                files.forEach { f ->
                    out.putNextEntry(java.util.zip.ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            zip
        }
    }

    // ----- Raw recordings (data/audio/recordings/yyyy-MM-dd/HHmmss.m4a) -----
    fun recordingsDir(date: LocalDate): File =
        File(audioDir, "recordings/${date.format(DATE_FMT)}")

    data class RecordingDay(val date: LocalDate, val files: Int, val bytes: Long)

    suspend fun listRecordingDays(): List<RecordingDay> = withContext(Dispatchers.IO) {
        File(audioDir, "recordings").listFiles().orEmpty().mapNotNull { d ->
            val date = runCatching { LocalDate.parse(d.name, DATE_FMT) }.getOrNull() ?: return@mapNotNull null
            val fs = d.listFiles().orEmpty().filter { it.extension in AUDIO_EXT }
            if (fs.isEmpty()) null else RecordingDay(date, fs.size, fs.sumOf { it.length() })
        }.sortedByDescending { it.date }
    }

    suspend fun exportRecordings(date: LocalDate): File? = withContext(Dispatchers.IO) {
        val files = recordingsDir(date).listFiles().orEmpty().filter { it.extension in AUDIO_EXT }.sortedBy { it.name }
        if (files.isEmpty()) return@withContext null
        val dir = File(context.cacheDir, "exports").also { it.deleteRecursively(); it.mkdirs() }
        val zip = File(dir, "recordings-${date.format(DATE_FMT)}.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.setLevel(0)
            files.forEach { f ->
                out.putNextEntry(java.util.zip.ZipEntry(f.name))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        zip
    }

    suspend fun pruneOldRecordings(retentionDays: Int = 7) = withContext(Dispatchers.IO) {
        val cutoff = LocalDate.now().minusDays(retentionDays.toLong())
        File(audioDir, "recordings").listFiles()?.forEach { d ->
            val date = runCatching { LocalDate.parse(d.name, DATE_FMT) }.getOrNull()
            if (date != null && date.isBefore(cutoff)) d.deleteRecursively()
        }
    }

    suspend fun pruneOldTranscripts(retentionDays: Int = 7) = withContext(Dispatchers.IO) {
        val cutoff = LocalDate.now().minusDays(retentionDays.toLong())
        transcriptsDir.listFiles()?.forEach { f ->
            val name = f.nameWithoutExtension
            val date = runCatching { LocalDate.parse(name, DATE_FMT) }.getOrNull()
            if (date != null && date.isBefore(cutoff)) f.delete()
        }
    }

    // ----- Letters -----
    suspend fun saveLetter(date: LocalDate, content: String) = withContext(Dispatchers.IO) {
        File(lettersDir, "${date.format(DATE_FMT)}.md").writeText(content)
    }

    suspend fun readLetter(date: LocalDate): String? = withContext(Dispatchers.IO) {
        val f = File(lettersDir, "${date.format(DATE_FMT)}.md")
        if (f.exists()) f.readText() else null
    }

    suspend fun listLetterDates(): List<LocalDate> = withContext(Dispatchers.IO) {
        lettersDir.listFiles().orEmpty()
            .mapNotNull { runCatching { LocalDate.parse(it.nameWithoutExtension, DATE_FMT) }.getOrNull() }
            .sortedDescending()
    }

    // ----- Nuclear -----
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        root.deleteRecursively()
        root.mkdirs()
        transcriptsDir.mkdirs()
        lettersDir.mkdirs()
        audioDir.mkdirs()
    }

    suspend fun deleteToday(date: LocalDate) = withContext(Dispatchers.IO) {
        File(transcriptsDir, "${date.format(DATE_FMT)}.md").delete()
        File(lettersDir, "${date.format(DATE_FMT)}.md").delete()
    }

    /** Today's saved speech segments, oldest first. */
    suspend fun listRecordingFiles(date: LocalDate): List<File> = withContext(Dispatchers.IO) {
        recordingsDir(date).listFiles().orEmpty().filter { it.extension in AUDIO_EXT }.sortedBy { it.name }
    }

    companion object {
        private val AUDIO_EXT = setOf("aac", "m4a")
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

data class TranscriptFragment(
    val timestamp: LocalDateTime,
    val text: String,
    val speaker: String = "self?",
)

data class MemoryEntry(
    val date: String,
    val kind: String, // "moment" | "note"
    val text: String,
    val tags: List<String> = emptyList(),
    val note: String = "",
    val source: String = "manual",
)
