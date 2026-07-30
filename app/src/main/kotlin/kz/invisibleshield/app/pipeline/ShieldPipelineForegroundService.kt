package kz.invisibleshield.app.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * ADR-008: event-driven shortService — поднимается ТОЛЬКО на время обработки
 * пайплайна (Atomizer->Planner->Executor->Aggregator), затем stopSelf(). НЕ
 * постоянный foreground service.
 *
 * РЕШЕНИЕ 2026-07-27 (#8 из HANDOFF, «нужен ли FGS теперь, когда LLM даёт секундный
 * инференс»): для текущего пайплайна FGS НЕ подключаем. Обоснование:
 *  - Пайплайн (regex + LLM ~1-2с на 0.5B) запускается из NLS — а `NotificationListener`
 *    Service, будучи привязанным системой, держит процесс на «perceptible»-важности,
 *    поэтому короткий инференс в нём не под угрозой отстрела. FGS дал бы гарантию,
 *    но за ощутимую цену.
 *  - Цена shortService на КАЖДУЮ проверку: (1) видимое уведомление «Проверка
 *    сообщения» поверх нашего же алерта = шум; (2) старт FGS из фонового NLS-колбэка
 *    на Android 12+ рискует `ForegroundServiceStartNotAllowedException` (открытый
 *    вопрос exemption'а) — т.е. может СЛОМАТЬ путь, который сейчас работает.
 *  - Выгода околонулевая для 1-2с задачи в живом процессе.
 *
 * ПЕРЕСМОТРЕТЬ, когда: (а) перейдём на более крупную/медленную модель (1.5B+),
 * где инференс станет длинным; или (б) логи с устройств покажут реальные отстрелы
 * процесса посреди инференса. Тогда включить: NLS перед тяжёлым LLM-вызовом стартует
 * этот shortService (с fallback-try/catch: не смог стартовать FGS -> обрабатываем
 * как сейчас, напрямую), stopSelf по завершении.
 *
 * (Отдельно: FGS куда уместнее для многоминутного СКАЧИВАНИЯ модели, чем для
 * секундного инференса — это другой кандидат на использование, вне ADR-008.)
 *
 * Открытые технические детали при будущем подключении:
 * - exemption старта FGS из фона (NLS/CallScreening) — проверить на билде.
 * - Держать ли llama.cpp-модель прогретой между вызовами (зависит от бенча ADR-002/003).
 * - Бюджет shortService <3 мин, иначе ANR.
 */
class ShieldPipelineForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        // TODO: вызвать сюда Executor-пайплайн (сейчас NLS/CallScreening
        // обрабатывают события напрямую через ShieldPlanner, минуя этот сервис).

        stopSelf()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "shield_pipeline"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Проверка сообщения", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("Невидимый Щит проверяет сообщение")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
