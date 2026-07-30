package kz.invisibleshield.app.llm

/**
 * Тонкий JNI-фасад над llama_bridge.cpp (ADR-002). Ничего не решает сам —
 * не проверяет пути, не сериализует вызовы (это ответственность
 * LlamaCppExecutor/ShieldPlanner.dispatcher, ADR-005).
 */
internal object LlamaBridge {

    init {
        System.loadLibrary("llama_bridge")
    }

    /**
     * Путь к файлу лога (shield.log) для нативных «хлебных крошек» — нативные
     * шаги пишутся ТУДА ЖЕ, что и Kotlin FileLog, чтобы их было видно без logcat/
     * компьютера. Вызывать один раз до nativeLoad.
     */
    external fun nativeSetLogPath(path: String)

    /** @return handle (указатель на LlamaHandle) или 0 при ошибке загрузки. */
    external fun nativeLoad(modelPath: String, grammarText: String, nThreads: Int, nCtx: Int): Long

    /** Блокирующий вызов — выполнять только на выделенном single-thread dispatcher'е. */
    external fun nativeInfer(handle: Long, prompt: String, maxTokens: Int): String

    external fun nativeFree(handle: Long)
}
