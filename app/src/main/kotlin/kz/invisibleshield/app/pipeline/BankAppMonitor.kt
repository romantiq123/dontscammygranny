package kz.invisibleshield.app.pipeline

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kz.invisibleshield.app.alert.AlertPresenter
import kz.invisibleshield.app.log.FileLog
import kz.invisibleshield.core.atomizer.BANK_APP_PKGS
import kz.invisibleshield.core.model.Level

/**
 * ADR-011 (расширение): детект «банковское приложение открыто во время рискованного
 * звонка». У Android НЕТ события «пользователь открыл приложение», поэтому пока
 * открыто окно рискованного звонка (ShieldEngine.isRiskyCallActive) — ОПРАШИВАЕМ,
 * какое приложение на переднем плане, через UsageStatsManager.
 *
 * Почему без foreground-сервиса: опрос запускается ТОЛЬКО на время звонка (арм →
 * self-stop при закрытии окна), в фоне ничего не крутится, новых разрешений не надо
 * (используется уже объявленный PACKAGE_USAGE_STATS — «Статистика использования» в
 * онбординге). Ограничение (честно): если система убьёт процесс во время звонка,
 * опрос прекратится; более стойкий вариант — FGS (ADR-008), это следующий шаг.
 *
 * Требует выданного доступа к статистике использования. Если не выдан —
 * queryEvents вернёт пусто, детект просто не сработает (без краша).
 */
object BankAppMonitor {

    private const val TAG = "BankAppMonitor"
    private const val POLL_INTERVAL_MS = 1200L
    private const val LOOKBACK_MS = 6_000L

    private val polling = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Запустить опрос, если он ещё не идёт. Вызывать в момент арма окна рискованного
     * звонка (ShieldCallScreeningService, мессенджер-звонок в NLS). Повторные вызовы
     * во время уже идущего опроса игнорируются.
     */
    fun arm(context: Context) {
        if (!polling.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            val watched = WatchedApps.get(app) // снимок списка на момент старта звонка
            FileLog.d(TAG, "старт опроса переднего плана (окно звонка открыто), под наблюдением: ${watched.size} прил.")
            var lastAlertedPkg: String? = null
            try {
                while (ShieldPlanner.engine.isRiskyCallActive()) {
                    val pkg = currentForegroundApp(app)
                    if (pkg != null && pkg != app.packageName) {
                        if (pkg in watched) {
                            if (pkg != lastAlertedPkg) {
                                val name = displayName(app, pkg)
                                FileLog.w(TAG, "приложение под наблюдением во время звонка: $name ($pkg) -> DANGER")
                                val verdict = ShieldPlanner.engine.onBankAppOpened(name)
                                if (verdict.level != Level.NONE) AlertPresenter.presentForCall(verdict)
                                lastAlertedPkg = pkg
                            }
                        } else {
                            // Диагностика: помогает узнать реальные пакеты приложений.
                            FileLog.d(TAG, "передний план: $pkg (не под наблюдением)")
                            lastAlertedPkg = null // ушли из приложения — при возврате алертим снова
                        }
                    }
                    delay(POLL_INTERVAL_MS)
                }
            } finally {
                FileLog.d(TAG, "опрос остановлен (окно рискованного звонка закрылось)")
                polling.set(false)
            }
        }
    }

    /** Читаемое имя приложения: из BANK_APP_PKGS (если известно), иначе метка из PackageManager. */
    private fun displayName(context: Context, pkg: String): String {
        BANK_APP_PKGS[pkg]?.let { return it }
        return runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /** Последнее приложение, вышедшее на передний план за окно LOOKBACK_MS, или null. */
    private fun currentForegroundApp(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = event.packageName
            }
        }
        return last
    }
}
