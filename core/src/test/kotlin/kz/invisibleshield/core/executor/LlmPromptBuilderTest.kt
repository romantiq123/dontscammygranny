package kz.invisibleshield.core.executor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmPromptBuilderTest {

    @Test
    fun `включает текст и категорию от Atomizer`() {
        val prompt = LlmPromptBuilder.build("Ваш код 4821, никому не говорите", "otp_shared_request")
        assertTrue(prompt.contains("otp_shared_request"))
        assertTrue(prompt.contains("Ваш код 4821, никому не говорите"))
        assertFalse(prompt.contains("Контекст звонка"), "без callContext секция звонка не должна появляться")
    }

    @Test
    fun `добавляет контекст звонка, если передан`() {
        val prompt = LlmPromptBuilder.build("текст", "none", callContext = "активный рискованный звонок")
        assertTrue(prompt.contains("Контекст звонка: активный рискованный звонок"))
    }

    @Test
    fun `тройные кавычки в тексте не ломают разделитель`() {
        val textWithTripleQuote = "он написал \"\"\"результат\"\"\""
        val prompt = LlmPromptBuilder.build(textWithTripleQuote, "none")
        // ровно два вхождения тройной кавычки-разделителя (открывающая/закрывающая) —
        // те, что были внутри текста, экранированы в одинарные ('').
        val occurrences = Regex("\"\"\"").findAll(prompt).count()
        assertTrue(occurrences == 2, "ожидались только разделители, было $occurrences вхождений")
    }
}
