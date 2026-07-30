package kz.invisibleshield.app.pipeline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kz.invisibleshield.app.log.FileLog

/**
 * ADR-011 (#6): точный конец СОТОВОГО звонка. `CallScreeningService` не даёт колбэк
 * окончания звонка, поэтому раньше «окно рискованного звонка» закрывалось только по
 * таймауту (`ShieldEngine.RISKY_WINDOW_MS`, 10 мин). Здесь ловим `CALL_STATE_IDLE`
 * через `TelephonyCallback` (API 31+) / `PhoneStateListener` (ниже) и закрываем окно
 * сразу — `ShieldEngine.onCallEnded()`.
 *
 * Требует `READ_PHONE_STATE`. Без разрешения регистрация тихо пропускается, и
 * работает прежний таймаут (graceful degradation). Для мессенджер-звонков конец
 * ловится точно через `onNotificationRemoved` — этот наблюдатель про сотовые.
 *
 * Регистрируется один раз на процесс (идемпотентно), ссылка на колбэк держится
 * статически, чтобы его не собрал GC.
 */
object CallStateWatcher {

    private const val TAG = "CallStateWatcher"

    @Volatile private var registered = false
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "shield-callstate") }

    private var telephonyCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    @Synchronized
    fun ensureRegistered(context: Context) {
        if (registered) return
        val app = context.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return // нет разрешения -> остаётся таймаут окна
        }
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleState(state)
                }
                tm.registerTelephonyCallback(executor, cb)
                telephonyCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleState(state)
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
            }
            registered = true
            FileLog.i(TAG, "слежение за состоянием сотового звонка включено")
        } catch (t: Throwable) {
            FileLog.e(TAG, "не удалось включить слежение за состоянием звонка (остаётся таймаут окна)", t)
        }
    }

    private fun handleState(state: Int) {
        if (state == TelephonyManager.CALL_STATE_IDLE) {
            ShieldPlanner.engine.onCallEnded()
            FileLog.i(TAG, "звонок завершён (IDLE) -> окно риска закрыто сразу")
        }
    }
}
