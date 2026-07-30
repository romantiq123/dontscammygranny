package kz.invisibleshield.app.pipeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kz.invisibleshield.app.alert.AlertPresenter
import kz.invisibleshield.core.model.Level

/**
 * ADR-011: детект установки remote-access приложений (AnyDesk/TeamViewer/RustDesk
 * и т.п.) во время активного рискованного звонка — сильнейший сигнал схемы
 * "установите приложение, мы поможем". Пакеты — core.atomizer.REMOTE_ACCESS_PKGS.
 */
class PackageAddedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        val verdict = ShieldPlanner.engine.onAppInstalled(pkg)
        if (verdict.level != Level.NONE) {
            AlertPresenter.presentForMessage(verdict)
        }
    }
}
