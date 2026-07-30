package kz.invisibleshield.core.aggregator

import kz.invisibleshield.core.atomizer.AtomResult
import kz.invisibleshield.core.model.Level
import kz.invisibleshield.core.model.Verdict

/**
 * Невидимый Щит — Aggregator (ADR-005), сведение Atomizer + LLM-Executor в Verdict.
 * Порт aggregator.py на Kotlin/JVM.
 *
 * Политика согласования (ADR-005, "Политика согласования Aggregator", закрыта 07.2026):
 *   final_level = max(atomizer_level, llm_level) — ни один источник не может
 *   ПОНИЗИТЬ итог, только повысить. category/reason_code берутся из источника,
 *   чей level победил. При равенстве level предпочитаем category/reason_code
 *   от LLM (он видел полный текст); regex-кандидат остаётся в reasons для аудита.
 *
 *   Третий вариант из ADR ("конфликт -> отдельный уровень требует ручной
 *   проверки") сознательно НЕ реализован — см. decisions.md.
 */

/** JSON-вердикт LLM-Executor'а (ADR-004 GBNF-схема), уже распарсенный. */
data class LlmVerdict(
    val level: String,
    val confidence: String,
    val category: String,
    val reasonCode: String,
)

// Базовый level/confidence для текстовой категории, когда Atomizer был "atomic"
// и Planner не стал звать LLM. Откалибровано по аналогии с ShieldEngine
// (kz.invisibleshield.core.planner), чтобы звонковый и текстовый каналы не
// расходились по шкале на одинаково уверенных сигналах.
//
// unknown_caller_action сюда не входит — не текстовый сигнал (ADR-007),
// формируется в ShieldEngine, Aggregator текста его не видит.
val CATEGORY_SEVERITY: Map<String, Pair<Level, Double>> = mapOf(
    "none" to (Level.NONE to 0.0),
    "otp_shared_request" to (Level.WARN to 0.4),
    "remote_access_install" to (Level.WARN to 0.5),
    // bank_impersonation здесь — это УЖЕ подтверждённое сочетание "упомянут
    // банк + тревожная фраза рядом" (RegexAtomizer), не голое упоминание
    // бренда — поэтому уверенность выше, ближе к ShieldEngine.impersonationFlag.
    "bank_impersonation" to (Level.DANGER to 0.8),
    "urgency_pressure" to (Level.WARN to 0.5),
    "financial_pyramid_signal" to (Level.WARN to 0.4),
    "prize_lottery_scam" to (Level.WARN to 0.4),
    // phishing_link: regex подтвердил URL + тревожный контекст, но НЕ репутацию
    // домена (отдельный Executor, ADR-009) — сознательно консервативнее.
    "phishing_link" to (Level.WARN to 0.4),
    // romance_scam — самое слабое покрытие regex'ом по recall (ADR-007), но то,
    // что ловится — уже прямая комбинация "романтика + деньги" в одном сообщении.
    "romance_scam" to (Level.WARN to 0.35),
)

val ADVICE_BY_CATEGORY: Map<String, String> = mapOf(
    "none" to "",
    "otp_shared_request" to "Никому не называйте код из СМС. Банк и госорганы его не спрашивают.",
    "remote_access_install" to "Не устанавливайте программы удалённого доступа по чужой просьбе.",
    "bank_impersonation" to "Настоящий банк не звонит с требованием сообщить данные карты. Положите трубку и перезвоните сами по номеру с карты/банковского приложения.",
    "urgency_pressure" to "Не спешите. Настоящие организации не требуют решить всё «прямо сейчас».",
    "financial_pyramid_signal" to "Гарантированный доход без риска — так не бывает.",
    "prize_lottery_scam" to "Если приз требует сначала заплатить или сообщить данные карты — это обман.",
    "phishing_link" to "Не переходите по ссылке и не вводите данные карты.",
    "romance_scam" to "Незнакомец, который просит деньги, — это обман, даже если общение кажется тёплым.",
)

object Aggregator {

    private fun atomizerVerdict(atom: AtomResult): Verdict {
        val (level, conf) = CATEGORY_SEVERITY[atom.category] ?: (Level.NONE to 0.0)
        val reasons = if (atom.category != "none") atom.matchedRu + atom.matchedKz else emptyList()
        return Verdict(level, conf, reasons, ADVICE_BY_CATEGORY[atom.category] ?: "")
    }

    /**
     * Сводит Atomizer-кандидата и (опционально) LLM-вердикт в один Verdict по
     * политике ADR-005. llmVerdict, если передан — это уже распарсенный JSON
     * из GBNF-схемы (ADR-004).
     */
    fun reconcile(atom: AtomResult, llmVerdict: LlmVerdict? = null): Verdict {
        val atomVerdict = atomizerVerdict(atom)

        if (llmVerdict == null) return atomVerdict

        val llmLevel = Level.fromString(llmVerdict.level)
        val llmConf = llmVerdict.confidence.toDouble()

        val audit = listOf(
            "Atomizer: category=${atom.category} -> level=${atomVerdict.level.name} (conf=${"%.2f".format(atomVerdict.confidence)})",
            "LLM: category=${llmVerdict.category} -> level=${llmLevel.name} (conf=${"%.2f".format(llmConf)})",
        )

        return when {
            llmLevel.ordinal > atomVerdict.level.ordinal -> {
                val advice = ADVICE_BY_CATEGORY[llmVerdict.category] ?: atomVerdict.advice
                Verdict(llmLevel, llmConf, audit + "LLM повысил уровень — итог от LLM", advice)
            }
            atomVerdict.level.ordinal > llmLevel.ordinal -> {
                Verdict(
                    atomVerdict.level, atomVerdict.confidence,
                    audit + "LLM предложил ниже — regex-уровень НЕ понижается (ADR-005)",
                    atomVerdict.advice,
                )
            }
            else -> { // равенство level -> предпочитаем category/reason_code от LLM, confidence = max
                val finalConf = maxOf(atomVerdict.confidence, llmConf)
                val advice = ADVICE_BY_CATEGORY[llmVerdict.category] ?: atomVerdict.advice
                Verdict(llmLevel, finalConf, audit + "Уровни совпали — category/reason_code от LLM, confidence=max", advice)
            }
        }
    }
}
