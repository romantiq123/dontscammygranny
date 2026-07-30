package kz.invisibleshield.app.alert

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kz.invisibleshield.app.ShieldApp
import kz.invisibleshield.app.log.FileLog
import kz.invisibleshield.core.model.Level
import kz.invisibleshield.core.model.Verdict

/**
 * ADR-013: доступный вывод для аудитории со сниженным зрением/слухом/когницией.
 * Обычные API (NotificationManager/Vibrator), без AccessibilityService (ADR-001).
 *
 * Разные каналы для разных ситуаций (ADR-013, "разведено 07.2026"):
 * - presentForCall(): вибро + heads-up уведомление, БЕЗ TTS — озвучка на другом
 *   аудио-тракте рискует не прозвучать там, где слышит пользователь, либо
 *   прорваться и быть услышанной самим мошенником через микрофон.
 * - presentForMessage(): heads-up уведомление + вибро.
 *
 * TODO (ADR-013, категория A — требуют проверки на билде): TextToSpeech.speak для
 * сообщений и USE_FULL_SCREEN_INTENT (не SYSTEM_ALERT_WINDOW, ADR-013 why: overlay —
 * сигнатурный признак banking-малвари); распространяется ли льгота FSI на роль
 * ROLE_CALL_SCREENING — не проверено, см. decisions.md.
 */
object AlertPresenter {

    fun presentForCall(verdict: Verdict) {
        vibrate(longArrayOf(0, 400, 200, 400))
        notify(ShieldApp.CHANNEL_CALL, ID_CALL, "Осторожно: подозрительный звонок", verdict)
    }

    fun presentForMessage(verdict: Verdict) {
        vibrate(longArrayOf(0, 300))
        notify(ShieldApp.CHANNEL_ALERT, ID_MESSAGE, "Осторожно: возможное мошенничество", verdict)
    }

    private fun notify(channelId: String, id: Int, title: String, verdict: Verdict) {
        val ctx = ShieldApp.context
        val body = buildString {
            if (verdict.advice.isNotBlank()) append(verdict.advice)
            if (verdict.reasons.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Почему: ").append(verdict.reasons.joinToString("; "))
            }
        }.ifBlank { "Обнаружен подозрительный сигнал" }

        val priority = if (verdict.level == Level.DANGER) {
            NotificationCompat.PRIORITY_MAX
        } else {
            NotificationCompat.PRIORITY_HIGH
        }

        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(verdict.advice.ifBlank { "Нажмите для подробностей" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setCategory(
                if (channelId == ShieldApp.CHANNEL_CALL) NotificationCompat.CATEGORY_CALL
                else NotificationCompat.CATEGORY_MESSAGE,
            )
            .setAutoCancel(true)
            .build()

        // POST_NOTIFICATIONS — runtime-разрешение с API 33. Если не выдано — тихо
        // не показываем (лог остаётся для отладки), процесс не роняем.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            logStub(channelId, verdict, "нет POST_NOTIFICATIONS")
            return
        }
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, notification)
            logStub(channelId, verdict, "показано")
        } catch (t: Throwable) {
            logStub(channelId, verdict, "ошибка notify: ${t.message}")
        }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val ctx = ShieldApp.context
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (t: Throwable) {
            FileLog.w(TAG, "vibrate error: ${t.message}", t)
        }
    }

    private fun logStub(channel: String, verdict: Verdict, note: String) {
        FileLog.i(
            TAG,
            "[$channel/$note] level=${verdict.level} conf=${verdict.confidence} advice=${verdict.advice} reasons=${verdict.reasons}",
        )
    }

    private const val TAG = "AlertPresenter"

    private const val ID_MESSAGE = 1001
    private const val ID_CALL = 1002
}
