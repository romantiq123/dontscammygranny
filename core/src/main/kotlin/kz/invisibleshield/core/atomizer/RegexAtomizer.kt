package kz.invisibleshield.core.atomizer

/**
 * Невидимый Щит — regex-слой RU/KZ (ADR-007), он же Atomizer в ROMA-пайплайне (ADR-005).
 * Порт regex_layer.py на Kotlin/JVM.
 *
 * ВАЖНО (проверено при портировании + на устройстве, см. ENGINEERING_NOTES §3.1): на JVM
 * `\w` по умолчанию матчит только ASCII (в отличие от Python re, где паттерн-строка уже
 * unicode-aware), а `(?i)` без Unicode-режима не делает регистронезависимой кириллицу —
 * без спец-мер ВСЕ паттерны ниже молча перестают матчиться на кириллице/казахском.
 * Решение — хелпер [ruKz] (детали в блоке ниже); НЕ префикс `(?iU)` и НЕ флаг
 * UNICODE_CHARACTER_CLASS — Android их отвергает.
 *
 * Категории совпадают с verdict_schema.json (ADR-004).
 */

// ВАЖНО (кросс-платформенно, проверено на Android 2026-07-21):
// 1) Инлайновый префикс "(?iU)" на desktop-JVM работает (тесты :core проходят),
//    но на Android java.util.regex падает.
// 2) Флаг UNICODE_CHARACTER_CLASS на Android вообще НЕ поддерживается
//    (IllegalArgumentException: "UNICODE_CHARACTER_CLASS flag not supported").
// Поэтому юникод-осведомлённость \w делаем ВРУЧНУЮ: заменяем \w на [\p{L}\p{N}_]
// (свойства \p{...} Android поддерживает), а из флагов оставляем
// CASE_INSENSITIVE + UNICODE_CASE (регистронезависимость кириллицы/казахского для
// литеральных букв в паттернах). \s/\d/\b оставляем ASCII — для наших паттернов
// (пробелы и цифры — ASCII) этого достаточно. Работает и на Android, и на JVM.
private val RU_KZ_FLAGS: Int =
    java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE

private fun ruKz(pattern: String): Regex {
    val unicodeWord = pattern.replace("""\w""", """[\p{L}\p{N}_]""")
    return java.util.regex.Pattern.compile(unicodeWord, RU_KZ_FLAGS).toRegex()
}

data class AtomResult(
    val category: String,
    val reasonCode: String,
    val matchedRu: List<String> = emptyList(),
    val matchedKz: List<String> = emptyList(),
    val confident: Boolean = true,
)

private data class Rule(
    val category: String,
    val reasonCode: String,
    val ru: List<Regex>,
    val kz: List<Regex>,
    // Правило учитывается только если в тексте есть URL (для phishing_link).
    val requiresUrl: Boolean = false,
    // Правило учитывается только если этот контекст тоже присутствует в тексте
    // (для слабых, неоднозначных паттернов — напр. «глагол+код» требует OTP-контекст).
    val contextAny: Regex? = null,
)

// ---------------------------------------------------------------- OTP / код
// Два тира (ADR-007 precision/recall). СИЛЬНЫЕ — однозначно про «код из СМС»,
// срабатывают сами по себе.
private val RU_OTP_STRONG = listOf(
    ruKz("""код\w*\s+(подтвержд\w*|из\s+смс|безопасности|верификаци\w*|одноразов\w*)"""),
    ruKz("""смс[- ]?код"""),
    ruKz("""никому\s+не\s+(сообщ\w*|говор\w*|передав\w*)"""),
    ruKz("""продикт\w*\s+.{0,10}код"""),   // «продиктовать» почти всегда про код
    ruKz("""назов\w*\s+.{0,10}код"""),      // «назовите код»
)
private val KZ_OTP_STRONG = listOf(
    ruKz("""код\w*\s+(растау|қауіпсіздік)"""),
    ruKz("""ешкімге\s+айтпа\w*"""),
)
// СЛАБЫЕ — «глагол + код» неоднозначен (в обычной/дев-переписке «дай код»,
// «код сообщения», «кодты айт»). Срабатывают ТОЛЬКО при OTP/скам-контексте рядом
// ([OTP_CONTEXT], см. Rule.contextAny). Так «скажи код» сам по себе — НЕ тревога,
// а «код скажи, доставка каспи» — да (найдено на реальном тесте 2026-07-22).
private val RU_OTP_WEAK = listOf(
    ruKz("""(скаж\w*|сообщ\w*|переда\w*|дай\w*|отправ\w*|напиш\w*)\s+(мне\s+)?(смс[- ]?)?код\w*"""),
    ruKz("""код\w*\s+(скаж\w*|сообщ\w*|переда\w*|дай\w*|отправ\w*|напиш\w*)"""),
)
private val KZ_OTP_WEAK = listOf(
    ruKz("""код\w*\s+айт\w*"""),      // «кодты айтыңыз» — скажите код
    ruKz("""код\w*\s+жібер\w*"""),    // «код жіберіңіз» — отправьте код
)
// Скам-контекст, при котором слабый «глагол+код» становится тревогой. Только
// сильные маркеры (не «пришёл/сообщение» — они частят в обычной переписке).
private val OTP_CONTEXT = ruKz(
    """(смс|sms|otp|верификаци\w*|подтвержд\w*|растау|одноразов\w*|банк\w*|каспи|kaspi|""" +
        """halyk|халы\w*|jusan|bereke|forte|доставк\w*|жеткіз\w*|посылк\w*|заблокир\w*|""" +
        """бұғатта\w*|срочн\w*|жедел|счёт|счет|шот|карт[аыуеі]|whatsapp|telegram|госуслуг\w*|egov)"""
)

