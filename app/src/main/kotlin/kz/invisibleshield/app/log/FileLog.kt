package kz.invisibleshield.app.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Файловый лог — дублирует Logcat в файл на внешнем хранилище приложения
 * (`getExternalFilesDir`, НЕ приватный internal filesDir), чтобы его можно было
 * найти файловым менеджером на самом телефоне без adb/компьютера: путь —
 * `Android/data/kz.invisibleshield.app/files/logs/shield.log` на внутренней
 * памяти устройства. На части прошивок (Android 11+) системный "Файлы" прячет
 * Android/data из обычного просмотра — тогда путь до файла надёжнее смотреть
 * через кнопку "Показать лог" на онбординге (MainActivity читает файл
 * напрямую), а не искать его файловым менеджером.
 *
 * ВАЖНО (предел этого механизма): нативный краш (SIGSEGV и т.п. в
 * llama_bridge.cpp, если там баг/дрейф API llama.cpp) убивает процесс
 * мгновенно — Kotlin не успевает ничего поймать и дописать в файл. Для таких
 * крашей всё равно нужен logcat/adb bugreport с компьютера; этот лог покрывает
 * всё, что происходит на Kotlin/JVM-стороне (скачивание модели, JNI-вызовы до
 * креша, парсинг ответа, весь пайплайн Atomizer/Planner/Aggregator/Alert).
 */
object FileLog {
    private const val MAX_BYTES = 2 * 1024 * 1024 // ротация, чтобы лог не рос бесконечно
    private const val TAG_DEFAULT = "InvisibleShield"

    // Маркер сборки — бампать при каждом значимом изменении, чтобы по логу было
    // видно, ТОТ ли APK установлен (частая путаница: тестер гоняет старую сборку).
    private const val BUILD_TAG = "2026-07-22-ctx1024+trim-prompt"

    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        logFile = File(dir, "shield.log")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("Crash", "Необработанное исключение в потоке ${thread.name}", throwable)
            // Даём сработать тому, что было бы и без нас (обычно — убивает процесс) —
            // здесь только логируем перед этим, поведение крэша не меняем.
            previous?.uncaughtException(thread, throwable)
        }
        i(TAG_DEFAULT, "=== старт приложения (build=$BUILD_TAG), лог: ${logFile?.absolutePath} ===")
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    fun path(): String? = logFile?.absolutePath

    /** Последние [maxChars] символов лога — для показа прямо в приложении. */
    fun tail(maxChars: Int = 20_000): String {
        val file = logFile ?: return "Лог ещё не инициализирован."
        if (!file.exists()) return "Файл лога пока пуст: ${file.absolutePath}"
        return try {
            val text = file.readText()
            if (text.length > maxChars) "…\n" + text.takeLast(maxChars) else text
        } catch (e: Exception) {
            "Не удалось прочитать лог: ${e.message}"
        }
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        // Дублируем в стандартный Logcat — он тоже пригодится, когда появится компьютер.
        when (level) {
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg, t)
            "E" -> Log.e(tag, msg, t)
        }
        val file = logFile ?: return
        try {
            rotateIfNeeded(file)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$timestamp $level/$tag: $msg\n")
            if (t != null) file.appendText(Log.getStackTraceString(t) + "\n")
        } catch (_: Exception) {
            // Файловый лог — вспомогательный инструмент, его сбой не должен ничего ронять.
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_BYTES) file.delete()
    }
}
