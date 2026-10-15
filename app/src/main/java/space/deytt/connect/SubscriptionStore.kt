package space.deytt.connect

import android.content.Context
import java.io.File
import java.io.IOException

class SubscriptionStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "subscription"))

    private val currentFile = File(directory, "current.json")
    private val previousFile = File(directory, "previous.json")

    fun readCurrent(): String? = readSafely(currentFile)

    fun readPrevious(): String? = readSafely(previousFile)

    private fun readSafely(file: File): String? = synchronized(AtomicSubscriptionFile.lock) {
        try {
            AtomicSubscriptionFile.recover(directory)
            readIfPresent(file)
        } catch (_: Exception) {
            // Keep the journal for recovery; never expose a partially restored generation.
            null
        }
    }

    fun saveValidated(content: String) = synchronized(AtomicSubscriptionFile.lock) {
        require(content.isNotBlank()) { "Сервер вернул пустую подписку" }
        AtomicSubscriptionFile.transaction(directory) {
            readIfPresent(currentFile)?.let { AtomicSubscriptionFile.writeEncrypted(previousFile, it) }
            AtomicSubscriptionFile.writeEncrypted(currentFile, content)
        }
    }

    internal fun commitValidated(content: String, awgStore: AwgProfileStore, profiles: List<AwgProfile>,
        commitOptional: () -> Unit = {}) {
        awgStore.saveWithCommit(profiles) {
            saveValidated(content)
            commitOptional()
        }
    }

    private fun readIfPresent(file: File): String? =
        AtomicSubscriptionFile.readDecrypted(file)
}
