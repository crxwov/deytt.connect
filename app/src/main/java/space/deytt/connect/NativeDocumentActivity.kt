package space.deytt.connect

import android.app.Activity
import android.os.Bundle
import android.text.Html
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.text

/** Native selectable document reader; no WebView, JavaScript, cookies or site navigation. */
class NativeDocumentActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var content: TextView
    private lateinit var status: TextView
    private var section = "privacy"
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = intent.getStringExtra(EXTRA_SECTION)?.takeIf { it == "privacy" || it == "terms" } ?: "privacy"
        val root = screen()
        val gutter = root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(gutter + bars.left, bars.top, gutter + bars.right, dp(18) + bars.bottom)
            insets
        }
        val title = if (section == "privacy") copy("Конфиденциальность", "Privacy") else copy("Условия использования", "Terms of use")
        root.addView(header("deytt.space · " + copy("документы", "documents"), title, back = true))
        status = text("", 12f, DeyttUi.MUTED).apply {
            setPadding(0, dp(10), 0, dp(12))
            minHeight = dp(48)
            setOnClickListener { loadDocument() }
        }
        root.addView(status)
        content = text("", 15f, DeyttUi.TEXT).apply {
            setLineSpacing(dp(5).toFloat(), 1.0f)
            setTextIsSelectable(true)
            linksClickable = true
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(DeyttUi.MINT)
        }
        root.addView(ScrollView(this).apply { addView(content); overScrollMode = View.OVER_SCROLL_NEVER }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadDocument()
    }

    private fun loadDocument() {
        if (loading || isFinishing || isDestroyed) return
        loading = true
        status.text = copy("Загружаем актуальный документ…", "Loading the current document…")
        status.setTextColor(DeyttUi.MUTED)
        executor.execute {
            val result = runCatching {
                val connection = URL("https://deytt.space/info/").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 12_000
                    connection.instanceFollowRedirects = false
                    check(connection.responseCode == 200) { "document_unavailable" }
                    val body = connection.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            check(out.size() + count <= 512 * 1024) { "document_too_large" }
                            out.write(chunk, 0, count)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                    LegalDocument.section(body, section)
                } finally { connection.disconnect() }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                result.onSuccess { html ->
                    val rendered = SpannableString(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY))
                    // The canonical page links to external policies. Only explicit HTTPS taps are allowed.
                    rendered.getSpans(0, rendered.length, URLSpan::class.java).forEach { span ->
                        val uri = android.net.Uri.parse(span.url)
                        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) rendered.removeSpan(span)
                    }
                    content.text = rendered
                    status.text = copy("Официальный текст · deytt.space", "Official text · deytt.space")
                }.onFailure {
                    status.text = copy("Не удалось загрузить документ. Нажмите, чтобы повторить.", "Document could not be loaded. Tap to retry.")
                    status.setTextColor(DeyttUi.CORAL)
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun copy(ru: String, en: String): String = if (AppLanguage.current(this) == AppLanguage.EN) en else ru

    companion object { const val EXTRA_SECTION = "document_section" }
}