// --------------------------------------------------- remote access install
private val RU_REMOTE = listOf(
    ruKz("""(anydesk|teamviewer|rustdesk|airdroid)"""),
    ruKz("""установ\w*\s+(приложен\w*|программ\w*)\s+для\s+(удал[её]нн\w*|помощ\w*)"""),
    ruKz("""дай\w*\s+доступ\s+к\s+экран\w*"""),
)
private val KZ_REMOTE = listOf(
    ruKz("""(anydesk|teamviewer|rustdesk|airdroid)"""),
    ruKz("""қашықтан\s+бас\wар\w*\s+бағдарлам\w*"""),
    ruKz("""экранға\s+қатынас"""),
)

// --------------------------------------------------------- urgency / нажим
private val RU_URGENCY = listOf(
    ruKz("""не\s+клад\w*\s+трубк\w*"""),
    ruKz("""срочно\s+(перевед\w*|переведит\w*|оплат\w*)"""),
    ruKz("""сейчас\s+же"""),
    ruKz("""иначе\s+(заблокир\w*|потеря\w*|спишет\w*)"""),
)
private val KZ_URGENCY = listOf(
    ruKz("""тел\w*фонды\s+тастама\w*"""),
    ruKz("""жедел\s+аудар\w*"""),
    ruKz("""қазір\s+(аудар\w*|төле\w*)"""),
)

// --------------------------------------------------- денежный перевод
private val RU_TRANSFER = listOf(
    ruKz("""перевед\w*\s+(деньги|средства|сумму)"""),
    ruKz("""безопасн\w*\s+сч[её]т"""),
    ruKz("""переведит\w*\s+на\s+карт\w*"""),
)
private val KZ_TRANSFER = listOf(
    ruKz("""ақша\w*\s+аудар\w*"""),
    ruKz("""қауіпсіз\s+шот"""),
)

// ----------------------------------------------------------- приз/лотерея
private val RU_PRIZE = listOf(
    ruKz("""вы\s+выиграл\w*"""),
    ruKz("""поздравля\w*.{0,20}(приз|розыгрыш|выигрыш)"""),
    ruKz("""чтобы\s+получ\w*\s+приз"""),
)
private val KZ_PRIZE = listOf(
    ruKz("""сіз\s+ұтып\s+ал\w*"""),
    ruKz("""жүлде\w*\s+ал\w*\s+үшін"""),
)

// ---------------------------------------------------- финансовая пирамида
private val RU_PYRAMID = listOf(
    ruKz("""пассивн\w*\s+доход\w*\s+(без\s+риск\w*|гарантир\w*)"""),
    ruKz("""удво\w*\s+(деньги|вложени\w*|капитал\w*)"""),
    ruKz("""инвестиру\w*\s+сегодня.{0,20}(завтра|через\s+недел\w*)"""),
)
private val KZ_PYRAMID = listOf(
    ruKz("""тәуекелсіз\s+табыс"""),
    ruKz("""ақшаңызды\s+екі\s+есе"""),
)

// ------------------------------------------------------- bank impersonation
private const val BANK_NAMES =
    """(halyk|халык|халық|народны\w*|kaspi|каспи|kaspi\.kz|bereke|береке|jusan|жусан|forte|форте|eurasian|евразийск\w*)"""
private val RU_BANK_IMPERSONATION = listOf(
    ruKz(BANK_NAMES + """.{0,30}(служба\s+безопасности|заблокир\w*|подозрительн\w*\s+(операци\w*|транзакци\w*))"""),
    ruKz("""(служба\s+безопасности|представитель)\s+банка"""),
    ruKz("""ваша\s+карт\w*\s+заблокирован\w*"""),
)
private val KZ_BANK_IMPERSONATION = listOf(
    ruKz(BANK_NAMES + """.{0,30}(қауіпсіздік\s+қызметі|бұғаттал\w*|күдікті\s+транзакция)"""),
    ruKz("""қауіпсіздік\s+қызметі"""),
    ruKz("""картаңыз\s+бұғаттал\w*"""),
)

