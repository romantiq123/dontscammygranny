package kz.invisibleshield.app.llm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.Normalizer
import kz.invisibleshield.app.log.FileLog
import kz.invisibleshield.core.aggregator.LlmVerdict

/**
 * Локальная БД (SQLite) — кэш вердиктов LLM по нормализованному тексту сообщения.
 * Цель: НЕ гонять дорогой инференс (~8 с, батарея, большие ядра) повторно на то же
 * сообщение — массовые рассылки/репосты берём готовым вердиктом из кэша.
 *
 * ПУТЬ A (лексический, точный). Ключ = ТОЧНЫЙ нормализованный текст (+ категория
 * Atomizer + флаг «во время звонка»). Попадание = буквально то же сообщение, что уже
 * судили -> отдать тот же вердикт БЕЗОПАСНО: риска ложняка от нечёткого матча тут
 * НЕТ. Смысловое совпадение (перефраз другими словами) — отдельный путь B через
 * эмбеддинги, сюда сознательно НЕ мешаем (нечёткий матч = чужой вердикт = ложная
 * тревога, а для пожилых это критично).
 *
 * Кэшируем ЛЮБОЙ вердикт (none/warn/danger): none тоже экономит инференс на
 * безобидных повторах и ничем не грозит (Aggregator: max(atom, none) = atom).
 * null (модель не готова / инференс упал) НЕ кэшируем — это не вердикт.
 *
 * Нормализация — консервативная (нижний регистр, NFKC, срез невидимых символов,
 * склейка пробелов). Агрессивный фолд гомоглифов (к0д->код) в КЛЮЧ намеренно НЕ
 * добавлен: пофольдить все латинские двойники -> коллизии разных текстов в один ключ
 * -> чужой вердикт. Обфускацию лучше гасить в Atomizer/пути B, не в точном кэше.
 */
class VerdictCache(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                key TEXT PRIMARY KEY,
                level TEXT NOT NULL,
                confidence TEXT NOT NULL,
                category TEXT NOT NULL,
                reason_code TEXT NOT NULL,
                score REAL NOT NULL,
                sample TEXT,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /** Вернуть сохранённый вердикт для этого сообщения, или null (промах/протухло). */
    fun lookup(text: String, atomCategory: String, callContext: String): LlmVerdict? {
        val key = keyFor(text, atomCategory, callContext)
        return try {
            readableDatabase.query(
                TABLE,
                arrayOf("level", "confidence", "category", "reason_code", "updated_at"),
                "key = ?", arrayOf(key), null, null, null,
            ).use { c ->
                if (!c.moveToFirst()) {
                    FileLog.d(TAG, "промах кэша")
                    return null
                }
                if (System.currentTimeMillis() - c.getLong(4) > TTL_MS) {
                    FileLog.d(TAG, "запись кэша протухла -> промах")
                    return null
                }
                FileLog.i(TAG, "ПОПАДАНИЕ кэша -> инференс LLM пропущен")
                LlmVerdict(
                    level = c.getString(0),
                    confidence = c.getString(1),
                    category = c.getString(2),
                    reasonCode = c.getString(3),
                )
            }
        } catch (t: Throwable) {
            FileLog.e(TAG, "ошибка чтения кэша (игнорируем, идём в LLM)", t)
            null
        }
    }

    fun store(text: String, atomCategory: String, callContext: String, verdict: LlmVerdict) {
        val key = keyFor(text, atomCategory, callContext)
        val values = ContentValues().apply {
            put("key", key)
            put("level", verdict.level)
            put("confidence", verdict.confidence)
            put("category", verdict.category)
            put("reason_code", verdict.reasonCode)
            put("score", scoreOf(verdict.level))
            put("sample", text.take(200)) // храним фразу для контекста/аудита
            put("updated_at", System.currentTimeMillis())
        }
        try {
            val db = writableDatabase
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            FileLog.d(TAG, "вердикт записан в кэш (${verdict.level}, score=${scoreOf(verdict.level)})")
            pruneIfNeeded(db)
        } catch (t: Throwable) {
            FileLog.e(TAG, "не удалось записать в кэш (не критично)", t)
        }
    }

    /** Ограничиваем рост таблицы: держим последние MAX_ROWS по времени. */
    private fun pruneIfNeeded(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM $TABLE WHERE key NOT IN " +
                "(SELECT key FROM $TABLE ORDER BY updated_at DESC LIMIT $MAX_ROWS)",
        )
    }

    private fun keyFor(text: String, atomCategory: String, callContext: String): String =
        normalize(text) + SEP + atomCategory + SEP + if (callContext.isNotBlank()) "1" else "0"

    companion object {
        private const val TAG = "VerdictCache"
        private const val DB_NAME = "verdict_cache.db"
        private const val DB_VERSION = 1
        private const val TABLE = "verdict_cache"
        private const val TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 дней — шаблоны скама ротируются
        private const val MAX_ROWS = 5000
        private const val SEP = ""

        private val SCORE = mapOf("none" to 0.0, "info" to 0.25, "warn" to 0.5, "danger" to 1.0)
        private fun scoreOf(level: String): Double = SCORE[level] ?: 0.0

        /**
         * Консервативная нормализация ключа: нижний регистр, NFKC (склеивает
         * unicode-варианты ширины/совместимости), срез невидимых/управляющих
         * символов (zero-width пробелы — частый трюк), склейка пробелов.
         */
        fun normalize(text: String): String {
            val nfkc = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKC)
            return nfkc
                .replace(Regex("[\\p{Cf}\\p{Cc}]"), "") // format + control (в т.ч. zero-width)
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
