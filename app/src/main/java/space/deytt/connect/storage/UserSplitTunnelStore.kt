package space.deytt.connect

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class UserSplitTunnelPreset(
    val id: String,
    val name: String,
    val mode: String,
    val domains: List<String>,
    val packages: List<String>,
)

class UserSplitTunnelStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "user_split_tunnel"
        private const val KEY_DOMAINS = "custom_domains"
        private const val KEY_PACKAGES = "custom_packages"
        private const val KEY_PRESETS = "presets"
        private const val KEY_ACTIVE_PRESET = "active_preset"
        const val MODE_BYPASS = "bypass"
        const val MODE_VPN_ONLY = "vpn_only"

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
            if (domain.startsWith(".")) domain = "*$domain"
            if (!DOMAIN_REGEX.matches(domain)) return null
            return domain
        }

        fun isValidPackage(packageName: String): Boolean = PACKAGE_REGEX.matches(packageName.trim())
    }

    private fun readLegacy(key: String, normalize: (String) -> String?): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { JSONArray(raw) }.getOrNull()?.let { array ->
            (0 until array.length()).mapNotNull { normalize(array.optString(it)) }.distinct().sorted()
        }.orEmpty()
    }

    @Synchronized
    fun readPresets(): List<UserSplitTunnelPreset> {
        val raw = prefs.getString(KEY_PRESETS, null)
        if (raw != null) runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = item.optString("name").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                val domains = item.optJSONArray("domains")?.let { values ->
                    (0 until values.length()).mapNotNull { normalizeDomain(values.optString(it)) }.distinct().sorted()
                }.orEmpty()
                val packages = item.optJSONArray("packages")?.let { values ->
                    (0 until values.length()).map { values.optString(it).trim() }.filter(::isValidPackage).distinct().sorted()
                }.orEmpty()
                UserSplitTunnelPreset(id, name, item.optString("mode").takeIf { it == MODE_VPN_ONLY } ?: MODE_BYPASS, domains, packages)
            }.distinctBy(UserSplitTunnelPreset::id).takeIf(List<UserSplitTunnelPreset>::isNotEmpty)
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return listOf(UserSplitTunnelPreset("default", "Основной", MODE_BYPASS,
            readLegacy(KEY_DOMAINS, ::normalizeDomain), readLegacy(KEY_PACKAGES) { it.trim().takeIf(::isValidPackage) }))
    }

    private fun active(presets: List<UserSplitTunnelPreset> = readPresets()) =
        presets.firstOrNull { it.id == prefs.getString(KEY_ACTIVE_PRESET, "default") } ?: presets.first()

    @Synchronized fun activePreset(): UserSplitTunnelPreset = active()
    @Synchronized fun readDomains(): List<String> = active().domains
    @Synchronized fun readPackages(): List<String> = active().packages
    @Synchronized fun readMode(): String = active().mode

    @Synchronized
    fun selectPreset(id: String): Boolean {
        if (readPresets().none { it.id == id }) return false
        prefs.edit().putString(KEY_ACTIVE_PRESET, id).apply()
        persist(readPresets())
        return true
    }

    @Synchronized
    fun createPreset(name: String): Boolean {
        val clean = name.trim().take(32)
        if (clean.isBlank() || readPresets().any { it.name.equals(clean, ignoreCase = true) }) return false
        val presets = readPresets() + UserSplitTunnelPreset(UUID.randomUUID().toString(), clean, MODE_BYPASS, emptyList(), emptyList())
        prefs.edit().putString(KEY_ACTIVE_PRESET, presets.last().id).apply()
        persist(presets)
        return true
    }

    @Synchronized
    fun deleteActivePreset(): Boolean {
        val presets = readPresets()
        val current = active(presets)
        if (current.id == "default" || presets.size <= 1) return false
        val remaining = presets.filterNot { it.id == current.id }
        prefs.edit().putString(KEY_ACTIVE_PRESET, remaining.first().id).apply()
        persist(remaining)
        return true
    }

    @Synchronized
    fun setMode(mode: String) {
        updateActive { it.copy(mode = if (mode == MODE_VPN_ONLY) MODE_VPN_ONLY else MODE_BYPASS) }
    }

    @Synchronized
    fun addDomain(rawDomain: String): Boolean {
        val normalized = normalizeDomain(rawDomain) ?: return false
        updateActive { it.copy(domains = (it.domains + normalized).distinct().sorted()) }
        return true
    }

    @Synchronized
    fun removeDomain(domain: String): Boolean {
        val normalized = normalizeDomain(domain) ?: domain.trim().lowercase(Locale.ROOT)
        val current = active().domains
        if (normalized !in current) return false
        updateActive { it.copy(domains = it.domains - normalized) }
        return true
    }

    @Synchronized
    fun addPackage(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (!isValidPackage(pkg)) return false
        updateActive { it.copy(packages = (it.packages + pkg).distinct().sorted()) }
        return true
    }

    @Synchronized
    fun removePackage(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg !in active().packages) return false
        updateActive { it.copy(packages = it.packages - pkg) }
        return true
    }

    private fun updateActive(change: (UserSplitTunnelPreset) -> UserSplitTunnelPreset) {
        val presets = readPresets()
        val current = active(presets)
        persist(presets.map { if (it.id == current.id) change(it) else it })
    }

    private fun persist(presets: List<UserSplitTunnelPreset>) {
        val selected = active(presets)
        val json = JSONArray()
        presets.forEach { preset ->
            json.put(JSONObject().put("id", preset.id).put("name", preset.name).put("mode", preset.mode)
                .put("domains", JSONArray(preset.domains)).put("packages", JSONArray(preset.packages)))
        }
        prefs.edit().putString(KEY_PRESETS, json.toString()).putString(KEY_ACTIVE_PRESET, selected.id)
            .putString(KEY_DOMAINS, JSONArray(selected.domains).toString())
            .putString(KEY_PACKAGES, JSONArray(selected.packages).toString()).apply()
    }

    @Synchronized
    fun clearAll() {
        prefs.edit().remove(KEY_DOMAINS).remove(KEY_PACKAGES).remove(KEY_PRESETS).remove(KEY_ACTIVE_PRESET).apply()
    }
}
