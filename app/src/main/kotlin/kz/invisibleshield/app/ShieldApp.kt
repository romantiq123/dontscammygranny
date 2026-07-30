package kz.invisibleshield.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kz.invisibleshield.app.log.FileLog
import kz.invisibleshield.app.pipeline.CallStateWatcher
import kz.invisibleshield.app.pipeline.ContactsLoader
import kz.invisibleshield.app.pipeline.ShieldPlanner

/**
 * Application-точка: держит applicationContext для AlertPresenter (ADR-013 — вывод
 * нужен Context, но сервисы/ресиверы могут жить и без своего) и создаёт канал
 * уведомлений один раз при старте процесса.
 *
 * Каналы разделены (ADR-013 "разные каналы для разных ситуаций"):
 *  - CHANNEL_ALERT — предупреждение по сообщению (heads-up, звук/вибро).
 *  - CHANNEL_CALL  — предупреждение во время звонка (вибро, без звука в аудио-тракт).
 */
class ShieldApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        FileLog.init(this)
        createChannels()

        // Известные контакты -> «свой номер = тишина» (ADR-011). Читаются в фоне,
        // под READ_CONTACTS; без разрешения — no-op, движок остаётся с пустым списком.
        ContactsLoader.refresh(this)

        // ADR-011 (#6): точный конец сотового звонка -> закрыть окно риска сразу.
        // Под READ_PHONE_STATE; без разрешения — no-op, остаётся таймаут окна.
        CallStateWatcher.ensureRegistered(this)

        // Модель (~491 МБ) БЕЗ явного согласия НЕ качаем (ADR-006 UX). warmUp() теперь
        // сам проверяет согласие: на первом запуске (согласия ещё нет) — no-op; если
        // пользователь ранее нажал «Скачать» — докачка возобновится в фоне (с учётом
        // режима «только Wi-Fi»). Модель в память не грузит (ленивый шаг при infer).
        ShieldPlanner.llmExecutor.warmUp()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "Предупреждения о мошенничестве",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Всплывающее предупреждение по подозрительному сообщению"
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL,
                "Предупреждения во время звонка",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Тихое (вибро) предупреждение во время звонка"
                enableVibration(true)
            },
        )
    }

    companion object {
        const val CHANNEL_ALERT = "shield_alert"
        const val CHANNEL_CALL = "shield_call"

        @Volatile
        lateinit var instance: ShieldApp
            private set

        val context: Context get() = instance.applicationContext
    }
}
