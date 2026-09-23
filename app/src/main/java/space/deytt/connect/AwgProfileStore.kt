package space.deytt.connect

import android.content.Context
import org.amnezia.awg.config.Config
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

class AwgProfileStore(context: Context) {
    private val directory = File(context.filesDir, "subscription")

    fun read15(): String? = read("awg15.conf")
    fun read31(): String? = read("awg31.conf")

    fun save(config15: String?, config31: String?) {
        validate(config15)
        validate(config31)
        directory.mkdirs()
        writeOrDelete("awg15.conf", config15)
        writeOrDelete("awg31.conf", config31)
    }

    private fun read(name: String): String? = File(directory, name)
        .takeIf { it.isFile && it.length() > 0L }
        ?.readText(Charsets.UTF_8)

    private fun writeOrDelete(name: String, content: String?) {
        val target = File(directory, name)
        if (content.isNullOrBlank()) {
            target.delete()
            return
        }
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
