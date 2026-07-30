package kz.invisibleshield.core.atomizer

/**
 * Невидимый Щит — классификация звонкового/SMS-номера (ADR-011), часть Atomizer (ADR-005).
 * Порт номерной части call_detect.py на Kotlin/JVM.
 *
 * Источник KZ-префиксов: план нумерации РК (Altel 700/708, Kcell 701/702/775/778,
 * Beeline 705/771/776/777, izi 706, Tele2 707/747; 703/704/709 — резерв сотовых;
 * 75x/76x — Казахтелеком фикс/данные). RU-мобилы: +7 9xx.
 */

// См. RegexAtomizer.kt: Android не поддерживает UNICODE_CHARACTER_CLASS, а инлайновый
// "(?iU)" на нём падает. Поэтому \w заменяем на [\p{L}\p{N}_] вручную, флаги —
// CASE_INSENSITIVE + UNICODE_CASE. Работает и на Android, и на desktop-JVM.
private val RU_KZ_FLAGS: Int =
    java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE

private fun ruKz(pattern: String): Regex {
    val unicodeWord = pattern.replace("""\w""", """[\p{L}\p{N}_]""")
    return java.util.regex.Pattern.compile(unicodeWord, RU_KZ_FLAGS).toRegex()
}

val KZ_MOBILE_PREFIXES: Set<String> = setOf(
    "700", "701", "702", "703", "704", "705", "706", "707", "708", "709",
    "747", "771", "775", "776", "777", "778",
)
val KZ_KTC_PREFIXES: Set<String> = setOf("750", "751", "760", "761", "762", "763", "764")

val REMOTE_ACCESS_PKGS: Map<String, String> = mapOf(
    "com.anydesk.anydeskandroid" to "AnyDesk",
    "com.teamviewer.quicksupport.market" to "TeamViewer QuickSupport",
    "com.teamviewer.host.market" to "TeamViewer Host",
    "com.rustdesk.rustdesk" to "RustDesk",
    "com.sand.airdroid" to "AirDroid",
    "com.aweray.remote" to "AweSun",
    "com.microsoft.rdc.androidx" to "MS Remote Desktop",
)

/**
 * Пакеты банковских приложений РК — для детекта «банк-апп открыт во время звонка»
 * (ADR-011, ShieldEngine.onBankAppOpened, опрос UsageStats в app/BankAppMonitor).
 *
 * ВНИМАНИЕ: список НУЖНО проверить на реальном устройстве — неверный пакет = не
 * ложняк, а ПРОПУСК детекта. BankAppMonitor логирует реальные пакеты переднего
 * плана во время звонка (FileLog "передний план: <pkg>") — по ним и уточнять/дополнять.
 * Собрать имена можно и так: `adb shell pm list packages | grep -iE 'bank|kaspi|halyk'`.
 */
val BANK_APP_PKGS: Map<String, String> = mapOf(
    "kz.kaspi.mobile" to "Kaspi.kz",
    "kz.halykbank.homebank" to "Halyk / Homebank",
    "kz.jysan.bank" to "Jusan",
    "kz.bcc.starbanking" to "BCC StarBanking",
    "kz.forte.tez" to "ForteBank",
    "kz.bereke.bank" to "Bereke Bank",
    "kz.eubank.mobile" to "Eurasian (Eubank)",
    "kz.ffin.superapp" to "Freedom SuperApp",
)

enum class NumClass(val value: String) {
    KZ_MOBILE("kz_mobile"),
    KZ_FIXED("kz_fixed"),           // Казахтелеком / гео KZ
    RU_MOBILE("ru_mobile"),
    RU_FIXED("ru_fixed"),
    FOREIGN("foreign"),
    HIDDEN("hidden"),               // скрытый/withheld
    SHORTCODE("shortcode"),         // короткий сервисный номер
    ALPHA_SENDER("alpha_sender"),   // буквенный отправитель (в SMS)
    MALFORMED("malformed"),
}

// базовый вес класса как СЛАБЫЙ сигнал (0..1). Не приговор.
val CLASS_BASE_RISK: Map<NumClass, Double> = mapOf(
    NumClass.KZ_MOBILE to 0.05,
    NumClass.KZ_FIXED to 0.05,
    NumClass.RU_MOBILE to 0.20,
    NumClass.RU_FIXED to 0.20,
    NumClass.FOREIGN to 0.35,
    NumClass.HIDDEN to 0.45,
    NumClass.SHORTCODE to 0.10,
    NumClass.ALPHA_SENDER to 0.15,
    NumClass.MALFORMED to 0.30,
)

