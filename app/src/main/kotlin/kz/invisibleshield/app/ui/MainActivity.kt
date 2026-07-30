package kz.invisibleshield.app.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kz.invisibleshield.app.R
import kz.invisibleshield.app.alert.AlertPresenter
import kz.invisibleshield.app.llm.ModelDownloadState
import kz.invisibleshield.app.llm.ModelPrefs
import kz.invisibleshield.app.pipeline.CallStateWatcher
import kz.invisibleshield.app.pipeline.ContactsLoader
import kz.invisibleshield.app.pipeline.ShieldPlanner
import kz.invisibleshield.app.pipeline.WatchedApps
import kz.invisibleshield.core.aggregator.Aggregator
import kz.invisibleshield.core.atomizer.BANK_APP_PKGS
import kz.invisibleshield.core.atomizer.RegexAtomizer

/**
 * Онбординг-экран (ADR-011/012/013): все ключевые доступы приложения — спец-
 * разрешения, которые НЕЛЬЗЯ запросить обычным диалогом (Notification access,
 * роль экрана вызовов, статистика использования). Этот экран показывает статус
 * каждого и ведёт пользователя в нужный системный экран.
 *
 * Кнопка "Проверить на примере" прогоняет тестовый текст через реальный движок
 * (:core RegexAtomizer + Aggregator) и показывает настоящее предупреждение —
 * способ убедиться, что связка Atomizer→Aggregator→AlertPresenter работает,
 * не дожидаясь реального мошеннического SMS.
 */
class MainActivity : AppCompatActivity() {

