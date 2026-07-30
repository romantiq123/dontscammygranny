package kz.invisibleshield.core.planner

import kz.invisibleshield.core.model.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Порт 6 сценариев из call_detect.py __main__ (ADR-011: номер=контекст, действия=детект). */
class ShieldEngineTest {

    @Test
    fun `Wangiri - зарубежный звонок без действий - WARN`() {
        val eng = ShieldEngine(contacts = listOf("+7 701 111 2233"))
        val v = eng.onCallStarted("+234 802 000 1111")
        assertEquals(Level.WARN, v.level)
        assertEquals(0.35, v.confidence, 1e-9)
        eng.onCallEnded()
    }

    @Test
    fun `неизвестный KZ-номер без действий - тихий INFO, не сирена`() {
        val eng = ShieldEngine(contacts = listOf("+7 701 111 2233"))
        val v = eng.onCallStarted("+7 708 555 4433")
        assertEquals(Level.INFO, v.level)
        assertEquals(0.05, v.confidence, 1e-9)
    }

    @Test
    fun `OTP-СМС во время звонка с незнакомого - DANGER`() {
        val eng = ShieldEngine()
        eng.onCallStarted("+7 700 999 8877")
        val v = eng.onSms("Ваш код подтверждения 4821, никому не сообщайте")
        assertEquals(Level.DANGER, v.level)
        assertEquals(0.9, v.confidence, 1e-9)
        assertEquals(2, v.reasons.size)
    }

    @Test
    fun `установка remote-access во время звонка - DANGER`() {
        val eng = ShieldEngine()
        eng.onCallStarted("+7 705 111 2222")
        val v = eng.onAppInstalled("com.anydesk.anydeskandroid")
        assertEquals(Level.DANGER, v.level)
        assertEquals(0.92, v.confidence, 1e-9)
    }

    @Test
    fun `SMS от Halyk во время звонка с незнакомого, номер не официальный - DANGER`() {
        val eng = ShieldEngine()
        eng.onCallStarted("+7 747 000 1234")
        val v = eng.onSms(
            "Здравствуйте, служба безопасности Halyk, ваша карта заблокирована",
            "+7 747 000 1234",
        )
        assertEquals(Level.DANGER, v.level)
        assertEquals(0.8, v.confidence, 1e-9)
        assertTrue(v.reasons.any { it.contains("halyk") })
    }

    @Test
    fun `звонок от известного контакта - тишина`() {
        val eng = ShieldEngine(contacts = listOf("+7 701 111 2233"))
        val v = eng.onCallStarted("+7 701 111 2233")
        assertEquals(Level.NONE, v.level)
        assertEquals(0.0, v.confidence, 1e-9)
    }

    @Test
    fun `OTP внутри окна риска - DANGER`() {
        var t = 0L
        val eng = ShieldEngine(nowMs = { t })
        eng.onCallStarted("+7 700 999 8877")     // окно открылось в t=0
        t = ShieldEngine.RISKY_WINDOW_MS - 1       // ещё внутри окна
        val v = eng.onSms("Ваш код подтверждения 4821, никому не сообщайте")
        assertEquals(Level.DANGER, v.level)
        assertEquals(0.9, v.confidence, 1e-9)
    }

    @Test
    fun `OTP после истечения окна риска - НЕ DANGER, окно самозакрылось`() {
        var t = 0L
        val eng = ShieldEngine(nowMs = { t })
        eng.onCallStarted("+7 700 999 8877")     // окно открылось в t=0
        t = ShieldEngine.RISKY_WINDOW_MS + 1       // время ушло за окно
        val v = eng.onSms("Ваш код подтверждения 4821, никому не сообщайте")
        assertEquals(Level.WARN, v.level, "по истечении окна OTP сам по себе = WARN, не DANGER")
        assertEquals(0.4, v.confidence, 1e-9)
    }

    @Test
    fun `банк-приложение открыто во время звонка с незнакомого - DANGER с именем`() {
        val eng = ShieldEngine()
        eng.onCallStarted("+7 700 999 8877")
        val v = eng.onBankAppOpened("Kaspi.kz")
        assertEquals(Level.DANGER, v.level)
        assertEquals(0.85, v.confidence, 1e-9)
        assertTrue(v.reasons.any { it.contains("Kaspi.kz") }, "имя банка должно попасть в причины")
    }

    @Test
    fun `банк-приложение без активного звонка - тишина`() {
        val eng = ShieldEngine()
        val v = eng.onBankAppOpened("Kaspi.kz")
        assertEquals(Level.NONE, v.level)
    }

    @Test
    fun `isRiskyCallActive - true в окне, false после его истечения`() {
        var t = 0L
        val eng = ShieldEngine(nowMs = { t })
        eng.onCallStarted("+7 700 999 8877")
        assertTrue(eng.isRiskyCallActive(), "сразу после незнакомого звонка окно открыто")
        t = ShieldEngine.RISKY_WINDOW_MS + 1
        assertEquals(false, eng.isRiskyCallActive(), "после таймаута окно закрыто")
    }

    @Test
    fun `isRiskyCallActive - false для звонка из контактов`() {
        val eng = ShieldEngine(contacts = listOf("+7 701 111 2233"))
        eng.onCallStarted("+7 701 111 2233")
        assertEquals(false, eng.isRiskyCallActive(), "известный контакт окно не армит")
    }

    @Test
    fun `updateContacts - номер, ставший известным, больше не армит окно`() {
        val eng = ShieldEngine() // старт с пустым списком (контакты ещё не прочитаны)
        eng.onCallStarted("+7 701 111 2233")
        assertTrue(eng.isRiskyCallActive(), "пока список пуст — номер незнакомый, окно армлено")

        eng.updateContacts(listOf("+7 701 111 2233")) // подгрузили ContactsContract
        val v = eng.onCallStarted("+7 701 111 2233")
        assertEquals(Level.NONE, v.level, "теперь номер известен -> тишина")
        assertEquals(false, eng.isRiskyCallActive(), "известный контакт окно не армит")
    }
}
