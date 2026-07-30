package kz.invisibleshield.app.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kz.invisibleshield.app.log.FileLog
import kz.invisibleshield.core.aggregator.LlmVerdict
import kz.invisibleshield.core.executor.LlmExecutor
import kz.invisibleshield.core.executor.LlmPromptBuilder
import org.json.JSONException
import org.json.JSONObject

/**
 * ADR-002/003/004: llama.cpp через NDK, CPU-only, GBNF-constrained decoding.
 * Модель по умолчанию — Qwen2.5-0.5B-Instruct Q4_K_M (кандидат ADR-003; финальный
 * выбор между Qwen2.5-0.5B/Qwen2.5-1.5B/Llama-3.2-1B — только после бенча на
 * референс-устройстве, см. HANDOFF "Следующие шаги"). Смена кандидата — правка
 * ТОЛЬКО [MODEL_FILENAME]/[MODEL_URL] (размер проверяется по Content-Length) и,
 * если чат-темплейт другой, промпта.
 *
 * Распространение модели (2026-07-21): модель (сотни МБ) НЕ вшита в APK —
 * скачивается с HuggingFace в filesDir/models/. Скачивание идёт ТОЛЬКО в фоновом
 * потоке (warmUp / triggerBackgroundDownload) — НИКОГДА внутри infer(), потому что
 * infer() бежит на единственном сериализованном dispatcher'е пайплайна (ADR-005),
 * и блокирующая многоминутная закачка там замораживала бы всю обработку событий
 * (и добавляла ~десятки секунд задержки к каждому алерту — баг, найденный на
 * устройстве 2026-07-21). Пока модели нет — infer() просто возвращает null, детект
 * работает на regex (ADR-007), а докачка тем временем идёт в фоне.
 *
 * ADR-005 concurrency: infer()/close() НЕ потокобезопасны и обязаны вызываться
 * только с ShieldPlanner.dispatcher. Скачивание — отдельный поток, сериализуется
 * своим atomic-флагом (downloading), с nativeLoad/nativeInfer оно не пересекается.
 */
class LlamaCppExecutor(context: Context) : LlmExecutor {

    private val appContext = context.applicationContext
    private val modelFile = File(appContext.filesDir, "models/$MODEL_FILENAME")

    private val grammarText: String by lazy {
        appContext.assets.open(GRAMMAR_ASSET).bufferedReader().use { it.readText() }
    }

    private var handle: Long = 0L
    private val downloading = AtomicBoolean(false)

    override fun infer(text: String, atomCategory: String, callContext: String): LlmVerdict? {
        if (!modelFile.exists()) {
            // ВАЖНО: не качаем здесь — мы на dispatcher'е пайплайна (ADR-005).
            // Запускаем фоновую докачку (если ещё не идёт) и работаем regex-only.
            FileLog.i(TAG, "infer(): модель ещё не готова — LLM пропущен, regex-only; докачка в фоне")
            triggerBackgroundDownload()
            return null
        }
        FileLog.i(TAG, "infer() запрошен: atomCategory=$atomCategory callContext=${callContext.ifBlank { "-" }}")
        return try {
            ensureLoaded()
            // Защитный кап длины: очень длинное уведомление (склеенная MessagingStyle-
            // переписка и т.п.) вместе с преамбулой промпта может выйти за N_CTX, и
            // llama_decode(prompt) вернёт ошибку -> откат на regex. Обычная SMS сюда
            // не упирается (сотни символов); кап срабатывает только на патологии.
            // Точная подгонка под N_CTX по токенам — по итогам бенча (ADR-002/003).
            val safeText = if (text.length > MAX_INPUT_CHARS) text.take(MAX_INPUT_CHARS) else text
            val prompt = LlmPromptBuilder.build(safeText, atomCategory, callContext)
            // Логируем ДО нативного вызова: если llama.cpp упадёт сегфолтом внутри
            // nativeInfer, это будет последняя строка в файле — сразу видно, что
            // именно инференс, а не что-то до/после него.
            FileLog.i(TAG, "-> LlamaBridge.nativeInfer(handle=$handle, maxTokens=$MAX_TOKENS)")
            val raw = LlamaBridge.nativeInfer(handle, prompt, MAX_TOKENS)
            FileLog.i(TAG, "<- nativeInfer вернул ${raw.length} символов: $raw")
            parseVerdict(raw)
        } catch (e: Exception) {
            // LLM — вторичный уточняющий слой (ADR-007): сбой инференса не должен
            // ронять пайплайн. Aggregator получит null и отработает по Atomizer.
            FileLog.e(TAG, "инференс недоступен, откатываемся на regex-only", e)
            null
        }
    }