// --------------------------------------------------------------- phishing link
private val URL_PATTERN = ruKz(
    """https?://\S+|(?:[a-zA-Zа-яА-Я0-9\-]+\.(?:kz|ru|com|net|org|info|xyz|top|site))\b"""
)
private val RU_PHISHING_CONTEXT = listOf(
    ruKz("""перейд\w*\s+по\s+ссылк\w*"""),
    ruKz("""подтверд\w*\s+данны\w*\s+по\s+ссылк\w*"""),
    ruKz("""обновит\w*\s+данны\w*\s+карт\w*"""),
)
private val KZ_PHISHING_CONTEXT = listOf(
    ruKz("""сілтеме\s+арқылы\s+өтіңіз"""),
    ruKz("""деректерді\s+растаңыз"""),
)

// --------------------------------------------------------------- romance scam
private val RU_ROMANCE = listOf(
    ruKz("""(полюбил\w*|влюби\w*)\s+тебя.{0,60}(деньги|перевед\w*|билет|подарок)"""),
    ruKz("""застрял\w*\s+(на\s+границе|в\s+аэропорту|за\s+рубежом).{0,40}(деньги|перевед\w*)"""),
)
private val KZ_ROMANCE = listOf(
    ruKz("""сені\s+сүй\w*.{0,60}(ақша|аудар\w*|билет|сыйлық)"""),
    ruKz("""шекарада\s+қалып\s+қойд\w*.{0,40}(ақша|аудар\w*)"""),
)

private val RULES: List<Rule> = listOf(
    Rule("otp_shared_request", "asks_otp_code", RU_OTP_STRONG, KZ_OTP_STRONG),
    Rule("otp_shared_request", "asks_otp_code", RU_OTP_WEAK, KZ_OTP_WEAK, contextAny = OTP_CONTEXT),
    Rule("remote_access_install", "asks_remote_access_app", RU_REMOTE, KZ_REMOTE),
    Rule("urgency_pressure", "urgency_do_not_hang_up", RU_URGENCY, KZ_URGENCY),
    Rule("urgency_pressure", "requests_money_transfer", RU_TRANSFER, KZ_TRANSFER),
    Rule("prize_lottery_scam", "too_good_to_be_true", RU_PRIZE, KZ_PRIZE),
    Rule("financial_pyramid_signal", "too_good_to_be_true", RU_PYRAMID, KZ_PYRAMID),
    Rule("bank_impersonation", "claims_bank_wrong_number", RU_BANK_IMPERSONATION, KZ_BANK_IMPERSONATION),
    Rule("phishing_link", "suspicious_link_domain", RU_PHISHING_CONTEXT, KZ_PHISHING_CONTEXT, requiresUrl = true),
    Rule("romance_scam", "requests_money_transfer", RU_ROMANCE, KZ_ROMANCE),
)

object RegexAtomizer {

    fun hasUrl(text: String): Boolean = URL_PATTERN.containsMatchIn(text)

    /**
     * Atomizer (ADR-005): пробегает все категории, возвращает совпадение с наибольшим
     * числом сматченных паттернов. Если ничего не найдено — category="none",
     * confident=true (это тоже atomic-результат: уверенно ничего подозрительного).
     *
     * Корроборация (ADR-007): часть правил требует доп. сигнала, иначе не считается.
     *  - requiresUrl (phishing_link): тревожная фраза без реального URL — обрывок,
     *    не скам.
     *  - contextAny (слабый OTP «глагол+код»): «дай/скажи код» без OTP-контекста
     *    (смс/банк/доставка/срочно…) — это обычная переписка про «код», не тревога.
     */
    fun atomize(text: String): AtomResult {
        val urlPresent = hasUrl(text)
        var best: Pair<Int, AtomResult>? = null

        for (rule in RULES) {
            if (rule.requiresUrl && !urlPresent) continue
            if (rule.contextAny != null && !rule.contextAny.containsMatchIn(text)) continue
            val ruHits = rule.ru.filter { it.containsMatchIn(text) }.map { it.pattern }
            val kzHits = rule.kz.filter { it.containsMatchIn(text) }.map { it.pattern }
            if (ruHits.isNotEmpty() || kzHits.isNotEmpty()) {
                val score = ruHits.size + kzHits.size
                if (best == null || score > best.first) {
                    best = score to AtomResult(rule.category, rule.reasonCode, ruHits, kzHits, confident = true)
                }
            }
        }
        return best?.second ?: AtomResult("none", "no_signal", confident = true)
    }
}
