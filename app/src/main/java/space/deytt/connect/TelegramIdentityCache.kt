package space.deytt.connect

import android.graphics.Bitmap

/** Keeps identity across activity recreation without persisting photos or credentials. */
internal object TelegramIdentityCache {
    private var session: String? = null
    var username: String? = null
        private set
    var avatar: Bitmap? = null
        private set

    @Synchronized fun bind(token: String?) {
        if (session == token) return
        session = token
        username = null
        avatar = null
    }

    @Synchronized fun update(token: String, name: String?, image: Bitmap?) {
        bind(token)
        if (!name.isNullOrBlank()) username = name
        if (image != null) avatar = image
    }
}
