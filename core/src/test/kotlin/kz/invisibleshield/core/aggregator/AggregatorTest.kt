package kz.invisibleshield.core.aggregator

import kz.invisibleshield.core.atomizer.RegexAtomizer
import kz.invisibleshield.core.model.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Порт 5 сценариев из aggregator.py __main__ — политика согласования ADR-005. */
class AggregatorTest {

    @Test
    fun `Atomizer один - LLM не вызывался - WARN от таблицы severity`() {
        val atom = RegexAtomizer.atomize("Ваш код подтверждения 4821, никому не сообщайте")
        val v = Aggregator.reconcile(atom, llmVerdict = null)
        assertEquals(Level.WARN, v.level)
        assertEquals(0.4, v.confidence, 1e-9)
    }

    @Test
    fun `обычная переписка - LLM не вызывался - NONE`() {
        val atom = RegexAtomizer.atomize("Привет, как дела?")
        val v = Aggregator.reconcile(atom, llmVerdict = null)
        assertEquals(Level.NONE, v.level)
    }

    @Test
    fun `LLM повышает уровень относительно regex`() {
        val atom = RegexAtomizer.atomize("Ваш код подтверждения 4821, никому не сообщайте") // regex -> WARN 0.4
        val llm = LlmVerdict("danger", "0.9", "otp_shared_request", "asks_otp_code")
        val v = Aggregator.reconcile(atom, llm)
        assertEquals(Level.DANGER, v.level)
        assertEquals(0.9, v.confidence, 1e-9)
    }

    @Test
    fun `LLM пытается понизить regex-DANGER - regex не уступает`() {
        val atom = RegexAtomizer.atomize("Здравствуйте, служба безопасности Halyk, ваша карта заблокирована") // regex -> DANGER 0.8
        val llm = LlmVerdict("warn", "0.5", "urgency_pressure", "urgency_act_now")
        val v = Aggregator.reconcile(atom, llm)
        assertEquals(Level.DANGER, v.level, "regex-DANGER не должен понижаться LLM")
        assertEquals(0.8, v.confidence, 1e-9)
    }

    @Test
    fun `Atomizer none, но LLM всё же вызвали и нашли сигнал - WARN от LLM`() {
        val atom = RegexAtomizer.atomize("Это Айгуль, звоню по поводу заказа")
        val llm = LlmVerdict("warn", "0.6", "urgency_pressure", "requests_money_transfer")
        val v = Aggregator.reconcile(atom, llm)
        assertEquals(Level.WARN, v.level)
        assertEquals(0.6, v.confidence, 1e-9)
    }

    @Test
    fun `уровни совпадают - category от LLM, confidence = max`() {
        val atom = RegexAtomizer.atomize("Поздравляем, вы выиграли миллион тенге!") // regex -> WARN 0.4
        val llm = LlmVerdict("warn", "0.3", "prize_lottery_scam", "too_good_to_be_true")
        val v = Aggregator.reconcile(atom, llm)
        assertEquals(Level.WARN, v.level)
        assertEquals(0.4, v.confidence, 1e-9)
    }
}
