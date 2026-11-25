package space.deytt.connect

import java.net.URI
import java.net.URLDecoder

/** External links only prefill an import; SetupActivity requires a user tap. */
internal object SubscriptionImportLink {
    private const val MAX_LINK_LENGTH = 8192
    private const val ACTION_VIEW = "android.intent.action.VIEW"

    fun parse(action: String?, data: String?): String? {
        if (action != ACTION_VIEW || data == null || data.length > MAX_LINK_LENGTH) return null
        return runCatching {
            val uri = URI(data)
            if (uri.isOpaque || !uri.scheme.equals("deytt.connect", ignoreCase = true) ||
                !uri.host.equals("import", ignoreCase = true) || uri.userInfo != null ||
                uri.port != -1 || uri.path !in listOf("", "/") || uri.fragment != null
            ) return null
            val candidates = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val name = decode(pair.substringBefore('='))
                if (name == "url" || name == "subscription") decode(pair.substringAfter('=', "")) else null
            }
            // Ambiguous payloads must not select a different URL in another parser.
            if (candidates.size != 1) return null
            validateSubscriptionUrl(candidates.single())
        }.getOrNull()
    }

    fun validateSubscriptionUrl(raw: String?): String? {
        if (raw == null || raw.length > MAX_LINK_LENGTH) return null
        return runCatching {
            val value = raw.trim()
            val uri = URI(value)
            value.takeIf {
                !uri.isOpaque && uri.scheme.equals("https", ignoreCase = true) &&
                    SubscriptionHostPolicy.isAllowed(uri.host) && uri.userInfo == null &&
                    (uri.port == -1 || uri.port == 443) && uri.fragment == null
            }
        }.getOrNull()
    }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
}
