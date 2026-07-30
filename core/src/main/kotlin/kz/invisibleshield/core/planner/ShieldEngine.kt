package kz.invisibleshield.core.planner

import kz.invisibleshield.core.atomizer.CLASS_BASE_RISK
import kz.invisibleshield.core.atomizer.NumClass
import kz.invisibleshield.core.atomizer.REMOTE_ACCESS_PKGS
import kz.invisibleshield.core.atomizer.classifyNumber
import kz.invisibleshield.core.atomizer.impersonationFlag
import kz.invisibleshield.core.atomizer.normalize
import kz.invisibleshield.core.atomizer.smsHasOtp
import kz.invisibleshield.core.model.Level
import kz.invisibleshield.core.model.Verdict

/**
 * Невидимый Щит — Planner звонкового детекта (ADR-005/ADR-011). Порт ShieldEngine
 * из call_detect.py на Kotlin/JVM.
 *
 * Модель: содержание звонка мы НЕ читаем (аудио звонка закрыто с Android 10,
 * запись запрещена Play). Поэтому звонок детектим по двум осям:
 *   1) НОМЕР — слабый контекст-сигнал (класс + импперсонация банка).
 *   2) ДЕЙСТВИЯ во время звонка — основной детект (OTP, remote-access, банк-апп, ссылка).
 * Деньги уходят через действия, не через слова -> слой действий и есть защита.
 *
 * Потокобезопасность: экземпляр — синглтон (ShieldPlanner.engine) и трогается из
 * НЕСКОЛЬКИХ потоков: onSms — с ShieldPlanner.dispatcher, onCallStarted — с Binder-
 * потока CallScreeningService, onAppInstalled — с main-потока BroadcastReceiver.
 * Поэтому все публичные методы, мутирующие/читающие [call], помечены @Synchronized —
 * это единственная гарантия отсутствия гонки за CallState (dispatcher сериализует
 * только LLM-доступ к llama_context, но НЕ вызовы из звонкового/пакетного каналов).
 */
data class CallState(
    var number: String? = null,
    var numClass: NumClass? = null,
    var knownContact: Boolean = false,
    var active: Boolean = false,
    var risky: Boolean = false, // неизвестный/флагнутый номер => окно повышенной чувствительности
    var startedAtMs: Long = 0L, // момент старта окна (для самозакрытия по таймауту, см. duringRiskyCall)
)

