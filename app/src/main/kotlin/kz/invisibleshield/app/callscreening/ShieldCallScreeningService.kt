package kz.invisibleshield.app.callscreening

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse
import android.telecom.Connection
import kz.invisibleshield.app.alert.AlertPresenter
import kz.invisibleshield.app.pipeline.BankAppMonitor
import kz.invisibleshield.app.pipeline.CallStateWatcher
import kz.invisibleshield.app.pipeline.ShieldPlanner
import kz.invisibleshield.core.model.Level

/**
 * ADR-011: звонковый канал через CallScreeningService + ROLE_CALL_SCREENING (не
 * дефолтный "Телефон"). onScreenCall() ОБЯЗАН вызвать respondToCall() в течение
 * 5 секунд (проверено, ADR-011) — здесь никакого LLM, только regex/dict-проверка
 * номера (ShieldEngine.onCallStarted, чистая функция, без I/O), поэтому бюджет
 * времени соблюдается by construction.
 *
 * v1 НЕ блокирует звонки (respondToCall с disallowCall=false) — номер это
 * контекст-сигнал, не приговор (ADR-011). Решение показывается пользователю
 * через AlertPresenter, реальный детект — в действиях во время звонка
 * (OTP-SMS/remote-access/bank-app, см. ShieldEngine).
 */
class ShieldCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val rawNumber = callDetails.handle?.schemeSpecificPart ?: ""

        // Call.Details.getCallerNumberVerificationStatus() — сетевая верификация
        // номера (STIR/SHAKEN-уровня), API 29+. Бесплатный сигнал, добавлен в
        // ADR-011 07.2026 как доп. вес к риску, не заменяет остальную эвристику.
        val verificationStatus = when (callDetails.callerNumberVerificationStatus) {
            Connection.VERIFICATION_STATUS_PASSED -> "passed"
            Connection.VERIFICATION_STATUS_FAILED -> "failed"
            else -> "not_verified"
        }

        // Отвечаем СРАЗУ — respondToCall не должен ждать ничего, кроме чистой
        // regex/dict-логики ShieldEngine (никакого LLM на этом этапе, ADR-011).
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSkipNotification(false)
                .build(),
        )

        // core.atomizer.normalize() сам разбирает "+7"/"8"-варианты, доп.
        // нормализация через PhoneNumberUtils здесь не нужна (не дублируем).
        val verdict = ShieldPlanner.engine.onCallStarted(
            rawNumber = rawNumber,
            verificationStatus = verificationStatus,
        )

        // ADR-011 (#6): включить слежение за концом звонка (CALL_STATE_IDLE), если
        // разрешение уже выдано — тогда окно закроется точно, а не по таймауту.
        CallStateWatcher.ensureRegistered(this)

        // ADR-011 (расширение): пока окно рискованного звонка открыто — следим,
        // не откроют ли банк-приложение (опрос UsageStats). Монитор сам остановится
        // по закрытии окна; повторный arm во время активного опроса — no-op.
        if (ShieldPlanner.engine.isRiskyCallActive()) {
            BankAppMonitor.arm(this)
        }

        // ADR-013: во время активного звонка — НЕ TTS (риск быть услышанным
        // мошенником через микрофон или не прозвучать в голосовом тракте вызова).
        // Только вибро + визуал.
        if (verdict.level != Level.NONE) {
            AlertPresenter.presentForCall(verdict)
        }
    }
}
