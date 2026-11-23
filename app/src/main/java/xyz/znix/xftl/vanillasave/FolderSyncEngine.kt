package xyz.znix.xftl.vanillasave

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

/** Content-based, two-way exchange of a vanilla save. Call from one IO thread. */
class FolderSyncEngine(private val journal: Path, private val folderId: String, private val folder: Folder) {
    interface Folder {
        fun read(): ByteArray?
        /** Must recheck [expected] immediately before replacing/deleting the file. */
        fun replace(bytes: ByteArray?, expected: String)
    }

    sealed class Result {
        object Unchanged : Result()
        object Published : Result()
        data class Incoming(val bytes: ByteArray, val hash: String, val conflict: Boolean) : Result()
        data class Removed(val conflict: Boolean) : Result()
    }

    private var baseline: String? = null
    private var pending: ByteArray? = null
    private var ended = false
    val phoneRunEnded: Boolean get() = ended

    init {
        if (Files.exists(journal)) {
            val state = Properties().apply { Files.newInputStream(journal).use { load(it) } }
            if (state.getProperty("folder") == folderId) {
                baseline = state.getProperty("baseline")
                pending = state.getProperty("pending")?.let { Base64.getDecoder().decode(it) }
                ended = state.getProperty("ended") == "true"
            }
        }
    }

    fun saved(bytes: ByteArray) {
        pending = bytes.copyOf()
        ended = false
        persist()
    }

    fun runEnded() {
        pending = null
        ended = true
        persist()
    }

    fun reconcile(): Result {
        val remote = folder.read()
        val hash = hash(remote)
        if (ended && remote == null) {
            acknowledge(MISSING)
            return Result.Unchanged
        }
        // A crash after publishing but before journalling is harmless.
        if (pending != null && hash == hash(pending)) {
            acknowledge(hash)
            return Result.Unchanged
        }
        if (baseline == null && remote == null) {
            baseline = MISSING
            persist()
        }
        if (hash != baseline) {
            if (remote == null) return Result.Removed(pending != null || ended)
            return Result.Incoming(remote, hash, pending != null || ended)
        }
        if (pending != null || ended) {
            folder.replace(pending, hash)
            // Don't acknowledge a failed/partial provider write.
            val expected = hash(pending)
            check(hash(folder.read()) == expected) { "The shared save changed during writing. Please check again." }
            acknowledge(expected)
            return Result.Published
        }
        return Result.Unchanged
    }

    /** Called only after the engine successfully imported this exact snapshot. */
    fun imported(hash: String) = acknowledge(hash)

    /** The player explicitly chose the phone version in a conflict dialog. */
    fun keepPhone(expected: String): Result {
        if (hash(folder.read()) != expected) return reconcile()
        baseline = expected
        persist()
        return reconcile()
    }

    private fun acknowledge(hash: String) {
        baseline = hash
        pending = null
        ended = false
        persist()
    }

    private fun persist() {
        val state = Properties()
        state.setProperty("folder", folderId)
        baseline?.let { state.setProperty("baseline", it) }
        pending?.let { state.setProperty("pending", Base64.getEncoder().encodeToString(it)) }
        state.setProperty("ended", ended.toString())
        Files.createDirectories(journal.parent)
        val temp = journal.resolveSibling(journal.fileName.toString() + ".tmp")
        Files.newOutputStream(temp).use { state.store(it, "Save folder sync") }
        Files.move(temp, journal, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val MISSING = "missing"
        fun hash(bytes: ByteArray?): String = bytes?.let {
            MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }
        } ?: MISSING
    }
}
