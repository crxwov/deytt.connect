package space.deytt.connect

import android.content.Context
import androidx.core.content.edit

data class SubscriptionMetadata(
    val title: String = "deytt",
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    val expiresAtSeconds: Long? = null,
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes

    companion object {
        fun parse(title: String?, userInfo: String?): SubscriptionMetadata {
            val fields = userInfo.orEmpty().split(';').mapNotNull { part ->
                val pair = part.trim().split('=', limit = 2)
                if (pair.size == 2) pair[0].trim().lowercase() to pair[1].trim() else null
            }.toMap()
            return SubscriptionMetadata(
                title = title?.trim()?.takeIf(String::isNotBlank) ?: "deytt",
                uploadBytes = fields["upload"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                downloadBytes = fields["download"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                totalBytes = fields["total"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                expiresAtSeconds = fields["expire"]?.toLongOrNull()?.takeIf { it > 0 },
            )
        }
    }
}

class SubscriptionMetadataStore(context: Context) {
    private val preferences = context.getSharedPreferences("subscription_metadata", Context.MODE_PRIVATE)

    fun save(metadata: SubscriptionMetadata) {
        preferences.edit {
            putString("title", metadata.title)
            putLong("upload", metadata.uploadBytes)
            putLong("download", metadata.downloadBytes)
            putLong("total", metadata.totalBytes)
            metadata.expiresAtSeconds?.let { putLong("expire", it) } ?: remove("expire")
        }
    }

    fun read(): SubscriptionMetadata = SubscriptionMetadata(
        title = preferences.getString("title", "deytt") ?: "deytt",
        uploadBytes = preferences.getLong("upload", 0),
        downloadBytes = preferences.getLong("download", 0),
        totalBytes = preferences.getLong("total", 0),
        expiresAtSeconds = preferences.takeIf { it.contains("expire") }?.getLong("expire", 0),
    )
}
