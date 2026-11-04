package space.deytt.connect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.Executors
import space.deytt.connect.AppLanguage.uiCopy

internal class TelegramIdentityBridge(
    private val host: MainActivity,
    private val onAvatarUpdated: ((Bitmap?) -> Unit)? = null,
    private val onAccountViewsRefreshed: (() -> Unit)? = null,
) {
    var telegramAccountName: TextView? = null
    var telegramAvatarFallback: TextView? = null
    var telegramAvatarImage: ImageView? = null

    private val accountExecutor = Executors.newSingleThreadExecutor()
    private var accountGeneration = 0

    fun bindViews(
        accountName: TextView?,
        avatarFallback: TextView?,
        avatarImage: ImageView?,
    ) {
        this.telegramAccountName = accountName
        this.telegramAvatarFallback = avatarFallback
        this.telegramAvatarImage = avatarImage
    }

    fun updateTelegramIdentity(username: String?, avatar: Bitmap?) {
        val account = username?.trim()?.removePrefix("@")?.takeIf(String::isNotBlank)
        val sessionConnected = TelegramSessionStore.read(host) != null
        val defaultText = host.uiCopy(if (sessionConnected) "Telegram подключён" else "Войти через Telegram")
        telegramAccountName?.text = account?.let { "./$it" } ?: defaultText
        telegramAvatarFallback?.apply {
            text = account?.take(1)?.uppercase() ?: "•"
            visibility = if (avatar == null) View.VISIBLE else View.GONE
        }
        telegramAvatarImage?.apply {
            if (avatar == null) {
                setImageDrawable(null)
                visibility = View.GONE
                alpha = 1f
            } else {
                setImageBitmap(avatar)
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(190L).start()
            }
        }
        onAvatarUpdated?.invoke(avatar)
    }

    fun updateTelegramUsername(username: String?) {
        val account = username?.trim()?.removePrefix("@")?.takeIf(String::isNotBlank)
        val sessionConnected = TelegramSessionStore.read(host) != null
        val defaultText = host.uiCopy(if (sessionConnected) "Telegram подключён" else "Войти через Telegram")
        telegramAccountName?.text = account?.let { "./$it" } ?: defaultText
        if (telegramAvatarImage?.drawable == null) {
            telegramAvatarFallback?.text = account?.take(1)?.uppercase() ?: "•"
            telegramAvatarFallback?.visibility = View.VISIBLE
        }
    }

    fun refreshTelegramAccount() {
        if (host.isFinishing || host.isDestroyed) return
        val token = TelegramSessionStore.read(host)
        TelegramIdentityCache.bind(token)
        updateTelegramIdentity(TelegramIdentityCache.username, TelegramIdentityCache.avatar)
        if (token == null) {
            accountGeneration++
            return
        }
        val generation = ++accountGeneration
        accountExecutor.execute {
            // A failed photo request must never erase a successfully loaded account.
            val profileResult = runCatching { TelegramPairingClient.profile(token) }
            val imageResult = runCatching { TelegramPairingClient.avatar(token)?.let(::decodeTelegramAvatar) }
            host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed || generation != accountGeneration ||
                    TelegramSessionStore.read(host) != token) return@runOnUiThread
                val failure = profileResult.exceptionOrNull()
                if ((failure as? TelegramPairingException)?.code == "session_expired") {
                    TelegramSessionStore.clear(host)
                    TelegramIdentityCache.bind(null)
                    updateTelegramIdentity(null, null)
                    refreshAccountViews()
                } else {
                    val profile = profileResult.getOrNull()
                    val name = profile?.optString("username")?.ifBlank { profile.optString("first_name") }
                    TelegramIdentityCache.update(token, name, imageResult.getOrNull())
                    updateTelegramIdentity(TelegramIdentityCache.username, TelegramIdentityCache.avatar)
                }
            }
        }
    }

    fun refreshAccountViews() {
        if (onAccountViewsRefreshed != null) {
            onAccountViewsRefreshed.invoke()
        } else {
            runCatching { host.refreshAccountViews() }
        }
    }

    fun decodeTelegramAvatar(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 144 || bounds.outHeight / sampleSize > 144) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun close() {
        accountGeneration++
        accountExecutor.shutdownNow()
    }
}