    // Тик обновления карточки «Модель ИИ» (прогресс закачки), пока экран виден.
    // Живой лог вынесен на отдельный экран (LogActivity) — тут его больше нет.
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresher = object : Runnable {
        override fun run() {
            refreshModelStatus()
            statusHandler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnNotifications).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnCallRole).setOnClickListener { requestCallScreeningRole() }
        findViewById<Button>(R.id.btnUsage).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnContacts).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
        }
        findViewById<Button>(R.id.btnPostNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF)
            }
        }
        findViewById<Button>(R.id.btnPhoneState).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE_STATE)
        }
        findViewById<Button>(R.id.btnWatchedApps).setOnClickListener { showWatchedAppsPicker() }
        findViewById<Button>(R.id.btnDemo).setOnClickListener { runDemo() }
        findViewById<Button>(R.id.btnShowLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        // Первичный сид: отмечаем установленные банк-приложения (защита «из коробки»).
        val installed = installedLaunchableApps().map { it.pkg }.toSet()
        WatchedApps.ensureDefaults(this, installed, BANK_APP_PKGS.keys)
        findViewById<Button>(R.id.btnModelDownload).setOnClickListener {
            // Явное согласие пользователя на скачивание ~491 МБ: фиксирует согласие
            // и запускает закачку (с учётом «только Wi-Fi»).
            ShieldPlanner.startModelDownload()
            refreshModelStatus()
        }

        findViewById<MaterialSwitch>(R.id.switchWifiOnly).apply {
            isChecked = ModelPrefs.isWifiOnly(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                ModelPrefs.setWifiOnly(this@MainActivity, checked)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshModelStatus()
        statusHandler.postDelayed(statusRefresher, STATUS_REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRefresher)
    }

    /**
     * Карточка "Модель ИИ": статус закачки + прогресс-бар + кнопка "Скачать"/
     * "Повторить". Читает [ModelDownloadState], который пишет фоновая закачка в
     * LlamaCppExecutor. Вызывается в цикле statusRefresher (раз в 1.5с).
     */
    private fun refreshModelStatus() {
        val status = findViewById<TextView>(R.id.statusModel)
        val button = findViewById<MaterialButton>(R.id.btnModelDownload)
        val progress = findViewById<LinearProgressIndicator>(R.id.progressModel)

        when (ModelDownloadState.phase) {
            ModelDownloadState.Phase.READY -> {
                status.text = "✓ Готова"
                status.setTextColor(getColor(R.color.status_ok))
                button.isEnabled = false
                button.text = "Готово"
                progress.visibility = android.view.View.GONE
            }
            ModelDownloadState.Phase.DOWNLOADING -> {
                val mb = ModelDownloadState.downloadedBytes / 1_000_000
                val totalMb = ModelDownloadState.totalBytes / 1_000_000
                status.text = "Качается: $mb / $totalMb МБ (${ModelDownloadState.percent}%)"
                status.setTextColor(getColor(R.color.status_off))
                button.isEnabled = false
                button.text = getString(R.string.model_download)
                progress.visibility = android.view.View.VISIBLE
                progress.isIndeterminate = ModelDownloadState.totalBytes <= 0
                progress.setProgressCompat(ModelDownloadState.percent, true)
            }
            ModelDownloadState.Phase.FAILED -> {
                status.text = "Ошибка: ${ModelDownloadState.message.take(80)}"
                status.setTextColor(getColor(R.color.status_off))
                button.isEnabled = true
                button.text = getString(R.string.model_retry)
                progress.visibility = android.view.View.GONE
            }
            ModelDownloadState.Phase.WAITING_WIFI -> {
                status.text = "Ожидание Wi-Fi"
                status.setTextColor(getColor(R.color.status_off))
                button.isEnabled = true
                button.text = getString(R.string.model_download)
                progress.visibility = android.view.View.GONE
            }
            ModelDownloadState.Phase.IDLE -> {
                status.text = "Не скачана"
                status.setTextColor(getColor(R.color.status_off))
                button.isEnabled = true
                button.text = getString(R.string.model_download)
                progress.visibility = android.view.View.GONE
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        // Только что выдали контакты -> сразу подгружаем «свои» номера в движок.
        if (requestCode == REQ_CONTACTS && granted) {
            ContactsLoader.refresh(this)
        }
        // Выдали READ_PHONE_STATE -> включаем точное слежение за концом звонка.
        if (requestCode == REQ_PHONE_STATE && granted) {
            CallStateWatcher.ensureRegistered(this)
        }
    }

    private fun requestCallScreeningRole() {
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        ) {
            startActivityForResult(
                rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),
                REQ_CALL_ROLE,
            )
        }
    }

    /**
     * Демонстрация: прогоняем пример через реальный движок (:core) и показываем
     * результат диалогом на экране — надёжно и без зависимости от разрешения на
     * уведомления. Дополнительно пробуем показать и настоящее heads-up уведомление
     * (AlertPresenter), но его сбой НЕ роняет демо. Любая ошибка выводится текстом
     * в диалог, а не крашит приложение.
     */
    private fun runDemo() {
        val sample = "Halyk Bank: служба безопасности. Ваша карта заблокирована. " +
            "Никому не сообщайте код из СМС, срочно переведите деньги на безопасный счёт."

        val message = try {
            val atom = RegexAtomizer.atomize(sample)
            val verdict = Aggregator.reconcile(atom, llmVerdict = null)
            runCatching { AlertPresenter.presentForMessage(verdict) } // уведомление — по возможности
            buildString {
                append("Уровень: ").append(verdict.level).append('\n')
                append("Уверенность: ").append(verdict.confidence).append("\n\n")
                if (verdict.advice.isNotBlank()) append(verdict.advice).append("\n\n")
                if (verdict.reasons.isNotEmpty()) {
                    append("Почему:\n")
                    verdict.reasons.forEach { append("• ").append(it).append('\n') }
                }
            }.trimEnd()
        } catch (t: Throwable) {
            // Показываем всю цепочку причин: NoClassDefFoundError часто прячет
            // корневой PatternSyntaxException из <clinit>.
            val chain = generateSequence<Throwable>(t) { it.cause }
                .joinToString("\n  ↳ ") { "${it.javaClass.simpleName}: ${it.message}" }
            "Ошибка при проверке:\n$chain"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Результат проверки примера")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun refreshStatus() {
        setStatus(R.id.statusNotifications, R.id.btnNotifications, isNotificationAccessGranted())
        setStatus(R.id.statusCallRole, R.id.btnCallRole, isCallScreeningRoleHeld())
        setStatus(R.id.statusUsage, R.id.btnUsage, isUsageAccessGranted())
        setStatus(R.id.statusContacts, R.id.btnContacts, isGranted(Manifest.permission.READ_CONTACTS))
        setStatus(R.id.statusPostNotif, R.id.btnPostNotif, isPostNotifGranted())
        setStatus(R.id.statusPhoneState, R.id.btnPhoneState, isGranted(Manifest.permission.READ_PHONE_STATE))
        updateWatchedStatus()
    }

    private data class AppItem(val pkg: String, val label: String)

    /** Установленные приложения с иконкой запуска (для выбора под наблюдение). */
    private fun installedLaunchableApps(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, 0)
        return resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == packageName) return@mapNotNull null // себя не наблюдаем
            AppItem(pkg, ri.loadLabel(pm).toString())
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    /** Диалог со списком приложений: пользователь сам отмечает, за какими следить. */
    private fun showWatchedAppsPicker() {
        val apps = installedLaunchableApps()
        if (apps.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.watched_title)
                .setMessage("Не удалось получить список приложений.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = apps.map { it.label }.toTypedArray()
        val current = WatchedApps.get(this)
        val checked = BooleanArray(apps.size) { apps[it].pkg in current }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.watched_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Сохранить") { _, _ ->
                val selected = apps.filterIndexed { i, _ -> checked[i] }.map { it.pkg }.toSet()
                WatchedApps.set(this, selected)
                updateWatchedStatus()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateWatchedStatus() {
        val count = WatchedApps.get(this).size
        val tv = findViewById<TextView>(R.id.statusWatched)
        if (count > 0) {
            tv.text = "Под наблюдением: $count прил."
            tv.setTextColor(getColor(R.color.status_ok))
        } else {
            tv.text = "Ничего не выбрано"
            tv.setTextColor(getColor(R.color.status_off))
        }
    }

    /** Обновляет строку статуса карточки и подпись кнопки по факту выдачи доступа. */
    private fun setStatus(statusViewId: Int, buttonId: Int, granted: Boolean) {
        val status = findViewById<TextView>(statusViewId)
        val button = findViewById<Button>(buttonId)
        if (granted) {
            status.text = "✓ Включено"
            status.setTextColor(getColor(R.color.status_ok))
            button.text = "Готово"
            button.isEnabled = false
        } else {
            status.text = "Не включено"
            status.setTextColor(getColor(R.color.status_off))
            button.text = getString(R.string.action_enable)
            button.isEnabled = true
        }
    }

    private fun isGranted(perm: String): Boolean =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun isPostNotifGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }

    private fun isNotificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun isCallScreeningRoleHeld(): Boolean {
        val rm = getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun isUsageAccessGranted(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private const val REQ_CONTACTS = 101
        private const val REQ_POST_NOTIF = 102
        private const val REQ_CALL_ROLE = 103
        private const val REQ_PHONE_STATE = 104
        private const val STATUS_REFRESH_MS = 1500L
    }
}
