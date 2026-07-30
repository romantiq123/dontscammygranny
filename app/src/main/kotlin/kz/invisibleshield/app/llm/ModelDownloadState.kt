package kz.invisibleshield.app.llm

/**
 * Наблюдаемое состояние скачивания модели — мост между фоновой закачкой в
 * [LlamaCppExecutor] и онбординг-экраном ([kz.invisibleshield.app.ui.MainActivity]),
 * который его периодически опрашивает и рисует карточку "Модель ИИ".
 *
 * Держим отдельным object'ом (а не полями executor'а), чтобы UI не зависел от
 * конкретного типа `LlmExecutor` и не тащил download-специфику в core-интерфейс.
 * Поля @Volatile — пишутся из потока закачки `shield-model-download`, читаются из
 * main-потока UI; атомарность одного поля здесь достаточна (согласованный снимок
 * из нескольких полей не нужен — это индикатор, не источник истины).
 */
object ModelDownloadState {

    enum class Phase { IDLE, WAITING_WIFI, DOWNLOADING, READY, FAILED }

    @Volatile var phase: Phase = Phase.IDLE
    @Volatile var downloadedBytes: Long = 0L
    @Volatile var totalBytes: Long = 0L
    @Volatile var message: String = ""

    val percent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}