    /**
     * Ранний прогрев — возобновляет докачку модели, ЕСЛИ пользователь ранее уже дал
     * согласие (ADR-006 UX). На самом первом запуске (согласия нет) — no-op: без
     * явного согласия ничего не качаем. Не блокирует вызывающий поток, не грузит
     * модель в память (тот шаг ленивый в ensureLoaded() при первом infer()).
     */
    override fun warmUp() {
        triggerBackgroundDownload()
    }

    /**
     * Пользователь явно нажал «Скачать» — фиксируем согласие и стартуем закачку
     * (с учётом режима «только Wi-Fi»). Это и есть «экран согласия» на скачивание
     * ~491 МБ: без этого действия закачка не начинается нигде.
     */
    fun startDownload() {
        ModelPrefs.setConsented(appContext, true)
        triggerBackgroundDownload()
    }

    private fun isUnmetered(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Запускает докачку модели в отдельном потоке — но только при согласии
     * пользователя (ADR-006) и с учётом режима «только Wi-Fi». atomic-guard не даёт
     * поднять две параллельные закачки одного файла.
     */
    private fun triggerBackgroundDownload() {
        if (modelFile.exists()) {
            ModelDownloadState.phase = ModelDownloadState.Phase.READY
            ModelDownloadState.totalBytes = modelFile.length()
            ModelDownloadState.downloadedBytes = modelFile.length()
            return
        }
        if (!ModelPrefs.isConsented(appContext)) {
            FileLog.d(TAG, "скачивание модели не начато — нет согласия пользователя")
            ModelDownloadState.phase = ModelDownloadState.Phase.IDLE
            return
        }
        if (ModelPrefs.isWifiOnly(appContext) && !isUnmetered()) {
            FileLog.i(TAG, "отложено: включён режим «только Wi-Fi», а сеть не Wi-Fi/лимитная")
            ModelDownloadState.phase = ModelDownloadState.Phase.WAITING_WIFI
            return
        }
        if (!downloading.compareAndSet(false, true)) {
            FileLog.d(TAG, "докачка уже идёт — не запускаем вторую")
            return
        }
        ModelDownloadState.phase = ModelDownloadState.Phase.DOWNLOADING
        ModelDownloadState.totalBytes = 0L // реальный размер проставит downloadWithResume из Content-Length
        Thread({
            try {
                ensureModelDownloaded()
                ModelDownloadState.phase = ModelDownloadState.Phase.READY
            } catch (e: Exception) {
                FileLog.e(TAG, "докачка модели не удалась (не блокирует пайплайн)", e)
                ModelDownloadState.phase = ModelDownloadState.Phase.FAILED
                ModelDownloadState.message = e.message ?: e.javaClass.simpleName
            } finally {
                downloading.set(false)
            }
        }, "shield-model-download").start()
    }

    private fun ensureLoaded() {
        if (handle != 0L) return
        check(modelFile.exists()) { "Модель не найдена: ${modelFile.absolutePath}" }
        // Отдаём нативному коду путь к тому же лог-файлу — нативные «крошки»
        // (в т.ч. последняя строка перед возможным сегфолтом) попадут в shield.log,
        // видимый из приложения без logcat/компьютера.
        FileLog.path()?.let { LlamaBridge.nativeSetLogPath(it) }
        FileLog.i(TAG, "-> LlamaBridge.nativeLoad(${modelFile.absolutePath}, nThreads=$N_THREADS, nCtx=$N_CTX)")
        handle = LlamaBridge.nativeLoad(modelFile.absolutePath, grammarText, N_THREADS, N_CTX)
        FileLog.i(TAG, "<- nativeLoad вернул handle=$handle")
        check(handle != 0L) { "llama.cpp: не удалось загрузить модель/контекст" }
    }

    /**
     * Качает .gguf с HuggingFace в filesDir. Устойчиво к обрывам большого файла:
     *  - докачивает с места обрыва через HTTP Range (частичная закачка в .part,
     *    сохраняется между попытками — не начинаем сотни МБ с нуля на каждый разрыв,
     *    из-за чего на устройстве закачка вообще не завершалась, 2026-07-21);
     *  - ретраит [MAX_DOWNLOAD_ATTEMPTS] раз с паузой;
     *  - проверяет итоговый размер против Content-Length сервера — ранний EOF
     *    (сервер закрыл соединение молча) не выдаёт частичный файл за готовый;
     *  - переименовывает .part -> финал только после успешной проверки, поэтому
     *    infer() никогда не увидит недокачанный файл.
     */
    @Synchronized
    private fun ensureModelDownloaded() {
        if (modelFile.exists()) {
            FileLog.d(TAG, "модель уже на диске: ${modelFile.absolutePath} (${modelFile.length()} байт)")
            return
        }
        modelFile.parentFile?.mkdirs()
        cleanupStaleModels() // снести .gguf от прошлой модели, чтобы не копить гигабайты
        val tmp = File(modelFile.parentFile, "$MODEL_FILENAME.part")
        FileLog.i(TAG, "нужно скачать модель $MODEL_FILENAME; уже есть ${tmp.length()} байт в .part")

        var attempt = 0
        while (true) {
            attempt++
            try {
                val serverTotal = downloadWithResume(tmp)
                // Целостность — по размеру, который сообщил сам сервер (Content-Length),
                // а не по захардкоженной константе (смена модели = правка только URL).
                // Если сервер размер не дал (serverTotal < 0) — принимаем как есть.
                if (serverTotal > 0 && tmp.length() != serverTotal) {
                    throw IOException("неполный файл: ${tmp.length()} из $serverTotal байт")
                }
                break
            } catch (e: Exception) {
                FileLog.w(TAG, "скачивание прервано (попытка $attempt, есть ${tmp.length()} байт)", e)
                if (attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                    throw IOException("не удалось скачать модель за $MAX_DOWNLOAD_ATTEMPTS попыток", e)
                }
                Thread.sleep(RETRY_DELAY_MS)
            }
        }

        if (!tmp.renameTo(modelFile)) {
            tmp.delete()
            error("не удалось перенести скачанную модель в ${modelFile.absolutePath}")
        }
        FileLog.i(TAG, "модель полностью скачана: ${modelFile.absolutePath} (${modelFile.length()} байт)")
    }

    /**
     * Удаляет из папки моделей всё, что не относится к ТЕКУЩЕЙ модели (старые .gguf
     * от прошлого кандидата ADR-003 + осиротевшие .part). Иначе после смены модели
     * старый файл (сотни МБ) остаётся мёртвым грузом в filesDir. Трогаем только
     * .gguf/.part в собственной папке приложения — безопасно.
     */
    private fun cleanupStaleModels() {
        val dir = modelFile.parentFile ?: return
        dir.listFiles()?.forEach { f ->
            val keep = f.name == MODEL_FILENAME || f.name == "$MODEL_FILENAME.part"
            if (!keep && (f.name.endsWith(".gguf") || f.name.endsWith(".part"))) {
                val ok = f.delete()
                FileLog.i(TAG, "удалён устаревший файл модели ${f.name} (${if (ok) "ок" else "не удалось"})")
            }
        }
    }

    /**
     * Одна попытка (до)качки: продолжает [tmp] с его текущей длины через Range.
     * Возвращает ПОЛНЫЙ размер файла по данным сервера (Content-Length), или -1,
     * если сервер его не сообщил.
     */
    private fun downloadWithResume(tmp: File): Long {
        val existing = if (tmp.exists()) tmp.length() else 0L
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")

            val code = connection.responseCode
            val partial = code == HttpURLConnection.HTTP_PARTIAL
            FileLog.i(TAG, "HTTP $code (запросили с $existing байт, докачка=${if (partial) "да" else "с нуля"})")
            check(code == HttpURLConnection.HTTP_OK || partial) { "HTTP $code при скачивании модели" }

            // partial (206) — сервер докачивает, дописываем в конец .part.
            // 200 — сервер отдал файл целиком (Range проигнорирован), пишем с нуля.
            val startFrom = if (partial) existing else 0L
            // На 206 contentLengthLong = ОСТАТОК, на 200 = весь файл. Полный размер:
            val serverTotal =
                if (connection.contentLengthLong > 0) startFrom + connection.contentLengthLong else -1L
            if (serverTotal > 0) ModelDownloadState.totalBytes = serverTotal

            connection.inputStream.use { input ->
                FileOutputStream(tmp, /* append = */ partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = startFrom
                    var lastLogged10Mb = downloaded / TEN_MB
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        ModelDownloadState.downloadedBytes = downloaded
                        val cur10Mb = downloaded / TEN_MB
                        if (cur10Mb != lastLogged10Mb) {
                            lastLogged10Mb = cur10Mb
                            val totalMb = if (serverTotal > 0) "${serverTotal / 1_000_000}" else "?"
                            FileLog.i(TAG, "скачивание: ${downloaded / 1_000_000} / $totalMb МБ")
                        }
                    }
                }
            }
            return serverTotal
        } finally {
            connection.disconnect()
        }
    }

    private fun parseVerdict(raw: String): LlmVerdict? {
        return try {
            val json = JSONObject(raw)
            LlmVerdict(
                level = json.getString("level"),
                confidence = json.getString("confidence"),
                category = json.getString("category"),
                reasonCode = json.getString("reason_code"),
            ).also { FileLog.i(TAG, "распарсено: $it") }
        } catch (e: JSONException) {
            // GBNF гарантирует валидную структуру при штатном завершении
            // генерации (ADR-004) — сюда попадаем только если генерация
            // оборвалась по maxTokens раньше, чем root успел закрыться.
            FileLog.e(TAG, "не удалось распарсить ответ модели: $raw", e)
            null
        }
    }

    fun close() {
        if (handle != 0L) {
            LlamaBridge.nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "LlamaCppExecutor"
        // Кандидат ADR-003. Смена модели = правка ТОЛЬКО этих двух строк: целостность
        // докачки проверяется по Content-Length от сервера (ensureModelDownloaded),
        // хардкод размера больше не нужен. URL — официальное non-gated репо Qwen.
        const val MODEL_FILENAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/$MODEL_FILENAME"
        private const val GRAMMAR_ASSET = "verdict.gbnf"
        private const val N_THREADS = 4
        // ChatML-промпт с few-shot занимает ~250-300 токенов, поэтому 512 было тесно
        // (длинное SMS переполняло контекст -> decode !=0 -> откат на regex). 1024
        // даёт запас под промпт + сообщение + генерацию; для 0.5B это ~25 МБ KV-кэша.
        private const val N_CTX = 1024
        private const val MAX_TOKENS = 64
        // Верхняя граница длины анализируемого текста (символы), защитный клапан
        // против переполнения N_CTX на аномально длинных уведомлениях. См. infer().
        // Подобрано под N_CTX=1024 минус фиксированный промпт (~300 токенов) и генерацию.
        private const val MAX_INPUT_CHARS = 1200
        private const val MAX_DOWNLOAD_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 3_000L
        private const val TEN_MB = 10L * 1024 * 1024
    }
}