class ShieldEngine(
    contacts: List<String> = emptyList(),
    // Источник времени — вынесен для тестируемости окна (тесты используют реальные
    // часы, где прошедшее время ~0, поэтому окно всегда открыто в момент проверки).
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    // var, а не val: реальные контакты (ContactsContract) читаются асинхронно уже
    // ПОСЛЕ создания движка — когда пользователь выдаст READ_CONTACTS. До этого
    // список пуст (каждый звонок = незнакомый). updateContacts() заменяет набор.
    private var contacts: Set<String> = contacts.map { normalize(it) }.toSet()

    var call: CallState = CallState()
        private set

    /** Заменяет список известных номеров (из ContactsContract). Потокобезопасно. */
    @Synchronized
    fun updateContacts(numbers: List<String>) {
        contacts = numbers.map { normalize(it) }.filter { it.isNotBlank() && it != "HIDDEN" }.toSet()
    }

    /**
     * verificationStatus: "passed" | "failed" | "not_verified" — из
     * Call.Details.getCallerNumberVerificationStatus() (STIR/SHAKEN-уровня,
     * бесплатный сигнал из платформы). ВАЖНО: claim_text здесь принципиально
     * недоступен — onScreenCall() вызывается ДО того как звонок принят,
     * никто ничего ещё не сказал. Проверка bank_impersonation по факту
     * произойдёт позже через onSms(), если во время этого звонка придёт
     * SMS с упоминанием банка (см. ADR-011 "архитектурная поправка").
     */
    @Synchronized
    fun onCallStarted(rawNumber: String, verificationStatus: String = "not_verified"): Verdict {
        val cls = classifyNumber(rawNumber)
        val known = normalize(rawNumber) in contacts
        // ВАЖНО: окно армится на ЛЮБОЙ звонок не из контактов — неизвестный
        // локальный +7 это главная маскировка импперсонатора. Класс номера
        // влияет только на базовый уровень тревоги самого звонка (ниже).
        call = CallState(
            number = normalize(rawNumber), numClass = cls,
            knownContact = known, active = true,
            risky = !known,
            startedAtMs = nowMs(),
        )
        if (known) {
            return Verdict(Level.NONE, 0.0, listOf("Номер в контактах"), "")
        }

        val base = CLASS_BASE_RISK.getValue(cls)
        val reasons = mutableListOf<String>()
        val level: Level
        val conf: Double
        if (verificationStatus == "failed") {
            reasons.add("Сетевая верификация номера НЕ пройдена (возможен спуфинг)")
            level = Level.WARN
            conf = maxOf(base, 0.5)
        } else if (base >= 0.30) { // foreign / hidden / malformed — заметный сигнал
            reasons.add("Неизвестный номер, класс: ${cls.value}")
            level = Level.WARN
            conf = base
        } else { // kz / ru-моб / shortcode — тихий флажок, НЕ сирена
            reasons.add("Неизвестный номер (класс ${cls.value}) — слабый сигнал")
            level = Level.INFO
            conf = base
        }

        val advice = "Идёт звонок с незнакомого номера. Настоящий банк/госорган " +
            "никогда не просит код из СМС и не требует переводить деньги " +
            "«на безопасный счёт»."
        return Verdict(level, conf, reasons, advice)
    }

    @Synchronized
    fun onCallEnded() {
        call.active = false
        call.risky = false
    }

    // --- действия во время звонка (основной слой) ---
    // Окно «рискованного звонка» самозакрывается по таймауту (RISKY_WINDOW_MS).
    // Таймаут — СТРАХОВКА: сам CallScreeningService колбэк окончания звонка не
    // даёт, поэтому без таймаута окно висело бы вечно, и любая OTP-SMS после
    // первого же неизвестного звонка навсегда трактовалась бы как DANGER «во
    // время звонка». Точное закрытие есть, но условное: app/CallStateWatcher
    // (сотовые, только при выданном READ_PHONE_STATE) и NLS.onNotificationRemoved
    // (мессенджер-звонки) зовут onCallEnded() — тогда окно закрывается раньше.
    private fun duringRiskyCall(): Boolean =
        call.active && call.risky && (nowMs() - call.startedAtMs) <= RISKY_WINDOW_MS

    /**
     * Публичная проверка «сейчас открыто окно рискованного звонка» — для внешнего
     * опросчика переднего плана (app/BankAppMonitor), который должен крутиться
     * только пока окно активно, и сам остановиться, когда оно закрылось/истекло.
     */
    @Synchronized
    fun isRiskyCallActive(): Boolean = duringRiskyCall()

    @Synchronized
    fun onSms(text: String, rawSender: String = ""): Verdict {
        val otp = smsHasOtp(text)
        val imp = impersonationFlag(text, rawSender)
        val reasons = mutableListOf<String>()
        var level = Level.NONE
        var conf = 0.0

        if (otp && duringRiskyCall()) {
            reasons.add("OTP/код в СМС пришёл ВО ВРЕМЯ звонка с незнакомого номера")
            reasons.add("Классическая схема «продиктуйте код из сообщения»")
            level = Level.DANGER
            conf = 0.9
        } else if (otp) {
            reasons.add("Пришёл код подтверждения — никому его не сообщайте")
            level = Level.WARN
            conf = 0.4
        }
        if (imp != null) {
            reasons.add(imp)
            level = Level.DANGER
            conf = maxOf(conf, 0.8)
        }
        if (level == Level.NONE) {
            return Verdict(Level.NONE, 0.0, emptyList(), "")
        }
        val advice = "НЕ называйте код никому по телефону. Положите трубку и перезвоните в банк сами."
        return Verdict(level, conf, reasons, advice)
    }

    @Synchronized
    fun onAppInstalled(pkg: String): Verdict {
        val name = REMOTE_ACCESS_PKGS[pkg] ?: return Verdict(Level.NONE, 0.0, emptyList(), "")
        return if (duringRiskyCall()) {
            Verdict(
                Level.DANGER, 0.92,
                listOf(
                    "Во время звонка с незнакомого номера ставится программа удалённого доступа ($name)",
                    "Схема «установите приложение, мы поможем» = передача контроля мошеннику",
                ),
                "СТОП. Не устанавливайте это по просьбе звонящего. Положите трубку.",
            )
        } else {
            Verdict(
                Level.WARN, 0.5,
                listOf("Устанавливается программа удалённого доступа ($name)"),
                "Если это по просьбе звонящего/из СМС — не продолжайте.",
            )
        }
    }

    @Synchronized
    fun onBankAppOpened(appName: String? = null): Verdict {
        if (duringRiskyCall()) {
            val which = if (appName.isNullOrBlank()) "" else " ($appName)"
            return Verdict(
                Level.DANGER, 0.85,
                listOf(
                    "Банковское приложение$which открыто во время звонка с незнакомого номера",
                    "Мошенник может диктовать шаги перевода",
                ),
                "Не выполняйте операции по инструкции звонящего. Завершите звонок.",
            )
        }
        return Verdict(Level.NONE, 0.0, emptyList(), "")
    }

    companion object {
        /**
         * Длительность окна корреляции «рискованного звонка». Эвристический дефолт —
         * скам-звонки, где жертву ведут по шагам, обычно длятся единицы–десятки минут.
         * Точное значение калибруется на реальном трафике; если/когда подключат
         * TelephonyManager/TelephonyCallback с реальным колбэком окончания звонка,
         * явный onCallEnded() вытеснит этот таймаут (окно закроется раньше).
         */
        const val RISKY_WINDOW_MS: Long = 10 * 60 * 1000L
    }
}
