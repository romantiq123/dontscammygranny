package kz.invisibleshield.app.notification

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kz.invisibleshield.app.alert.AlertPresenter
import kz.invisibleshield.app.log.FileLog
import kz.invisibleshield.app.pipeline.BankAppMonitor
import kz.invisibleshield.app.pipeline.ShieldPlanner
import kz.invisibleshield.core.aggregator.Aggregator
import kz.invisibleshield.core.atomizer.RegexAtomizer
import kz.invisibleshield.core.model.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ADR-001/007/012: первичный канал захвата текста (SMS, мессенджеры). NLS — не
 * Accessibility (см. ADR-001 why). Требует ручного включения пользователем
 * (Settings > Notification access) — программно запросить нельзя.
 */
class ShieldNotificationListenerService : NotificationListenerService() {

    // SupervisorJob + cancel в onDestroy: отдельные события не роняют друг друга,
    // и запущенные корутины не переживают отвязку сервиса (dispatcher — общий
    // синглтон, его cancel не трогает: отменяется только Job этого scope).
    private val scope = CoroutineScope(ShieldPlanner.dispatcher + SupervisorJob())

    // Ключ уведомления активного мессенджер-звонка, на который армлено окно риска.
    // Нужен для дедупа (мессенджер обновляет уведомление звонка много раз — не
    // вибрируем на каждое) и для точного закрытия окна по снятию уведомления.
    // Коллбэки NLS приходят на одном потоке, поэтому доступ не конкурентный.
    private var armedCallKey: String? = null

