package space.deytt.connect

import android.content.Context
import org.amnezia.awg.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.StringReader

data class AwgProfile(
    val id: String,
    val version: String,
    val label: String,
    val shortLabel: String,
    val config: String,
)

class AwgProfileStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "subscription"))
    private val indexFile = File(directory, "awg-profiles.json")

    fun profiles(): List<AwgProfile> = synchronized(AtomicSubscriptionFile.lock) {
        try {
            AtomicSubscriptionFile.recover(directory)
        } catch (_: IOException) {
            return@synchronized emptyList()
        }
        if (indexFile.isFile) {
            // A damaged optional cache must not prevent importing the main subscription.
            val source = runCatching { JSONArray(indexFile.readText(Charsets.UTF_8)) }.getOrNull()
                ?: return@synchronized emptyList()
            return@synchronized (0 until source.length()).mapNotNull { position ->
                runCatching {
                    val item = source.getJSONObject(position)
                    val version = item.getString("version")
                    if (version !in SUPPORTED_VERSIONS) return@runCatching null
                    val id = item.getString("id")
                    require(id == "awg$version" || id.startsWith("awg$version:"))
                    val name = item.getString("file")
                    require(name.isNotBlank() && File(name).name == name && name.endsWith(".conf"))
                    val file = File(directory, name)
                    require(file.canonicalFile.parentFile == directory.canonicalFile)
                    val config = readFile(name) ?: return@runCatching null
                    validate(config)
                    AwgProfile(
                        id = id,
                        version = version,
                        label = item.getString("label"),
                        shortLabel = item.getString("shortLabel"),
                        config = config,
                    )
                }.getOrNull()
            }
        }
        listOfNotNull(
            readLegacyProfile("awg15.conf", "15"),
            readLegacyProfile("awg31.conf", "31"),
        )
    }

    fun read15(): String? = profiles().firstOrNull { it.version == "15" }?.config
    fun read31(): String? = profiles().firstOrNull { it.version == "31" }?.config
    fun read(id: String): String? = profiles().firstOrNull { it.id == id }?.config

    fun save(config31: String?) = save(
        listOfNotNull(config31?.let { AwgProfile("awg31", "31", "Основной", "AWG", it) }),
    )

    fun save(profiles: List<AwgProfile>) = saveWithCommit(profiles) {}

    internal fun saveWithCommit(profiles: List<AwgProfile>, commit: () -> Unit) = synchronized(AtomicSubscriptionFile.lock) {
        val supportedProfiles = profiles.filter { it.version in SUPPORTED_VERSIONS }
        supportedProfiles.forEach { validate(it.config) }
        directory.mkdirs()
        val generation = java.util.UUID.randomUUID().toString()
        val activeFiles = mutableSetOf<String>()
        val index = JSONArray()
        try {
            AtomicSubscriptionFile.transaction(directory) {
                supportedProfiles.forEachIndexed { position, profile ->
                    val name = "awg-$generation-${profile.version}-$position.conf"
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
                commit()
            }
        } catch (failure: Exception) {
            // A retained journal means recovery failed: keep every generation available.
            if (!File(directory, ".subscription-transaction.json").exists()) {
                activeFiles.forEach { File(directory, it).delete() }
            }
            throw failure
        }
        // Old generation is removed only after the durable transaction has committed.
        directory.listFiles { file ->
            file.name.startsWith("awg-") && file.extension == "conf" && file.name !in activeFiles
        }?.forEach(File::delete)
        File(directory, "awg15.conf").delete()
        File(directory, "awg31.conf").delete()
        Unit
    }

    private fun readFile(name: String): String? = File(directory, name)
        .takeIf { it.isFile && it.length() > 0L }
        ?.readText(Charsets.UTF_8)

    private fun readLegacyProfile(name: String, version: String): AwgProfile? = runCatching {
        readFile(name)?.let {
            validate(it)
            AwgProfile("awg$version", version, "основной", "awg", it)
        }
    }.getOrNull()

    private fun writeAtomic(name: String, content: String) =
        AtomicSubscriptionFile.write(File(directory, name), content)

    companion object {
        private val SUPPORTED_VERSIONS = setOf("15", "31")

        fun validate(content: String?) {
            val raw = content?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException(
                "Сервер вернул пустой профиль AmneziaWG"
            )
            require(raw.contains("[Interface]") && raw.contains("[Peer]")) {
                "Сервер вернул повреждённый профиль AmneziaWG"
            }
            try {
                val parsed = Config.parse(BufferedReader(StringReader(raw)))
                check(parsed.getInterface().getAddresses().isNotEmpty()) {
                    "В профиле AmneziaWG отсутствует адрес интерфейса"
                }
                check(parsed.getPeers().isNotEmpty()) {
                    "В профиле AmneziaWG отсутствует сервер"
                }
                check(parsed.getPeers().all { peer ->
                    peer.getEndpoint().isPresent && peer.getAllowedIps().isNotEmpty()
                }) {
                    "В профиле AmneziaWG отсутствует endpoint или маршрут"
                }
                check(parsed.getPeers().any { peer ->
                    peer.getAllowedIps().any { allowed -> allowed.getMask() == 0 }
                }) {
                    "В профиле AmneziaWG отсутствует маршрут по умолчанию"
                }
            } catch (error: Exception) {
                if (error is IllegalStateException) throw error
                throw IllegalArgumentException("Сервер вернул несовместимый профиль AmneziaWG", error)
            }
        }
    }
}
