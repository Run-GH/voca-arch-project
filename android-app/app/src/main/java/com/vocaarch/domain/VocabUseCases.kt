package com.vocaarch.domain

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.vocaarch.data.VocabDao
import com.vocaarch.data.VocabEntryEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// ============================================================================
// VocaArch Android — Milestone 1, 3, 4:
//   - OCR 이미지 파서 & 다품사 행 분리(Multi-POS Row Splitter)
//   - 파일(CSV/JSON) 입출력 및 DB 고속 인덱싱
//   - 누락 발음기호(Free Dictionary API + 내장 사전) 및 음성(TTS/MP3/직접 업로드) 매니저
// File: android-app/app/src/main/java/com/vocaarch/domain/VocabUseCases.kt
// ============================================================================

class VocabDomainManager(
    private val context: Context,
    private val vocabDao: VocabDao,
    private val supabase: SupabaseClient? = null
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isTtsReady = false

    // 내장 오프라인 발음기호 폴백 사전 (네트워크 단절 시 즉시 조회)
    private val offlineIpaFallback = mapOf(
        "abandon" to "/əˈbæn.dən/",
        "present" to "/ˈprez.ənt/",
        "conduct" to "/ˈkɒn.dʌkt/",
        "elaborate" to "/iˈlæb.ər.ət/",
        "ubiquitous" to "/juːˈbɪk.wɪ.təs/",
        "ephemeral" to "/ɪˈfem.ər.əl/",
        "resilient" to "/rɪˈzɪl.jənt/",
        "ambiguous" to "/æmˈbɪɡ.ju.əs/"
    )

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(0.95f)
            isTtsReady = true
        }
    }

    /**
     * 1. 소리 버튼 클릭 시 음성 재생 (Fallback Chain)
     *    1순위: DB에 저장된 audioUrl (사용자 업로드 음성 또는 사전 원어민 MP3)
     *    2순위: Android 내장 TextToSpeech 엔진
     */
    fun playWordAudio(entry: VocabEntryEntity) {
        val url = entry.audioUrl
        if (!url.isNullOrBlank()) {
            try {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(url)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { it.release() }
                    setOnErrorListener { mp, _, _ ->
                        mp.release()
                        speakWithNativeTts(entry.word, entry.id)
                        true
                    }
                    prepareAsync()
                }
                return
            } catch (_: Exception) {
                // 스트리밍 실패 시 즉시 내장 TTS로 폴백
            }
        }
        speakWithNativeTts(entry.word, entry.id)
    }

    private fun speakWithNativeTts(word: String, utteranceId: String) {
        if (isTtsReady) {
            tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    /**
     * 2. 사용자가 직접 녹음하거나 선택한 음성 파일(.mp3, .m4a)을 Supabase Storage에 업로드하고 DB에 연결
     */
    suspend fun attachCustomAudioFile(entry: VocabEntryEntity, audioBytes: ByteArray, ext: String = "mp3"): VocabEntryEntity =
        withContext(Dispatchers.IO) {
            val fileName = "${entry.word.lowercase()}_${entry.id}.$ext"
            val publicUrl = if (supabase != null) {
                val bucket = supabase.storage.from("vocab-audio")
                bucket.upload(fileName, audioBytes, upsert = true)
                bucket.publicUrl(fileName)
            } else {
                val localFile = File(context.filesDir, fileName)
                localFile.writeBytes(audioBytes)
                localFile.absolutePath
            }
            val updated = entry.copy(audioUrl = publicUrl)
            vocabDao.upsert(updated)
            syncEntryToSupabase(updated)
            updated
        }

    /**
     * 3. 누락된 발음기호(IPA) 및 원어민 음성 URL 자동 보강 (Free Dictionary API -> 내장 사전 폴백)
     */
    suspend fun enrichMissingPhoneticAndAudio(word: String): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            val cleanWord = word.trim().lowercase()
            try {
                val apiUrl = URL("https://api.dictionaryapi.dev/api/v2/entries/en/$cleanWord")
                val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonArr = Json.parseToJsonElement(body).jsonArray
                    val firstObj = jsonArr.firstOrNull()?.jsonObject
                    var phonetic = firstObj?.get("phonetic")?.jsonPrimitive?.contentOrNull
                    var audioUrl: String? = null
                    val phoneticsArr = firstObj?.get("phonetics")?.jsonArray
                    if (phoneticsArr != null) {
                        for (item in phoneticsArr) {
                            val obj = item.jsonObject
                            if (phonetic.isNullOrBlank()) {
                                phonetic = obj["text"]?.jsonPrimitive?.contentOrNull
                            }
                            val rawAudio = obj["audio"]?.jsonPrimitive?.contentOrNull
                            if (audioUrl.isNullOrBlank() && !rawAudio.isNullOrBlank()) {
                                audioUrl = if (rawAudio.startsWith("//")) "https:$rawAudio" else rawAudio
                            }
                        }
                    }
                    return@withContext Pair(phonetic ?: offlineIpaFallback[cleanWord], audioUrl)
                }
            } catch (_: Exception) {
                // 오프라인 또는 타임아웃 시 내장 사전 폴백
            }
            Pair(offlineIpaFallback[cleanWord], null)
        }

    /**
     * 4. 이미지 OCR 인식 및 다품사(1단어 다의미) 행 분리 파이프라인
     */
    suspend fun parseVocabFromBitmap(
        bitmap: Bitmap,
        bookTitle: String?,
        chapterName: String?
    ): List<VocabEntryEntity> = withContext(Dispatchers.Default) {
        // ML Kit Korean+Latin TextRecognizer로 영단어와 한글 뜻 동시 추출
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = recognizer.process(image).await()

        parseRawTextAndEnrich(
            rawText = visionText.text,
            bookTitle = bookTitle?.takeIf { it.isNotBlank() },
            chapterName = chapterName?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * 한 줄에 여러 품사가 포함된 텍스트(예: "present /preznt/ n. 선물 v. 제출하다 a. 현재의 | This is a present.")를
     * 품사별 독립 행(VocabEntryEntity)으로 분리하고 누락된 발음기호를 채웁니다.
     */
    suspend fun parseRawTextAndEnrich(
        rawText: String,
        bookTitle: String?,
        chapterName: String?
    ): List<VocabEntryEntity> = withContext(Dispatchers.IO) {
        val posRegex = Regex("""(?:^|\s|/|;|,)(n\.|v\.|vt\.|vi\.|adj\.|a\.|adv\.|ad\.|명\.|동\.|형\.|부\.)\s*""", RegexOption.IGNORE_CASE)
        val headRegex = Regex("""^([a-zA-Z\-]+)\s*(?:([/\[][^/\]\n]+[/\]]))?\s+(.+)$""")
        val result = mutableListOf<VocabEntryEntity>()
        var seqCounter = System.currentTimeMillis()

        for (rawLine in rawText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val parts = line.split(Regex("""\s*\|\s*|\s*Ex:\s*""", RegexOption.IGNORE_CASE), limit = 2)
            val mainPart = parts[0].trim()
            val exampleSentence = parts.getOrNull(1)?.trim()

            val headMatch = headRegex.find(mainPart) ?: continue
            val word = headMatch.groupValues[1].trim()
            var phonetic = headMatch.groupValues[2].takeIf { it.isNotBlank() }
            val meaningsBlock = headMatch.groupValues[3].trim()

            // 발음기호가 이미지에 없다면 Dictionary API / 내장 사전에서 자동 확보
            var audioUrl: String? = null
            if (phonetic == null) {
                val enriched = enrichMissingPhoneticAndAudio(word)
                phonetic = enriched.first
                audioUrl = enriched.second
            }

            val posMatches = posRegex.findAll(meaningsBlock).toList()
            if (posMatches.isEmpty()) {
                result.add(
                    VocabEntryEntity(
                        bookTitle = bookTitle,
                        chapterName = chapterName,
                        word = word,
                        phonetic = phonetic,
                        audioUrl = audioUrl,
                        partOfSpeech = "n.",
                        meaningKo = meaningsBlock,
                        exampleSentence = exampleSentence,
                        seqOrder = seqCounter++
                    )
                )
            } else {
                for (i in posMatches.indices) {
                    val rawPos = posMatches[i].groupValues[1].lowercase()
                    val normalizedPos = when (rawPos) {
                        "명.", "n." -> "n."
                        "동.", "v.", "vt.", "vi." -> "v."
                        "형.", "adj.", "a." -> "adj."
                        "부.", "adv.", "ad." -> "adv."
                        else -> rawPos
                    }
                    val startIdx = posMatches[i].range.last + 1
                    val endIdx = if (i + 1 < posMatches.size) posMatches[i + 1].range.first else meaningsBlock.length
                    val meaningKo = meaningsBlock.substring(startIdx, endIdx).trim(' ', '/', ';', ',')

                    if (meaningKo.isNotEmpty()) {
                        result.add(
                            VocabEntryEntity(
                                bookTitle = bookTitle,
                                chapterName = chapterName,
                                word = word,
                                phonetic = phonetic,
                                audioUrl = audioUrl,
                                partOfSpeech = normalizedPos,
                                meaningKo = meaningKo,
                                exampleSentence = exampleSentence,
                                seqOrder = seqCounter++
                            )
                        )
                    }
                }
            }
        }

        if (result.isNotEmpty()) {
            vocabDao.upsertAll(result)
            syncBatchToSupabase(result)
        }
        result
    }

    /**
     * 5. DB -> CSV 파일 출력 (Export)
     */
    suspend fun exportDbToCsv(entries: List<VocabEntryEntity>): String = withContext(Dispatchers.Default) {
        val sb = StringBuilder()
        sb.appendLine("book_title,chapter_name,word,phonetic,part_of_speech,meaning_ko,example_sentence,attempt_count,wrong_count,error_rate,audio_url")
        for (e in entries) {
            fun esc(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
            sb.appendLine(
                listOf(
                    esc(e.bookTitle),
                    esc(e.chapterName),
                    esc(e.word),
                    esc(e.phonetic),
                    esc(e.partOfSpeech),
                    esc(e.meaningKo),
                    esc(e.exampleSentence),
                    e.attemptCount,
                    e.wrongCount,
                    e.errorRate,
                    esc(e.audioUrl)
                ).joinToString(",")
            )
        }
        sb.toString()
    }

    /**
     * 6. CSV 파일을 읽어 DB에 고속 인덱싱 (Import & Index)
     */
    suspend fun importCsvAndIndexToDb(
        inputStream: InputStream,
        defaultBookTitle: String?,
        defaultChapterName: String?
    ): Int = withContext(Dispatchers.IO) {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val lines = reader.readLines()
        if (lines.size <= 1) return@withContext 0

        val entries = mutableListOf<VocabEntryEntity>()
        var seq = System.currentTimeMillis()

        for (i in 1 until lines.size) {
            val cols = parseCsvLine(lines[i])
            if (cols.size < 6) continue
            val word = cols[2].trim()
            if (word.isEmpty()) continue

            var phonetic = cols[3].trim().takeIf { it.isNotEmpty() }
            var audioUrl = cols.getOrNull(10)?.trim()?.takeIf { it.isNotEmpty() }
            if (phonetic == null) {
                val enriched = enrichMissingPhoneticAndAudio(word)
                phonetic = enriched.first
                if (audioUrl == null) audioUrl = enriched.second
            }

            entries.add(
                VocabEntryEntity(
                    bookTitle = cols[0].trim().ifEmpty { defaultBookTitle },
                    chapterName = cols[1].trim().ifEmpty { defaultChapterName },
                    word = word,
                    phonetic = phonetic,
                    partOfSpeech = cols[4].trim().ifEmpty { "n." },
                    meaningKo = cols[5].trim(),
                    exampleSentence = cols.getOrNull(6)?.trim()?.ifEmpty { null },
                    attemptCount = cols.getOrNull(7)?.toIntOrNull() ?: 0,
                    wrongCount = cols.getOrNull(8)?.toIntOrNull() ?: 0,
                    errorRate = cols.getOrNull(9)?.toDoubleOrNull() ?: 0.0,
                    audioUrl = audioUrl,
                    seqOrder = seq++
                )
            )
        }

        vocabDao.upsertAll(entries)
        syncBatchToSupabase(entries)
        entries.size
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    cur.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                result.add(cur.toString())
                cur.clear()
            } else {
                cur.append(c)
            }
            i++
        }
        result.add(cur.toString())
        return result
    }

    suspend fun recordAttemptAndSync(entry: VocabEntryEntity, isWrong: Boolean) {
        val updated = entry.withAttempt(isWrong)
        vocabDao.upsert(updated)
        syncEntryToSupabase(updated)
    }

    private fun syncEntryToSupabase(entry: VocabEntryEntity) {
        val client = supabase ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                client.postgrest["vocab_entries"].upsert(entry)
            }
        }
    }

    private fun syncBatchToSupabase(entries: List<VocabEntryEntity>) {
        val client = supabase ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                client.postgrest["vocab_entries"].upsert(entries)
            }
        }
    }
}