    // Дедуп текстовых уведомлений: key -> hashCode последнего обработанного текста.
    // Только с потока NLS-коллбэков — синхронизация не нужна.
    private val lastProcessed = HashMap<String, Int>()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * Уведомление мессенджер-звонка сняли (звонок завершён/отклонён/пропущен) —
     * закрываем окно риска, если оно было армлено именно этим звонком. Для
     * мессенджер-звонков это ТОЧНЫЙ сигнал окончания (в отличие от сотовых, где
     * колбэка окончания нет и окно закрывается лишь по таймауту, ADR-011).
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == armedCallKey) {
            armedCallKey = null
            ShieldPlanner.engine.onCallEnded()
            FileLog.i(TAG, "мессенджер-звонок (${sbn.packageName}) завершён -> окно риска закрыто")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // КРИТИЧНО: не анализируем собственные алерты (AlertPresenter). Иначе
        // heads-up уведомление «Осторожно…» с текстом «Никому не называйте код…»
        // само матчит OTP-паттерн -> новый анализ -> новый алерт -> ... бесконечная
        // петля обратной связи (найдена на устройстве 2026-07-21).
        if (sbn.packageName == packageName) return

        // Входящий звонок из мессенджера (WhatsApp/Telegram/Viber/…). CallScreeningService
        // (ADR-011) их НЕ видит — это VoIP, он не идёт через Telecom-стек. Ловим по
        // уведомлению-звонку и армим то же «окно рискованного звонка» в ShieldEngine.
        // Это событие звонка, а не текст — через текстовый Atomizer его не гоняем.
        if (isMessengerCallNotification(sbn)) {
            handleMessengerCall(sbn)
            return
        }

        // Оптимизация (по логу устройства 2026-07-22): постоянные/системные уведомления
        // (заряд батареи, медиаплеер, прогресс загрузки, foreground-сервисы, сводки
        // групп) скамом не бывают — не гоняем на них пайплайн. Идёт ПОСЛЕ ветки звонка
        // (звонок тоже ongoing, но обрабатывается выше и до сюда не доходит).
        if (shouldSkip(sbn)) return

        val text = extractText(sbn.notification) ?: return

        // Дедуп: мессенджеры/система перепосчитывают ОДНО И ТО ЖЕ уведомление много раз
        // в минуту (в логе одна переписка гонялась по 8+ раз). Повтор с тем же ключом и
        // тем же текстом пропускаем — экономим CPU/батарею и, главное, НЕ запускаем
        // повторный LLM-инференс на идентичный текст.
        if (isDuplicate(sbn.key, text)) return

        val sender = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        // EXTRA_TITLE — это ОТОБРАЖАЕМОЕ имя (для банк-приложений почти всегда
        // буквенное, напр. «Kaspi.kz»), а НЕ номер отправителя. Передаём его в
        // проверку импперсонации-по-номеру (ShieldEngine.onSms -> impersonationFlag)
        // только если это реально похоже на телефонный номер; иначе номер нам
        // неизвестен -> пустая строка -> impersonationFlag вернёт null (см. его
        // комментарий). Без этого любое банк-уведомление с буквенным заголовком
        // давало ложный DANGER. Текстовый bank_impersonation ловит RegexAtomizer.
        val senderNumber = if (looksLikePhoneNumber(sender)) sender else ""

        scope.launch {
            FileLog.i(TAG, "onNotificationPosted от «$sender»: ${text.take(200)}")

            // Atomizer (regex, 8 текстовых категорий, ADR-007) — общий текстовый путь.
            val atom = RegexAtomizer.atomize(text)
            FileLog.d(TAG, "Atomizer: category=${atom.category} confident=${atom.confident}")

            // Эскалация к LLM только когда Atomizer нашёл хоть какой-то сигнал
            // (category != "none") — большинство трафика (обычная переписка) не
            // будит LLM, ровно как описано в ADR-005 Why. Это же закрывает открытый
            // пункт HANDOFF "confident всегда true, порог эскалации не определён":
            // порогом де-факто становится "Atomizer нашёл категорию", а не
            // confident-флаг (последний зарезервирован под более тонкую эскалацию
            // в будущем, если этот грубый порог окажется слишком частым/редким).
            val llmVerdict = if (atom.category != "none") {
                ShieldPlanner.llmExecutor.infer(text, atom.category)
            } else {
                null
            }
            val textVerdict = Aggregator.reconcile(atom, llmVerdict)

            // Planner (ShieldEngine, ADR-011) — тот же текст ещё раз, но уже в контексте
            // "идёт ли сейчас рискованный звонок" (OTP-во-время-звонка, impersonation).
            //
            // TODO(интеграционный пробел, не формализован отдельным ADR): RegexAtomizer
            // и ShieldEngine.onSms() частично перекрываются (оба видят OTP/impersonation),
            // но были написаны как раздельные прототипы (regex_layer.py и call_detect.py)
            // и никогда не сводились в один вызов. Здесь оба выполняются, и берётся более
            // severe результат — тот же принцип "никто не может понизить", что и в
            // Aggregator (ADR-005), но применённый к этой конкретной паре путей, а не
            // формально решённый как отдельный ADR. Если оба окажутся избыточны/шумны —
            // нужно явное решение, кто из двух работает по SMS, а не оба.
            val callVerdict = ShieldPlanner.engine.onSms(text, senderNumber)
            val finalVerdict = if (callVerdict.level.ordinal > textVerdict.level.ordinal) callVerdict else textVerdict
            FileLog.i(
                TAG,
                "итог: textVerdict=${textVerdict.level} callVerdict=${callVerdict.level} " +
                    "-> final=${finalVerdict.level} (llmVerdict=${llmVerdict ?: "не звали"})",
            )

            if (finalVerdict.level != Level.NONE) {
                AlertPresenter.presentForMessage(finalVerdict)
            }
        }
    }

    private companion object {
        const val TAG = "NLS"

        const val MAX_DEDUP_ENTRIES = 256

        // Категории уведомлений, которые скамом не бывают (в дополнение к флагам
        // ongoing/foreground/group-summary в shouldSkip).
        val SKIP_CATEGORIES: Set<String> = setOf(
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_STOPWATCH,
        )

        // Мессенджеры с VoIP-звонками, которые CallScreeningService не видит.
        // Ключ — package, значение — человекочитаемое имя для лога/алерта.
        val VOIP_CALL_PACKAGES: Map<String, String> = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "org.thunderdog.challegram" to "Telegram X",
            "com.viber.voip" to "Viber",
            "com.google.android.apps.tachyon" to "Google Meet",
            "org.thoughtcrime.securesms" to "Signal",
            "com.skype.raider" to "Skype",
        )
    }

    /**
     * «Похоже на телефонный номер»: есть достаточно цифр и нет букв. Заголовок
     * банк-приложения («Kaspi.kz», «Halyk») букв содержит -> не номер; SMS от
     * неизвестного номера показывает сам номер -> проходит. Достаточно грубо:
     * задача — не пропустить буквенное имя в поле, где логика ждёт номер.
     */
    private fun looksLikePhoneNumber(s: String): Boolean {
        val digits = s.count { it.isDigit() }
        return digits >= 5 && s.none { it.isLetter() }
    }

    /**
     * Уведомление — это входящий/идущий VoIP-звонок из мессенджера? Признаки:
     * пакет из [VOIP_CALL_PACKAGES] И (категория CATEGORY_CALL ИЛИ есть
     * full-screen intent — оба ставят мессенджеры именно на звонок, но НЕ на
     * обычное сообщение и НЕ на «пропущенный звонок»). Чат-сообщения сюда не
     * попадают и уходят в обычный текстовый путь.
     */
    private fun isMessengerCallNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in VOIP_CALL_PACKAGES) return false
        val n = sbn.notification
        return n.category == Notification.CATEGORY_CALL || n.fullScreenIntent != null
    }

    /**
     * Армит «окно рискованного звонка» на мессенджер-звонок с НЕЗНАКОМОГО номера.
     * WhatsApp/Telegram показывают ИМЯ, если звонящий в контактах, иначе — НОМЕР.
     * Номер => звонящего нет в контактах => подозрительный случай (так и приходят
     * «служба безопасности банка» через мессенджер) — армим окно + предупреждаем.
     * Имя => сохранённый контакт => окно НЕ армим (иначе ложный DANGER на OTP во
     * время легального звонка родственника). Дедуп по ключу уведомления, чтобы не
     * вибрировать на каждое обновление уведомления звонка.
     */
    private fun handleMessengerCall(sbn: StatusBarNotification) {
        if (sbn.key == armedCallKey) return // тот же звонок уже обработан
        val app = VOIP_CALL_PACKAGES[sbn.packageName] ?: sbn.packageName
        val caller = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()

        if (!looksLikePhoneNumber(caller)) {
            FileLog.i(TAG, "мессенджер-звонок ($app) от «${caller.ifBlank { "?" }}» — похоже на контакт, окно не армим")
            return
        }

        armedCallKey = sbn.key
        // onCallStarted @Synchronized — безопасно с потока NLS-коллбэка; армит окно.
        val verdict = ShieldPlanner.engine.onCallStarted(caller)
        FileLog.i(TAG, "мессенджер-звонок ($app) с незнакомого номера «$caller» -> окно риска армлено, level=${verdict.level}")
        // ADR-011 (расширение): следим за открытием банк-приложения во время звонка.
        if (ShieldPlanner.engine.isRiskyCallActive()) {
            BankAppMonitor.arm(this)
        }
        // Во время звонка — вибро + визуал, без TTS (ADR-013): presentForCall.
        if (verdict.level != Level.NONE) {
            AlertPresenter.presentForCall(verdict)
        }
    }

    /**
     * Постоянное/системное/служебное уведомление, которое скамом не бывает: заряд
     * батареи, медиаплеер, прогресс загрузки, foreground-сервис, сводка группы.
     * Отсекаем ДО пайплайна (звонки мессенджеров тоже ongoing, но их ветка выше).
     */
    private fun shouldSkip(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val flags = n.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        val cat = n.category
        return cat != null && cat in SKIP_CATEGORIES
    }

    /**
     * Тот же ключ + тот же текст, что в прошлый раз -> дубликат (уведомление
     * переопубликовано без изменений). Грубая эвикция: при переполнении чистим
     * карту целиком (в худшем случае несколько активных чатов один раз пройдут
     * повторно — дёшево).
     */
    private fun isDuplicate(key: String, text: String): Boolean {
        val hash = text.hashCode()
        if (lastProcessed[key] == hash) return true
        if (lastProcessed.size >= MAX_DEDUP_ENTRIES) lastProcessed.clear()
        lastProcessed[key] = hash
        return false
    }

    /**
     * ADR-012: приоритет полей извлечения текста — EXTRA_MESSAGES (MessagingStyle,
     * самое полное) -> EXTRA_BIG_TEXT (развёрнутый вид) -> EXTRA_TEXT (часто это
     * свёрнутая превью-строка, источник риска обрезки). НЕ подтверждено на билде,
     * использует ли Google Messages MessagingStyle для обычных SMS (см. decisions.md,
     * блокер категории A) — здесь реализована цепочка приоритета как решено, сама
     * полнота покрытия не верифицирована.
     */
    private fun extractText(notification: Notification): String? {
        val extras: Bundle = notification.extras

        val messagingMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messagingMessages != null && messagingMessages.isNotEmpty()) {
            val texts = Notification.MessagingStyle.Message
                .getMessagesFromBundleArray(messagingMessages)
                .mapNotNull { it.text?.toString() }
            if (texts.isNotEmpty()) return texts.joinToString(separator = "\n")
        }

        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { return it.toString() }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.let { return it.toString() }
        return null
    }
}
