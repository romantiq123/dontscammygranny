package kz.invisibleshield.app.pipeline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kz.invisibleshield.app.log.FileLog

/**
 * ADR-011: «известный номер = тишина». Читает номера из ContactsContract и отдаёт
 * их в ShieldEngine (updateContacts), чтобы звонок/SMS от сохранённого контакта не
 * поднимал ложную тревогу. До этого список пуст — каждый звонок считался незнакомым.
 *
 * Чтение — в фоновом потоке (запрос к провайдеру контактов может быть небыстрым),
 * под защитой READ_CONTACTS. Вызывается при старте процесса (ShieldApp) и сразу
 * после выдачи разрешения (MainActivity).
 */
object ContactsLoader {

    private const val TAG = "ContactsLoader"

    fun refresh(context: Context) {
        val app = context.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return // нет разрешения — движок остаётся с пустым списком (всё незнакомое)
        }
        Thread({
            try {
                val numbers = readContactNumbers(app)
                ShieldPlanner.engine.updateContacts(numbers)
                FileLog.i(TAG, "контакты загружены: ${numbers.size} номеров")
            } catch (t: Throwable) {
                FileLog.e(TAG, "не удалось прочитать контакты (не критично)", t)
            }
        }, "shield-contacts").start()
    }

    private fun readContactNumbers(context: Context): List<String> {
        val numbers = ArrayList<String>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        ) ?: return numbers
        cursor.use { c ->
            val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (idx < 0) return numbers
            while (c.moveToNext()) {
                c.getString(idx)?.let { numbers.add(it) }
            }
        }
        return numbers
    }
}
