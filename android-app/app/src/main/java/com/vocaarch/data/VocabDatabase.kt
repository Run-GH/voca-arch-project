package com.vocaarch.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.round

// ============================================================================
// VocaArch Android — Milestone 1: Room Local DB Entities, DAO & Supabase DTO
// File: android-app/app/src/main/java/com/vocaarch/data/VocabDatabase.kt
// ============================================================================

@Serializable
@Entity(
    tableName = "vocab_entries",
    indices = [
        Index(value = ["book_title", "chapter_name"]),
        Index(value = ["word"]),
        Index(value = ["seq_order"]),
        Index(value = ["error_rate"])
    ]
)
data class VocabEntryEntity(
    @PrimaryKey
    @SerialName("id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "book_title")
    @SerialName("book_title")
    val bookTitle: String? = null, // 비워져 있을 수 있음 (NULL 허용)

    @ColumnInfo(name = "chapter_name")
    @SerialName("chapter_name")
    val chapterName: String? = null, // 비워져 있을 수 있음 (NULL 허용)

    @ColumnInfo(name = "word")
    @SerialName("word")
    val word: String,

    @ColumnInfo(name = "phonetic")
    @SerialName("phonetic")
    val phonetic: String? = null,

    @ColumnInfo(name = "audio_url")
    @SerialName("audio_url")
    val audioUrl: String? = null,

    // 영단어 1개가 여러 의미를 가질 때 품사별로 행을 분리하여 저장
    @ColumnInfo(name = "part_of_speech")
    @SerialName("part_of_speech")
    val partOfSpeech: String,

    @ColumnInfo(name = "meaning_ko")
    @SerialName("meaning_ko")
    val meaningKo: String,

    @ColumnInfo(name = "example_sentence")
    @SerialName("example_sentence")
    val exampleSentence: String? = null,

    @ColumnInfo(name = "attempt_count")
    @SerialName("attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "wrong_count")
    @SerialName("wrong_count")
    val wrongCount: Int = 0,

    @ColumnInfo(name = "error_rate")
    @SerialName("error_rate")
    val errorRate: Double = 0.0,

    @ColumnInfo(name = "is_last_wrong")
    @SerialName("is_last_wrong")
    val isLastWrong: Boolean = false,

    @ColumnInfo(name = "seq_order")
    @SerialName("seq_order")
    val seqOrder: Long = System.currentTimeMillis()
) {
    fun withAttempt(isWrong: Boolean): VocabEntryEntity {
        val nextAttempt = attemptCount + 1
        val nextWrong = if (isWrong) wrongCount + 1 else wrongCount
        val nextRate = if (nextAttempt > 0) {
            round((nextWrong.toDouble() / nextAttempt.toDouble()) * 10000.0) / 100.0
        } else 0.0
        return copy(
            attemptCount = nextAttempt,
            wrongCount = nextWrong,
            errorRate = nextRate,
            isLastWrong = isWrong
        )
    }
}

enum class SortOrder(val label: String) {
    WORD_ASC("오름차순 (A→Z)"),
    WORD_DESC("내림차순 (Z→A)"),
    RANDOM("랜덤 순서"),
    INPUT_ORDER("입력된 순서"),
    ERROR_RATE_DESC("오답률 높은 순")
}

data class UserSettings(
    val fontSizeScale: Float = 1.0f,        // 상단 메뉴바 폰트 크기 배율 (0.8f ~ 1.5f)
    val showPhonetic: Boolean = true,       // 발음기호 표시 선택
    val showPos: Boolean = true,            // 품사 표시 선택
    val showExample: Boolean = true,        // 예시 문장 표시 선택
    val flashcardAutoAdvanceSec: Int = 3,   // 플래시카드 자동 넘김 시간(초, 0이면 수동)
    val flashcardInitialSide: String = "WORD" // "WORD" 또는 "MEANING"
)

@Dao
interface VocabDao {
    /**
     * 책제목별, 단원별 필터링 조회 (선택 안 하면 NULL이 전달되어 전체 단어 반환)
     */
    @Query("""
        SELECT * FROM vocab_entries
        WHERE (:bookTitle IS NULL OR :bookTitle = '' OR book_title = :bookTitle)
          AND (:chapterName IS NULL OR :chapterName = '' OR chapter_name = :chapterName)
        ORDER BY
            CASE WHEN :sortMode = 'WORD_ASC' THEN LOWER(word) END ASC,
            CASE WHEN :sortMode = 'WORD_DESC' THEN LOWER(word) END DESC,
            CASE WHEN :sortMode = 'INPUT_ORDER' THEN seq_order END ASC,
            CASE WHEN :sortMode = 'ERROR_RATE_DESC' THEN error_rate END DESC,
            seq_order ASC
    """)
    fun observeFilteredVocab(
        bookTitle: String?,
        chapterName: String?,
        sortMode: String
    ): Flow<List<VocabEntryEntity>>

    @Query("SELECT DISTINCT book_title FROM vocab_entries WHERE book_title IS NOT NULL AND book_title != '' ORDER BY book_title ASC")
    fun observeBookTitles(): Flow<List<String>>

    @Query("""
        SELECT DISTINCT chapter_name FROM vocab_entries
        WHERE (:bookTitle IS NULL OR :bookTitle = '' OR book_title = :bookTitle)
          AND chapter_name IS NOT NULL AND chapter_name != ''
        ORDER BY chapter_name ASC
    """)
    fun observeChapters(bookTitle: String?): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<VocabEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VocabEntryEntity)

    @Query("SELECT * FROM vocab_entries ORDER BY seq_order ASC")
    suspend fun getAllSnapshot(): List<VocabEntryEntity>
}

@Database(entities = [VocabEntryEntity::class], version = 1, exportSchema = false)
abstract class VocabDatabase : RoomDatabase() {
    abstract fun vocabDao(): VocabDao
}
