package space.deytt.connect

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import org.json.JSONObject

/** All subscription store instances share this lock, including index cleanup and readers. */
internal object AtomicSubscriptionFile {
    val lock = Any()
    private const val JOURNAL = ".subscription-transaction.json"
    private val legacySnapshotNames = listOf("current.json", "previous.json", "awg-profiles.json")
    private val snapshotNames = legacySnapshotNames + OpenFluxProfileStore.FILE_NAME
    private val activeDirectories = ThreadLocal.withInitial { mutableSetOf<String>() }

    /** Undo journal is published before any live file changes; deletion is the commit point. */
    fun transaction(directory: File, block: () -> Unit) = synchronized(lock) {
        val key = directory.canonicalPath
        if (key in activeDirectories.get()) {
            block()
            return@synchronized
        }
        recover(directory)
        val snapshot = JSONObject()
        snapshotNames.forEach { name ->
            val file = File(directory, name)
            if (file.exists() && !file.isFile) throw IOException("Хранилище подписки повреждено")
            snapshot.put(name, if (file.isFile) file.readText(Charsets.UTF_8) else JSONObject.NULL)
        }
        val journal = File(directory, JOURNAL)
        writeEncrypted(journal, snapshot.toString())
        activeDirectories.get().add(key)
        try {
            block()
            if (!journal.delete()) throw IOException("Не удалось завершить сохранение подписки")
        } catch (failure: Exception) {
            activeDirectories.get().remove(key)
            try { recover(directory) } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        } finally {
            activeDirectories.get().remove(key)
        }
    }

    fun recover(directory: File) = synchronized(lock) {
        if (directory.canonicalPath in activeDirectories.get()) return@synchronized
        val journal = File(directory, JOURNAL)
        if (!journal.exists()) return@synchronized
        val snapshot = try {
            JSONObject(LocalSecretCipher.decryptOrPlaintext(journal.readText(Charsets.UTF_8)))
        } catch (error: Exception) {
            throw IOException("Не удалось восстановить подписку", error)
        }
        // Validate the entire journal before restoring anything; never guess through corruption.
        if (legacySnapshotNames.any { !snapshot.has(it) } ||
            snapshotNames.any { snapshot.has(it) && !snapshot.isNull(it) && snapshot.opt(it) !is String }) {
            throw IOException("Журнал подписки повреждён")
        }
        snapshotNames.forEach { name ->
            val file = File(directory, name)
            if (!snapshot.has(name) || snapshot.isNull(name)) {
                if (file.exists() && !file.delete()) throw IOException("Не удалось восстановить подписку")
            } else write(file, snapshot.getString(name))
        }
        // Recovery can be interrupted and repeated safely while the journal still exists.
        if (!journal.delete()) throw IOException("Не удалось завершить восстановление подписки")
    }

    fun writeEncrypted(target: File, content: String) =
        write(target, LocalSecretCipher.encrypt(content))

    fun readDecrypted(target: File): String? =
        target.takeIf { it.isFile && it.length() > 0L }
            ?.readText(Charsets.UTF_8)
            ?.let(LocalSecretCipher::decryptOrPlaintext)

    fun write(target: File, content: String) = write(target) {
        it.write(content.toByteArray(Charsets.UTF_8))
    }

    internal fun write(target: File, writer: (OutputStream) -> Unit) = synchronized(lock) {
        val directory = target.absoluteFile.parentFile!!
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Не удалось создать хранилище подписки")
        }
        val temporary = File.createTempFile(".subscription-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                writer(stream)
                stream.fd.sync()
            }
            // Same-directory rename replaces atomically on Android. Never truncate the live file.
            if (!temporary.renameTo(target)) {
                // Android/Linux rename replaces atomically; Windows JVMs require
                // the explicit replace operation for the same storage checks.
                if (!System.getProperty("os.name", "").startsWith("Windows")) {
                    throw IOException("Не удалось сохранить подписку")
                }
                WindowsAtomicReplace.move(temporary, target)
            }
        } finally {
            temporary.delete()
        }
    }

    /** Loaded only on a Windows JVM, never on Android API 24/25. */
    @android.annotation.TargetApi(26)
    private object WindowsAtomicReplace {
        fun move(source: File, target: File) {
            java.nio.file.Files.move(source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
