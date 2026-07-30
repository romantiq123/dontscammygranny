package kz.invisibleshield.core.atomizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Порт демо-классификации номеров из call_detect.py __main__ (ADR-011). */
class NumberClassifierTest {

    @TestFactory
    fun `классификация номера`(): List<DynamicTest> {
        val cases = listOf(
            "+7 701 234 5678" to NumClass.KZ_MOBILE,
            "8 777 000 11 22" to NumClass.KZ_MOBILE,
            "+7 912 345 67 89" to NumClass.RU_MOBILE,
            "+7 495 123 45 67" to NumClass.RU_FIXED,
            "+234 802 000 1111" to NumClass.FOREIGN,
            "unknown" to NumClass.HIDDEN,
            "1414" to NumClass.SHORTCODE,
            "HalykBank" to NumClass.ALPHA_SENDER,
            "+7 727 250 00 00" to NumClass.KZ_FIXED,
        )
        return cases.map { (raw, expected) ->
            DynamicTest.dynamicTest("$raw -> ${expected.value}") {
                assertEquals(expected, classifyNumber(raw), "raw=$raw")
            }
        }
    }

    @Test
    fun `импперсонация - пустой отправитель НЕ флагается (нет данных о номере)`() {
        // Текстовый канал NLS не даёт номера отправителя (только заголовок-имя) —
        // отсутствие номера не должно превращаться в ложный DANGER на упоминании банка.
        assertNull(impersonationFlag("служба безопасности Halyk, ваша карта заблокирована", ""))
    }

    @Test
    fun `импперсонация - реальный не-официальный номер флагается`() {
        assertNotNull(impersonationFlag("это Halyk", "+7 747 000 1234"))
    }
}
