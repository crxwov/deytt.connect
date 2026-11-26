package space.deytt.connect

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.CORAL
import space.deytt.connect.DeyttUi.LINE
import space.deytt.connect.DeyttUi.MINT
import space.deytt.connect.DeyttUi.SKY
import space.deytt.connect.DeyttUi.SURFACE
import space.deytt.connect.DeyttUi.TEXT
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.text

internal class InAppUpdateFlow(
    private val host: MainActivity,
    private val copy: (String, String) -> String,
    private val openUrl: (String) -> Unit,
) {
    companion object {
        private const val UPDATE_CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L
        private const val KEY_LAST_UPDATE_CHECK = "last_official_update_check_ms"
        private const val KEY_LAST_OFFERED_UPDATE = "last_offered_official_update"
    }

    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val updateHandler = Handler(Looper.getMainLooper())
    private var latestRelease: ReleaseInfo? = null
    private var downloadedUpdate: File? = null
    private var updateCheckInFlight = false
    private var updateDownloadInFlight = false
    private var closed = false
    private var updateStatus: TextView? = null
    private var updateButton: TextView? = null
    private var updateBanner: LinearLayout? = null
    private var updateBannerText: TextView? = null
    private var updateBannerProgress: ProgressBar? = null
    var updateStatusText: String = "Готово проверить официальный релиз."
        private set

    private val updateCycle = object : Runnable {
        override fun run() {
            checkForUpdate(force = false)
            updateHandler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS)
        }
    }

    fun bindSettingsViews(status: TextView, button: TextView) {
        updateStatus = status
        updateButton = button
        status.text = host.uiCopy(updateStatusText)
    }

    fun createUpdateBanner(): View {
        val label = host.text("", 12f, TEXT).apply { setPadding(host.dp(18), host.dp(10), host.dp(18), host.dp(8)) }
        val progress = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(SKY)
            progressBackgroundTintList = ColorStateList.valueOf(LINE)
        }
        updateBannerText = label
        updateBannerProgress = progress
        return LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            visibility = View.GONE
            addView(label)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(3)))
            setOnClickListener { if (!updateDownloadInFlight) updateAction() }
            updateBanner = this
        }
    }

    private fun showUpdateProgress(label: String, percent: Int? = null) {
        updateBanner?.visibility = View.VISIBLE
        updateBannerText?.text = label
        updateBannerProgress?.isIndeterminate = percent == null
        percent?.let { updateBannerProgress?.progress = it }
    }

    fun checkForUpdate(force: Boolean = true) {
        if (updateCheckInFlight || updateDownloadInFlight || closed) return
        val preferences = host.getSharedPreferences("profile_settings", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        if (!force && now - lastCheck < UPDATE_CHECK_INTERVAL_MS) return
        updateCheckInFlight = true
        updateButton?.isEnabled = false
        updateButton?.alpha = .68f
        updateStatus?.apply {
            text = host.uiCopy("Проверяем официальный релиз…")
            setTextColor(SKY)
        }
        updateStatusText = "Проверяем официальный релиз…"
        updateExecutor.execute {
            runCatching { UpdateChecker.latest() }
                .onSuccess { release -> host.runOnUiThread {
                    if (closed || host.isFinishing || host.isDestroyed) return@runOnUiThread
                    updateCheckInFlight = false
                    preferences.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                    updateButton?.isEnabled = true
                    updateButton?.alpha = 1f
                    if (!ReleaseVersion.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                        latestRelease = null
                        downloadedUpdate = null
                        updateStatusText = "Установлена последняя версия."
                        updateButton?.text = host.uiCopy("Проверить снова")
                    } else {
                        latestRelease = release
                        val apkSize = release.apkSize?.let { " · ${formatUpdateSize(it)}" }.orEmpty()
                        updateStatusText = if (release.apkUrl != null) {
                            "Доступна ${release.tag}$apkSize. APK будет проверен по подписи перед установкой."
                        } else "Доступна ${release.tag}. APK пока нет; доступна только страница релиза."
                        updateButton?.text = host.uiCopy(if (release.apkUrl != null) "Скачать обновление" else "Открыть официальный релиз")
                        offerUpdateIfNew(release)
                    }
                    updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(MINT) }
                } }
                .onFailure {
                    host.runOnUiThread {
                        if (closed || host.isFinishing || host.isDestroyed) return@runOnUiThread
                        updateCheckInFlight = false
                        preferences.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                        latestRelease = null
                        updateStatusText = "Не удалось проверить официальный релиз. Повторите попытку позже."
                        updateButton?.apply { isEnabled = true; alpha = 1f; text = host.uiCopy("Проверить обновления") }
                        updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(CORAL) }
                    }
                }
        }
    }

    fun updateAction() {
        val cached = downloadedUpdate?.takeIf { it.isFile }
        if (cached != null) {
            offerInstall(cached)
            return
        }
        val release = latestRelease
        if (release == null) checkForUpdate(force = true)
        else if (release.apkUrl != null) askDownload(release)
        else openLatest()
    }

    private fun offerUpdateIfNew(release: ReleaseInfo) {
        val preferences = host.getSharedPreferences("profile_settings", Context.MODE_PRIVATE)
        if (preferences.getString(KEY_LAST_OFFERED_UPDATE, null) == release.tag || release.apkUrl == null) return
        preferences.edit().putString(KEY_LAST_OFFERED_UPDATE, release.tag).apply()
        AppDialog.Builder(host)
            .setTitle(host.uiCopy("Доступно обновление ${release.tag}"))
            .setMessage(host.uiCopy("Скачать официальный APK сейчас? Позже его можно будет открыть в настройках."))
            .setNegativeButton(host.uiCopy("Позже"), null)
            .setNeutralButton(host.uiCopy("Страница релиза")) { _, _ -> openLatest() }
            .setPositiveButton(host.uiCopy("Обновить сейчас")) { _, _ -> downloadUpdate(release) }
            .show()
    }

    private fun askDownload(release: ReleaseInfo) {
        if (release.apkUrl == null) {
            openLatest()
            return
        }
        val size = release.apkSize?.let { " · ${formatUpdateSize(it)}" }.orEmpty()
        AppDialog.Builder(host)
            .setTitle(host.uiCopy("Скачать версию ${release.tag}?"))
            .setMessage(host.uiCopy("Файл с GitHub будет проверен по имени пакета и подписи приложения$size."))
            .setNegativeButton(host.uiCopy("Позже"), null)
            .setNeutralButton(host.uiCopy("Страница релиза")) { _, _ -> openLatest() }
            .setPositiveButton(host.uiCopy("Скачать")) { _, _ -> downloadUpdate(release) }
            .show()
    }

    private fun downloadUpdate(release: ReleaseInfo) {
        if (updateDownloadInFlight || closed) return
        updateDownloadInFlight = true
        showUpdateProgress(copy("Скачиваем обновление…", "Downloading update…"), 0)
        updateButton?.apply { isEnabled = false; alpha = .68f; text = host.uiCopy("Скачиваем…") }
        updateStatus?.apply { text = host.uiCopy("Скачиваем и проверяем официальный APK…"); setTextColor(SKY) }
        updateExecutor.execute {
            runCatching {
                UpdateChecker.downloadAndVerify(host, release) { progress ->
                    host.runOnUiThread {
                        if (!host.isFinishing && !host.isDestroyed) {
                            updateStatusText = copy("Скачиваем обновление · $progress%", "Downloading update · $progress%")
                            updateStatus?.text = updateStatusText
                            showUpdateProgress(updateStatusText, progress)
                        }
                    }
                }
            }.onSuccess { apk -> host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                updateDownloadInFlight = false
                downloadedUpdate = apk
                showUpdateProgress(copy("Обновление готово · нажмите, чтобы установить", "Update ready · tap to install"), 100)
                updateButton?.apply { isEnabled = true; alpha = 1f; text = host.uiCopy("Установить обновление") }
                updateStatusText = "APK проверен. Установка начнётся только после подтверждения Android."
                updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(MINT) }
                offerInstall(apk)
            } }.onFailure { failure -> host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                updateDownloadInFlight = false
                showUpdateProgress(copy("Обновление не загружено · нажмите для повтора", "Update failed · tap to retry"), 0)
                updateButton?.apply { isEnabled = true; alpha = 1f; text = host.uiCopy("Повторить загрузку") }
                updateStatusText = when (failure.message) {
                    "Downloaded APK belongs to another app" -> "APK принадлежит другому приложению. Установка отменена."
                    "Downloaded APK signing certificate does not match" -> "Подпись APK не совпадает с установленной версией. Установка отменена."
                    else -> "Не удалось загрузить или проверить APK. Попробуйте позже."
                }
                updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(CORAL) }
            } }
        }
    }

    private fun offerInstall(apk: File) {
        AppDialog.Builder(host)
            .setTitle(host.uiCopy("Обновление готово"))
            .setMessage(host.uiCopy(copy("Android попросит подтвердить установку и закроет текущую версию. После установки нажмите «Открыть» или запустите приложение позже.", "Android will ask to install and close the current version. After installation, tap Open or launch the app later.")))
            .setNegativeButton(host.uiCopy("Позже"), null)
            .setPositiveButton(host.uiCopy("Установить сейчас")) { _, _ -> launchInstaller(apk) }
            .show()
    }

    private fun launchInstaller(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !host.packageManager.canRequestPackageInstalls()) {
            AppDialog.Builder(host)
                .setTitle(host.uiCopy("Разрешите установку обновлений"))
                .setMessage(host.uiCopy("Android откроет настройки разрешения для этого приложения. Вернитесь и нажмите «Установить обновление»."))
                .setNegativeButton(host.uiCopy("Позже"), null)
                .setPositiveButton(host.uiCopy("Открыть настройки")) { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${host.packageName}"))
                    runCatching { host.startActivity(intent) }
                }
                .show()
            return
        }
        runCatching { UpdateChecker.launchInstaller(host, apk) }
            .onFailure {
                updateStatusText = "Не удалось открыть системный установщик Android."
                updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(CORAL) }
            }
    }

    fun openLatest() {
        latestRelease?.pageUrl?.takeIf(ReleaseUrlPolicy::isOfficialPage)?.let(openUrl)
            ?: updateStatus?.apply { text = host.uiCopy("Ссылка на релиз не прошла проверку.") }
    }

    private fun formatUpdateSize(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)

    fun startForegroundUpdates() {
        updateHandler.removeCallbacks(updateCycle)
        checkForUpdate(force = false)
        updateHandler.postDelayed(updateCycle, UPDATE_CHECK_INTERVAL_MS)
    }

    fun stopForegroundUpdates() {
        updateHandler.removeCallbacks(updateCycle)
    }

    fun close() {
        closed = true
        updateHandler.removeCallbacks(updateCycle)
        updateExecutor.shutdownNow()
    }
}
