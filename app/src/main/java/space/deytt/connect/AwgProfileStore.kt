package space.deytt.connect

import android.content.Context
import org.amnezia.awg.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

data class AwgProfile(
    val id: String,
    val version: String,
    val label: String,
    val shortLabel: String,
    val config: String,
)

class AwgProfileStore(context: Context) {
    private val directory = File(context.filesDir, "subscription")
    private val indexFile = File(directory, "awg-profiles.json")

    fun profiles(): List<AwgProfile> {
        if (indexFile.isFile) {
            return runCatching {
                val source = JSONArray(indexFile.readText(Charsets.UTF_8))
                buildList {
                    for (index in 0 until source.length()) {
                        val item = source.getJSONObject(index)
                        val file = File(directory, item.getString("file"))
                        if (file.isFile) add(
                            AwgProfile(
                                id = item.getString("id"),
                                version = item.getString("version"),
                                label = item.getString("label"),
                                shortLabel = item.getString("shortLabel"),
                                config = file.readText(Charsets.UTF_8),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
        return listOfNotNull(
            readFile("awg15.conf")?.let { AwgProfile("awg15", "15", "Основной", "AWG", it) },
            readFile("awg31.conf")?.let { AwgProfile("awg31", "31", "Основной", "AWG", it) },
        )
    }

    fun read15(): String? = profiles().firstOrNull { it.version == "15" }?.config
    fun read31(): String? = profiles().firstOrNull { it.version == "31" }?.config
    fun read(id: String): String? = profiles().firstOrNull { it.id == id }?.config

    fun save(config15: String?, config31: String?) = save(
        listOfNotNull(
            config15?.let { AwgProfile("awg15", "15", "Основной", "AWG", it) },
            config31?.let { AwgProfile("awg31", "31", "Основной", "AWG", it) },
        ),
    )

    fun save(profiles: List<AwgProfile>) {
        profiles.forEach { validate(it.config) }
        directory.mkdirs()
        val generation = java.lang.Long.toUnsignedString(System.nanoTime(), 36)
        val activeFiles = mutableSetOf<String>()
        val index = JSONArray()
        profiles.forEachIndexed { position, profile ->
            val safeId = profile.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val name = "awg-$generation-${profile.version}-$safeId-$position.conf"
            writeAtomic(name, profile.config)
            activeFiles += name
            index.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("version", profile.version)
                    .put("label", profile.label)
                    .put("shortLabel", profile.shortLabel)
                    .put("file", name),
            )
        }
        writeAtomic(indexFile.name, index.toString())
        directory.listFiles { file ->
            file.name.startsWith("awg-") && file.extension == "conf" && file.name !in activeFiles
        }?.forEach(File::delete)
        File(directory, "awg15.conf").delete()
        File(directory, "awg31.conf").delete()
    }

    private fun readFile(name: String): String? = File(directory, name)
        .takeIf { it.isFile && it.length() > 0L }
        ?.readText(Charsets.UTF_8)

    private fun writeAtomic(name: String, content: String) {
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    companion object {
        fun validate(content: String?) {
            if (content.isNullOrBlank()) return
            require(content.contains("[Interface]") && content.contains("[Peer]")) {
                "Сервер вернул повреждённый профиль AmneziaWG"
            }
            try {
                Config.parse(BufferedReader(StringReader(content)))
            } catch (error: Exception) {
                throw IllegalArgumentException("Сервер вернул несовместимый профиль AmneziaWG", error)
            }
        }
    }
}
