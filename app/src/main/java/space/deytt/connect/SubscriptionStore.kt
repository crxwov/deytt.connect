package space.deytt.connect

import android.content.Context
import java.io.File

class SubscriptionStore(context: Context) {
    private val directory = File(context.filesDir, "subscription")
    private val currentFile = File(directory, "current.json")
    private val previousFile = File(directory, "previous.json")

    fun readCurrent(): String? = readIfPresent(currentFile)

    fun readPrevious(): String? = readIfPresent(previousFile)

    fun saveValidated(content: String) {
        directory.mkdirs()
        if (currentFile.exists()) {
            currentFile.copyTo(previousFile, overwrite = true)
        }
        val temporary = File(directory, "current.json.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(currentFile)) {
            temporary.copyTo(currentFile, overwrite = true)
            check(temporary.delete()) { "Не удалось удалить временную конфигурацию" }
        }
    }

    private fun readIfPresent(file: File): String? =
        file.takeIf { it.isFile && it.length() > 0L }?.readText(Charsets.UTF_8)
}

