package space.deytt.connect

import android.content.Context
import org.json.JSONArray
import java.util.Locale

class UserSplitTunnelStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "user_split_tunnel"
        private const val KEY_DOMAINS = "custom_domains"
        private const val KEY_PACKAGES = "custom_packages"

        private val DOMAIN_REGEX = Regex(
            """^(\*\.)?([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z0-9-]{2,}$"""
        )
        private val PACKAGE_REGEX = Regex(
            """^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$"""
        )

        fun normalizeDomain(raw: String): String? {
            var domain = raw.trim().lowercase(Locale.ROOT)
            if (domain.startsWith("http://")) {
                domain = domain.removePrefix("http://")
            } else if (domain.startsWith("https://")) {
                domain = domain.removePrefix("https://")
            }
            domain = domain.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').trim()
            if (domain.isBlank()) return null

            // Support both *.example.com and .example.com
            if (domain.startsWith(".")) {
                domain = "*$domain"
            }

            if (!DOMAIN_REGEX.matches(domain)) return null
            return domain
        }

        fun isValidPackage(packageName: String): Boolean {
            return PACKAGE_REGEX.matches(packageName.trim())
        }
    }

    @Synchronized
    fun readDomains(): List<String> {
        val raw = prefs.getString(KEY_DOMAINS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { index -> normalizeDomain(array.optString(index)) }
                .distinct()
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addDomain(rawDomain: String): Boolean {
        val normalized = normalizeDomain(rawDomain) ?: return false
        val current = readDomains().toMutableList()
        if (current.contains(normalized)) return true
        current.add(normalized)
        current.sort()
        val json = JSONArray(current).toString()
        prefs.edit().putString(KEY_DOMAINS, json).apply()
        return true
    }

    @Synchronized
    fun removeDomain(domain: String): Boolean {
        val normalized = normalizeDomain(domain) ?: domain.trim().lowercase(Locale.ROOT)
        val current = readDomains().toMutableList()
        val removed = current.remove(normalized)
        if (removed) {
            val json = JSONArray(current).toString()
            prefs.edit().putString(KEY_DOMAINS, json).apply()
        }
        return removed
    }

    @Synchronized
    fun readPackages(): List<String> {
        val raw = prefs.getString(KEY_PACKAGES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length())
                .map { array.optString(it).trim() }
                .filter { isValidPackage(it) }
                .distinct()
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addPackage(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (!isValidPackage(pkg)) return false
        val current = readPackages().toMutableList()
        if (current.contains(pkg)) return true
        current.add(pkg)
        current.sort()
        val json = JSONArray(current).toString()
        prefs.edit().putString(KEY_PACKAGES, json).apply()
        return true
    }

    @Synchronized
    fun removePackage(packageName: String): Boolean {
        val pkg = packageName.trim()
        val current = readPackages().toMutableList()
        val removed = current.remove(pkg)
        if (removed) {
            val json = JSONArray(current).toString()
            prefs.edit().putString(KEY_PACKAGES, json).apply()
        }
        return removed
    }

    @Synchronized
    fun clearAll() {
        prefs.edit().remove(KEY_DOMAINS).remove(KEY_PACKAGES).apply()
    }
}
