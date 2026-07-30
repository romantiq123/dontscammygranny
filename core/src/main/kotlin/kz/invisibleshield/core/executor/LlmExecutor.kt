package kz.invisibleshield.core.executor

import kz.invisibleshield.core.aggregator.LlmVerdict

/**
 * ADR-005: единственный тип Executor'а, трогающий native-код (JNI в llama.cpp,
 * ADR-002), GBNF-constrained decoding (ADR-004). Реализация живёт в `:app`
 * (нужен Android Context для assets/filesDir) — здесь только контракт, чтобы
 * Planner/Aggregator в `:core` можно было тестировать с фейком, не поднимая
 * реальную модель.
 *
 * Вызывающий обязан гарантировать сериализованный доступ (ShieldPlanner.dispatcher,
 * ADR-005 concurrency-требование: один llama_context на всё приложение) — сам
 * интерфейс это не обеспечивает.
 *
 * Возврат null означает "LLM не дал вердикт" (модель не загружена, инференс упал,
 * ответ не распарсился) — вызывающая сторона должна отработать по чистому
 * Atomizer-результату (Aggregator.reconcile(atom, llmVerdict = null)), т.к. LLM —
 * вторичный уточняющий слой (ADR-007), а не обязательное звено.
 */
interface LlmExecutor {
    fun infer(text: String, atomCategory: String, callContext: String = ""): LlmVerdict?

    /**
     * Необязательный ранний прогрев (напр. возобновление фоновой докачки модели,
     * если пользователь ранее дал согласие — см. LlamaCppExecutor.warmUp). По
     * умолчанию — no-op, вызывать не обязательно: infer() отработает и без него
     * (вернёт null, пока модель не готова).
     */
    fun warmUp() {}
}
