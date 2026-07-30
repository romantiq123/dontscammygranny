package kz.invisibleshield.app.pipeline

import android.content.Context

/**
 * Пользовательский список приложений, за открытием которых во время рискованного
 * звонка следит [BankAppMonitor]. Раньше список был захардкожен (BANK_APP_PKGS);
 * теперь пользователь сам отмечает, на какие приложения реагировать (банк, кошелёк,
 * госуслуги — что угодно, что мошенник может заставить открыть).
 *
 * Хранится в SharedPreferences как множество имён пакетов. BANK_APP_PKGS остаётся
 * лишь как разумные значения по умолчанию (см. [ensureDefaults]).
 */
object WatchedApps {

    private const val PREFS = "watched_apps"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_INITIALIZED = "initialized"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Текущий набор пакетов под наблюдением (копия — безопасно мутировать). */
    fun get(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun set(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_PACKAGES, packages).apply()
    }

    /**
     * Первичная инициализация: при самом первом запуске отмечаем те банк-приложения
     * (BANK_APP_PKGS), что реально установлены на устройстве — чтобы защита работала
     * «из коробки». Дальше пользователь правит список сам, повторно НЕ перезаписываем.
     */
    fun ensureDefaults(context: Context, installedPackages: Set<String>, defaultPackages: Set<String>) {
        val p = prefs(context)
        if (p.getBoolean(KEY_INITIALIZED, false)) return
        val seeded = installedPackages.intersect(defaultPackages)
        p.edit()
            .putStringSet(KEY_PACKAGES, seeded)
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
    }
}
