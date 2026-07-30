package kz.invisibleshield.app.llm

import kz.invisibleshield.core.aggregator.LlmVerdict
import kz.invisibleshield.core.executor.LlmExecutor

/**
 * Декоратор над реальным LLM-исполнителем: перед дорогим инференсом смотрит в
 * [VerdictCache]. Попадание -> отдаём сохранённый вердикт, инференс НЕ запускаем
 * (экономия ~8 с/батареи на повторах и рассылках). Промах -> зовём модель и
 * записываем её вердикт в кэш.
 *
 * Вызывается на ShieldPlanner.dispatcher (ADR-005, сериализованно) — SQLite-доступ
 * идёт на том же фоновом потоке, не на main. null от модели (не готова/сбой) не
 * кэшируется и прозрачно пробрасывается (regex-only, ADR-007).
 */
class CachingLlmExecutor(
    private val delegate: LlmExecutor,
    private val cache: VerdictCache,
) : LlmExecutor {

    override fun infer(text: String, atomCategory: String, callContext: String): LlmVerdict? {
        cache.lookup(text, atomCategory, callContext)?.let { return it }
        val verdict = delegate.infer(text, atomCategory, callContext) ?: return null
        cache.store(text, atomCategory, callContext, verdict)
        return verdict
    }

    override fun warmUp() = delegate.warmUp()
}