private val LETTERS_RE = ruKz("""[a-zA-Zа-яА-Я]""")
private val NON_DIGITS_RE = Regex("""\D""")
private val HIDDEN_WORDS = setOf("unknown", "private", "withheld", "restricted", "anonymous")

/** Грубая нормализация к нац. виду: '8XXXXXXXXXX'/'+7...' -> '7XXXXXXXXXX'. */
fun normalize(raw: String?): String {
    if (raw == null) return ""
    val s = raw.trim()
    if (s.isEmpty() || s.lowercase() in HIDDEN_WORDS) return "HIDDEN"
    if (LETTERS_RE.containsMatchIn(s)) {
        // буквенный sender ID (напр. "HalykBank")
        return "ALPHA:$s"
    }
    var digits = NON_DIGITS_RE.replace(s, "")
    if (digits.isEmpty()) return "HIDDEN"
    if (digits.startsWith("8") && digits.length == 11) {
        digits = "7" + digits.substring(1)
    }
    if (s.startsWith("+7") || (digits.startsWith("7") && digits.length == 11)) {
        return digits
    }
    return digits // прочее — оставляем как есть, класс решит
}

fun classifyNumber(raw: String): NumClass {
    val n = normalize(raw)
    if (n == "HIDDEN") return NumClass.HIDDEN
    if (n.startsWith("ALPHA:")) return NumClass.ALPHA_SENDER
    if (n.length in 3..6) return NumClass.SHORTCODE
    if (n.length == 11 && n.startsWith("7")) {
        val nsn = n.substring(1) // национальный значимый номер, 10 цифр
        val area = nsn.substring(0, 3)
        val lead = nsn[0]
        return when {
            area in KZ_MOBILE_PREFIXES -> NumClass.KZ_MOBILE
            area in KZ_KTC_PREFIXES -> NumClass.KZ_FIXED
            lead == '7' -> NumClass.KZ_FIXED   // прочие 7xx в зоне +7 -> гео Казахстан
            lead == '9' -> NumClass.RU_MOBILE  // +7 9xx -> мобильный РФ
            lead in setOf('3', '4', '8') -> NumClass.RU_FIXED
            else -> NumClass.MALFORMED
        }
    }
    if (n.length in 7..15) { // похоже на международный E.164, но не +7
        return if (n.startsWith("7")) NumClass.MALFORMED else NumClass.FOREIGN
    }
    return NumClass.MALFORMED
}

// ------------------------------------------------------ импперсонация банка

// мини-allowlist официальных номеров (сорсится у самих банков; тут пример).
val BANK_OFFICIAL: Map<String, Set<String>> = mapOf(
    "halyk" to setOf("77777", "75007777777"), // напр. короткий 7777 + горячая линия
    "kaspi" to setOf("77777"),
)
// альтернативные написания брендов в тексте
val BANK_ALIASES: Map<String, List<String>> = mapOf(
    "halyk" to listOf("halyk", "халык", "народный банк", "халық"),
    "kaspi" to listOf("kaspi", "каспи", "каспий"),
)

fun claimedBank(text: String?): String? {
    val t = (text ?: "").lowercase()
    return BANK_ALIASES.entries.firstOrNull { (_, aliases) -> aliases.any { it in t } }?.key
}

/** Если сообщение/звонок 'от банка X', но номер не из официальных X -> флаг. */
fun impersonationFlag(text: String?, rawNumber: String): String? {
    val bank = claimedBank(text) ?: return null
    // Пустой отправитель = НЕТ информации о номере (напр. текстовый канал NLS, где
    // доступен только заголовок-имя, а не адрес отправителя), а НЕ «скрытый номер».
    // Без реального номера факт импперсонации-по-номеру утверждать нельзя — иначе
    // любое упоминание банка в уведомлении без номера = ложный DANGER. Текстовый
    // сигнал bank_impersonation отдельно и корректно ловит RegexAtomizer (ADR-007).
    if (rawNumber.isBlank()) return null
    val n = normalize(rawNumber)
    if (n == "HIDDEN" || n.startsWith("ALPHA:")) {
        return "'Представляется как $bank', но номер скрыт/буквенный — банки так не звонят"
    }
    if (n !in (BANK_OFFICIAL[bank] ?: emptySet())) {
        return "'Представляется как $bank', но номер $n НЕ из официальных номеров $bank"
    }
    return null
}

// --------------------------------------------------------- OTP в сообщении

private val OTP_RE = ruKz(
    """(код|kod|code|растау|пароль)\D{0,20}\b(\d{4,8})\b""" +
        """|\b(\d{4,8})\b\D{0,20}(никому|ешкімге|не сообщай|не говори)"""
)

fun smsHasOtp(text: String?): Boolean = OTP_RE.containsMatchIn(text ?: "")
