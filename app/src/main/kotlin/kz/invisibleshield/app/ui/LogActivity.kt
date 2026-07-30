package kz.invisibleshield.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kz.invisibleshield.app.log.FileLog

/**
 * Отдельный экран диагностического лога (для нас, тестинг). Раньше «живой лог» был
 * встроен в онбординг и его вложенный авто-скролл-вниз перехватывал прокрутку всей
 * страницы. Здесь он на своей странице со своей прокруткой — главный экран больше
 * не дёргается. Автообновление раз в 1.5с, пока экран виден.
 */
class LogActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scroll: ScrollView
    private lateinit var logView: TextView
    private var autoScroll = true

    private val refresher = object : Runnable {
        override fun run() {
            // Обновляем текст ТОЛЬКО пока пользователь у хвоста (autoScroll). Если он
            // сам прокрутил вверх — не трогаем текст вообще, иначе замена текста в
            // ScrollView сбрасывает позицию наверх и читать невозможно. «Вниз»
            // возобновляет живой хвост.
            if (autoScroll) {
                logView.text = FileLog.tail()
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Лог"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        buttons.addView(
            Button(this).apply {
                text = "Копировать путь"
                setOnClickListener {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("Путь к логу", FileLog.path()))
                }
            },
        )
        buttons.addView(
            Button(this).apply {
                text = "Вниз"
                setOnClickListener {
                    autoScroll = true
                    logView.text = FileLog.tail()
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            },
        )
        root.addView(buttons)

        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            isFillViewport = true
            // Как только пользователь сам трогает прокрутку — не выдёргиваем вниз.
            setOnTouchListener { _, _ -> autoScroll = false; false }
        }
        logView = TextView(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = FileLog.tail()
        }
        scroll.addView(logView)
        root.addView(scroll)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(refresher, REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REFRESH_MS = 1500L
    }
}
