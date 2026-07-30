package kz.invisibleshield.app.llm

import android.content.Context

/**
 * Настройки скачивания модели (ADR-006 UX): согласие пользователя и режим «только
 * по Wi-Fi». Без явного согласия модель (~491 МБ) НЕ качается вовсе — ни на старте,
 * ни из пайплайна. Согласие даётся один раз кнопкой «Скачать» и запоминается, чтобы
 * при следующих запусках докачка возобновлялась сама (с учётом Wi-Fi-режима).
 */
object ModelPrefs {

    private const val PREFS = "model_prefs"
    private const val KEY_CONSENT = "download_consented"
    private const val KEY_WIFI_ONLY = "wifi_only"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConsented(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONSENT, false)

    fun setConsented(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONSENT, value).apply()
    }

    /** По умолчанию ВКЛ — на 491 МБ безопаснее не тратить мобильный трафик без спроса. */
    fun isWifiOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_ONLY, true)

    fun setWifiOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    }
}
