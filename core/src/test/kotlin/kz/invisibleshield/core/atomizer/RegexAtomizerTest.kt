package kz.invisibleshield.core.atomizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Порт тестов из regex_layer.py __main__ (ADR-007 "Покрытие категорий") — проверяет, что
 * портирование на Kotlin/JVM не сломало поведение относительно эталона на Python.
 *
 * Сейчас здесь 29 кейсов (19 позитивных + 10 контролей ложных срабатываний) — больше, чем
 * 18 в Python-прототипе: добавлены двухтировый OTP и контроли на «код» в обычной переписке.
 * Правишь regex — синхронизируй прототип, он остаётся эталоном.
 *
 * ВНИМАНИЕ по Unicode: инлайновый префикс `(?iU)` здесь НЕ используется — на Android
 * java.util.regex его отвергает, как и флаг UNICODE_CHARACTER_CLASS. Юникод-осведомлённость
 * делается вручную в хелпере `ruKz()` внутри RegexAtomizer (см. ENGINEERING_NOTES §3.1).
 */
class RegexAtomizerTest {

    @TestFactory
    fun `должны сработать`(): List<DynamicTest> {
        val cases = listOf(
            Triple("Ваш код подтверждения 4821, никому не сообщайте", "otp_shared_request", "RU OTP явный"),
            Triple("код скажи доставка каспи", "otp_shared_request", "RU OTP 'код скажи' (обратный порядок)"),
            Triple("скажите код из сообщения срочно", "otp_shared_request", "RU OTP 'скажите код'"),
            Triple("Продиктуйте смс-код который пришёл", "otp_shared_request", "RU OTP 'продиктуйте смс-код'"),
            Triple("Растау коды: 8213, ешкімге айтпаңыз", "otp_shared_request", "KZ OTP явный"),
            Triple("Установите AnyDesk чтобы мы помогли с доступом", "remote_access_install", "RU remote явный"),
            Triple("Қашықтан басқару бағдарламасын орнатыңыз", "remote_access_install", "KZ remote явный"),
            Triple("Не кладите трубку, переведите деньги на безопасный счёт", "urgency_pressure", "RU urgency"),
            Triple("Телефонды тастамаңыз, ақшаны қауіпсіз шотқа аударыңыз", "urgency_pressure", "KZ urgency"),
            Triple("Поздравляем, вы выиграли миллион тенге!", "prize_lottery_scam", "RU приз"),
            Triple("Сіз ұтып алдыңыз! Жүлдені алу үшін хабарласыңыз", "prize_lottery_scam", "KZ приз"),
            Triple("Пассивный доход без риска, удвойте вложения за неделю", "financial_pyramid_signal", "RU пирамида"),
            Triple("Ақшаңызды екі есе көбейтіңіз, тәуекелсіз табыс", "financial_pyramid_signal", "KZ пирамида"),
            Triple("Здравствуйте, это служба безопасности Halyk, ваша карта заблокирована", "bank_impersonation", "RU банк-имперсонация"),
            Triple("Сәлеметсіз бе, Kaspi қауіпсіздік қызметі, картаңыз бұғатталды", "bank_impersonation", "KZ банк-имперсонация"),
            Triple("Перейдите по ссылке http://kaspi-secure.xyz и подтвердите данные карты", "phishing_link", "RU фишинг-ссылка"),
            Triple("Сілтеме арқылы өтіңіз: kaspi-bonus.top деректерді растаңыз", "phishing_link", "KZ фишинг-ссылка"),
            Triple("Я так полюбил тебя, но застрял на границе, переведи денег на билет", "romance_scam", "RU романтический скам"),
            Triple("Сені сүйіп қалдым, шекарада қалып қойдым, ақша аударшы", "romance_scam", "KZ романтический скам"),
        )
        return cases.map { (text, expected, note) ->
            DynamicTest.dynamicTest("$note: «$text»") {
                val result = RegexAtomizer.atomize(text)
                assertEquals(expected, result.category, "text=«$text»")
            }
        }
    }

    @TestFactory
    fun `НЕ должны сработать (контроль ложных срабатываний)`(): List<DynamicTest> {
        val cases = listOf(
            "Привет, как дела? Встретимся вечером?" to "обычная переписка",
            "Ваш заказ из Kaspi доставлен, спасибо за покупку" to "легитимное уведомление",
            "Сабақ кестесі өзгерді, ертең 9-да" to "KZ обычное сообщение (расписание)",
            "Не забудь перевести презентацию на английский" to "RU 'перевод' = перевод текста, не денег",
            "Служба безопасности предприятия проводит инструктаж в пятницу" to "RU 'служба безопасности' без банка",
            "Посмотри какой сайт классный: example.kz, там рецепты" to "RU URL без тревожного контекста",
            "Я тебя люблю, увидимся вечером после работы" to "RU романтика без денег",
            // «код» + глагол БЕЗ скам-контекста — обычная/дев-переписка, не тревога (2026-07-22).
            "скажи код" to "RU 'скажи код' без контекста",
            "дай код доступа к серверу" to "RU дев: 'дай код доступа'",
            "код сообщения не дошёл" to "RU 'код сообщения' без контекста",
        )
        return cases.map { (text, note) ->
            DynamicTest.dynamicTest("$note: «$text»") {
                val result = RegexAtomizer.atomize(text)
                assertEquals("none", result.category, "ложное срабатывание на «$text»: got=${result.category}")
            }
        }
    }
}
