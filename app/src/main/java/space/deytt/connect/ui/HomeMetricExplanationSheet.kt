package space.deytt.connect

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.text

internal class HomeMetricExplanationSheet(
    private val host: MainActivity,
    private val setMapTrafficActive: (Boolean) -> Unit,
    private val setStarfieldMotion: (Boolean) -> Unit,
    private val setMetricsPaused: (Boolean) -> Unit,
    private val onDismissed: () -> Unit,
    private val selectTrafficFlow: ((String?) -> Unit)? = null,
) {
    private fun dp(value: Int): Int = host.dp(value)

    var currentTrafficExplanation: Dialog? = null

    fun isTrafficExplanationShowing(): Boolean = currentTrafficExplanation != null

    fun dismiss() {
        currentTrafficExplanation?.dismiss()
        currentTrafficExplanation = null
    }

    fun showHomeMetricExplanation(speed: Boolean) {
        val english = AppLanguage.current(host) == AppLanguage.EN
        val title = if (speed) {
            if (english) "speed" else "скорость"
        } else if (english) "ping" else "ping / задержка"
        val message = if (speed) {
            if (english) "How much data your connection can download per second. A higher Mbps value means faster downloads and higher video quality. It depends on your network and the VPN server.\n\nRefresh measurement runs a short download through your current connection. The last successful result stays visible until the next result arrives; reconnecting clears it."
            else "Сколько данных соединение может скачать за секунду. Чем больше Мбит/с, тем быстрее скачиваются файлы и загружается видео. Значение зависит от вашей сети и VPN-сервера.\n\n«Обновить замеры» запускает короткую загрузку через текущее подключение. Последний успешный результат остаётся до нового замера; при переподключении он сбрасывается."
        } else {
            if (english) "How long a small request takes to get a reply through your VPN, in milliseconds. A lower value means pages and messages respond sooner.\n\nThe first reply appears immediately, then several replies refine the result. Refresh measurement runs a new check. The last successful result stays visible during the same connection."
            else "Сколько миллисекунд нужно небольшому запросу, чтобы получить ответ через VPN. Чем меньше задержка, тем быстрее откликаются страницы и сообщения.\n\nПервый ответ показывается сразу, затем результат уточняется по нескольким ответам. «Обновить замеры» запускает новую проверку. Последний успешный результат сохраняется в пределах текущего подключения."
        }
        AppDialog.Builder(host).setTitle(title).setMessage(message)
            .setPositiveButton(if (english) "got it" else "понятно", null).show()
    }

    fun showTrafficExplanation(flow: String) {
        if (host.isFinishing || host.isDestroyed) return
        val english = AppLanguage.current(host) == AppLanguage.EN
        val (title, message) = when (flow) {
            "upload" -> if (english) "outbound data" to "Requests and data travel from your device to the exit node: opening a page, sending a message or uploading a file."
                else "исходящие данные" to "Запросы и данные идут от устройства к выходному узлу: открытие страницы, отправка сообщения или загрузка файла на сервер."
            "download" -> if (english) "return traffic" to "Responses travel back to your device: page content, incoming messages and downloaded files."
                else "ответный трафик" to "Ответы возвращаются к устройству: содержимое страниц, входящие сообщения и скачиваемые файлы."
            "control" -> if (english) "service traffic" to "Small service exchanges keep a connection working. DNS resolves names; other control messages coordinate the tunnel."
                else "служебный трафик" to "Небольшие служебные обмены поддерживают соединение. DNS определяет адрес по имени, остальные сообщения согласуют работу туннеля."
            else -> return
        }
        val note = if (english) "The moving points illustrate traffic direction; packet contents are not captured."
            else "Движущиеся точки показывают направление потока. Содержимое пакетов не считывается."
        val accent = when (flow) {
            "upload" -> 0xFFFF7798.toInt()
            "download" -> 0xFFFFCE55.toInt()
            else -> 0xFFAF8CFF.toInt()
        }
        currentTrafficExplanation?.dismiss()
        val dialog = Dialog(host)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(host).apply {
            tag = "traffic-explanation"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = host.rounded(DeyttUi.SHEET_SURFACE, 24f)
        }
        panel.addView(
            View(host).apply { background = host.rounded(accent, 2f, accent) },
            LinearLayout.LayoutParams(dp(28), dp(3)).apply { bottomMargin = dp(12) },
        )
        panel.addView(host.text(title, 20f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        })
        panel.addView(
            host.text(message, 13f, DeyttUi.MUTED).apply { setLineSpacing(dp(2).toFloat(), 1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        panel.addView(
            host.text(note, 11f, DeyttUi.MUTED).apply { setLineSpacing(dp(2).toFloat(), 1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) },
        )
        panel.addView(
            host.button(if (english) "show all traffic lines" else "показать все линии", secondary = true).apply {
                setTextColor(accent)
                background = host.rounded((accent and 0x00FFFFFF) or 0x20000000, 22f, (accent and 0x00FFFFFF) or 0x55000000)
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(18) },
        )
        val container = FrameLayout(host).apply {
            setPadding(dp(14), 0, dp(14), dp(20))
            addView(
                ScrollView(host).apply {
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(panel)
                },
                FrameLayout.LayoutParams(
                    minOf(host.resources.displayMetrics.widthPixels - dp(28), dp(390)),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ),
            )
            setOnClickListener { dialog.dismiss() }
        }
        panel.setOnClickListener { /* prevent dismiss */ }
        dialog.setContentView(container)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.38f)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(0)
            DeyttUi.blurBehind(this, dp(24))
        }
        currentTrafficExplanation = dialog
        setMapTrafficActive(false)
        setStarfieldMotion(false)
        setMetricsPaused(true)
        selectTrafficFlow?.invoke(flow)
        dialog.setOnDismissListener {
            if (currentTrafficExplanation === dialog) {
                currentTrafficExplanation = null
                selectTrafficFlow?.invoke(null)
                setMetricsPaused(false)
                onDismissed()
            }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
