package com.pocketwormhole.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.PlatformSpecific
import xyz.znix.xftl.vanillasave.FolderSyncEngine
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/** SAF owns file access; imports run on a rendered frame, after GL has resumed. */
class SaveFolderSync(private val activity: Activity) : PlatformSpecific.SaveSync {
    private val prefs = activity.getSharedPreferences("save-folder-sync", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "SaveFolderSync").apply { isDaemon = true } }
    private val commands = ConcurrentLinkedQueue<(MainGame) -> Unit>()
    private var engine: FolderSyncEngine? = null // worker only
    private var engineUri: String? = null
    private var deferredHash: String? = null // worker only
    private var lastError: String? = null
    private var taskGeneration = 0 // worker only
    private var nextCheck = 0L // render thread only
    private var importing = false // render thread only
    @Volatile private var foreground = false
    @Volatile private var checkRequested = true
    @Volatile private var dialogOpen = false
    @Volatile private var generation = 0
    @Volatile private var status = "Choose a folder to exchange continue.sav with your PC."

    override val enabled: Boolean get() = prefs.getString("tree", null) != null

    override fun saved(bytes: ByteArray) {
        if (!enabled || importing) return
        submit {
            val sync = session()
            sync.saved(bytes)
            handle(sync.reconcile())
        }
    }

    override fun runEnded() {
        if (!enabled || importing) return
        submit { val sync = session(); sync.runEnded(); handle(sync.reconcile()) }
    }

    fun onResume() { foreground = true; checkRequested = true }
    fun onPause() { foreground = false }
    fun close() {
        foreground = false
        generation++
        commands.clear()
        worker.shutdown()
    }

    override fun pump(game: MainGame) {
        while (true) (commands.poll() ?: break).invoke(game)
        if (!enabled || !foreground) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (checkRequested || now >= nextCheck) {
            val capture = checkRequested
            checkRequested = false
            nextCheck = now + 5000
            // Resume also saves the current in-flight state before considering a PC save.
            if (capture) {
                game.autoPauseIfInFlight()
                capturePhone(game)
            }
            submit { handle(session().reconcile()) }
        }
    }

    override fun showSettings() = activity.runOnUiThread {
        if (activity.isFinishing) return@runOnUiThread
        val folder = prefs.getString("tree", null)?.let { Uri.decode(Uri.parse(it).lastPathSegment ?: it) }
        AlertDialog.Builder(activity)
            .setTitle("PC save folder")
            .setMessage((folder?.let { "Folder: $it\n\n$status\n\n" } ?: "") +
                "Choose a local folder, such as Documents/FTL-Sync, and sync it with your PC's " +
                "Documents/My Games/FasterThanLight folder using Syncthing.\n\n" +
                "The game exports continue.sav when it saves, and offers to load a changed PC save. " +
                "Wait for Syncthing before switching devices. Play on one device at a time. " +
                "Profiles and achievements are not synced yet.")
            .setPositiveButton(if (enabled) "Change folder" else "Choose folder") { _, _ ->
                activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                }, REQUEST_FOLDER)
            }
            .setNegativeButton("Close", null)
            .apply {
                if (enabled) setNeutralButton("More") { _, _ ->
                    AlertDialog.Builder(activity).setTitle("PC save folder")
                        .setItems(arrayOf("Check now", "Disconnect folder")) { _, which ->
                            if (which == 0) { submit { deferredHash = null }; checkRequested = true }
                            else disconnect()
                        }.show()
                }
            }.show()
    }

    fun folderPicked(resultCode: Int, data: Intent?) {
        val uri = data?.data ?: return
        if (resultCode != Activity.RESULT_OK) return
        try {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            require(flags and access == access) { "The folder must allow reading and writing." }
            activity.contentResolver.takePersistableUriPermission(uri, flags)
            generation++
            prefs.edit().putString("tree", uri.toString()).commit()
            status = "Folder connected. Checking saves..."
            submit { deferredHash = null }
            // Seed an existing phone run, even if it isn't currently loaded.
            commands.add { game ->
                capturePhone(game)
                checkRequested = true
            }
        } catch (e: Exception) { error(e) }
    }

    private fun disconnect() {
        val old = prefs.getString("tree", null)
        generation++
        prefs.edit().remove("tree").commit()
        submitEvenDisabled {
            engine = null; engineUri = null; deferredHash = null
            Files.deleteIfExists(journal())
        }
        if (old != null) try {
            activity.contentResolver.releasePersistableUriPermission(Uri.parse(old),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) { }
        status = "Folder disconnected. Phone saves are kept."
    }

    private fun journal() = File(activity.filesDir, "save-folder-sync/state.properties").toPath()

    private fun capturePhone(game: MainGame) {
        if (game.isInFlight) {
            game.writeRunSave()
            return
        }
        val path = game.vanillaSavePath
        val imported = path.resolveSibling("continue-imported.sav")
        when {
            Files.exists(path) -> saved(Files.readAllBytes(path))
            // A successful PC import moves its portable save aside, but leaves the native run.
            Files.exists(path.resolveSibling("run-save.xml")) && Files.exists(imported) -> saved(Files.readAllBytes(imported))
        }
    }

    private fun session(): FolderSyncEngine {
        val uri = prefs.getString("tree", null) ?: throw IOException("No folder connected.")
        if (engine == null || engineUri != uri) {
            engine = FolderSyncEngine(journal(), uri, SafFolder(Uri.parse(uri)))
            engineUri = uri
        }
        return engine!!
    }

    private fun handle(result: FolderSyncEngine.Result) {
        if (taskGeneration != generation) return
        when (result) {
            FolderSyncEngine.Result.Published -> {
                status = "Folder updated. Wait for Syncthing before switching devices."
                lastError = null
                Log.i("SaveFolderSync", "Shared save updated")
            }
            FolderSyncEngine.Result.Unchanged -> status = "Folder checked. No new PC save."
            is FolderSyncEngine.Result.Incoming -> offer(result.bytes, result.hash, result.conflict)
            is FolderSyncEngine.Result.Removed -> offer(null, FolderSyncEngine.MISSING, result.conflict)
        }
    }

    private fun offer(bytes: ByteArray?, hash: String, conflict: Boolean) {
        status = if (bytes == null) "The PC save was removed. Phone save kept." else "A changed PC save is available."
        if (!foreground || dialogOpen || deferredHash == hash) return
        dialogOpen = true
        deferredHash = hash
        Log.i("SaveFolderSync", "Shared save needs a choice: $hash")
        val ticket = generation
        val phoneEnded = session().phoneRunEnded
        commands.add { game ->
            game.autoPauseIfInFlight()
            // Capture any changes since the last jump without publishing over the PC file.
            capturePhone(game)
            activity.runOnUiThread {
                if (ticket != generation || !foreground || activity.isFinishing) {
                    dialogOpen = false
                    return@runOnUiThread
                }
                val message = if (bytes == null)
                    "continue.sav disappeared from the folder. The PC run may have ended. " +
                        "Your phone save is kept. Send it back only if you want to continue the phone run."
                else if (phoneEnded) "The phone run has ended, but the folder contains a different PC run. " +
                    "Load it, or leave it in the folder for later."
                else (if (conflict) "Both the phone and folder have saves to keep.\n\n" else "The folder has a changed PC save.\n\n") +
                    "Load the PC save? It replaces the phone run. A private backup of the phone save will be kept."
                AlertDialog.Builder(activity).setTitle(if (conflict) "Choose a save" else "PC save folder")
                    .setMessage(message)
                    .setNegativeButton("Not now", null)
                    .apply { if (!phoneEnded) setNeutralButton("Use phone save") { _, _ ->
                        commands.add { current ->
                            if (ticket != generation) return@add
                            capturePhone(current)
                            submit { handle(session().keepPhone(hash)) }
                        }
                    } }
                    .apply {
                        if (bytes != null) setPositiveButton("Load PC save") { _, _ ->
                            // Re-read before importing; the dialog may have stayed open for minutes.
                            submit {
                                val fresh = session().reconcile()
                                if (fresh is FolderSyncEngine.Result.Incoming && fresh.hash == hash) {
                                    commands.add { current ->
                                        if (ticket == generation) importSave(current, bytes, hash)
                                    }
                                } else { deferredHash = null; handle(fresh) }
                            }
                        }
                    }
                    .setOnDismissListener { dialogOpen = false }.show()
            }
        }
    }

    private fun importSave(game: MainGame, bytes: ByteArray, hash: String) {
        val path = game.vanillaSavePath
        val native = path.resolveSibling("run-save.xml")
        val previous = if (Files.exists(path)) Files.readAllBytes(path) else null
        val previousNative = if (Files.exists(native)) Files.readAllBytes(native) else null
        val previousState = (game.currentState as? xyz.znix.xftl.game.InGameState)?.saveGameState()
        try {
            previous?.let { backup("phone", it) }
            previousNative?.let { backup("phone-native", it, "xml") }
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
            importing = true
            check(game.continueVanillaSave()) { "Couldn't load the PC save. Check its game version and mods." }
            game.autoPauseIfInFlight()
            submit { session().imported(hash); deferredHash = null }
            status = "PC save loaded."
            Log.i("SaveFolderSync", "PC save loaded: $hash")
        } catch (e: Exception) {
            // Importing a different AE setting may have replaced the content resources.
            // Restore the live state as well as its files, without publishing a rollback.
            if (previousState != null) try { game.loadSavedGame(previousState) }
            catch (restore: Exception) { Log.e("SaveFolderSync", "Couldn't restore the live phone run", restore) }
            game.autoPauseIfInFlight()
            if (previous != null) Files.write(path, previous) else Files.deleteIfExists(path)
            if (previousNative != null) Files.write(native, previousNative) else Files.deleteIfExists(native)
            error(e)
        } finally { importing = false }
    }

    private fun backup(name: String, bytes: ByteArray, extension: String = "sav") {
        val dir = File(activity.filesDir, "save-folder-sync/backups").apply { mkdirs() }
        File(dir, "$name-${System.currentTimeMillis()}-${FolderSyncEngine.hash(bytes).take(12)}.$extension").writeBytes(bytes)
    }

    private fun submit(action: () -> Unit) {
        val ticket = generation
        submitEvenDisabled {
            if (enabled && ticket == generation) {
                taskGeneration = ticket
                action()
            }
        }
    }

    private fun submitEvenDisabled(action: () -> Unit) {
        worker.execute { try { action() } catch (e: Exception) { error(e) } }
    }

    private fun error(e: Exception) {
        status = "Folder sync stopped: ${e.message}. Open PC save folder to check it."
        Log.e("SaveFolderSync", status, e)
        if (lastError == status) return
        lastError = status
        activity.runOnUiThread {
            if (foreground && !activity.isFinishing) android.widget.Toast.makeText(activity, status, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private inner class SafFolder(private val tree: Uri) : FolderSyncEngine.Folder {
        private fun find(): Uri? {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            activity.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) if (cursor.getString(1) == "continue.sav")
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
            } ?: throw IOException("Couldn't read the folder. Choose it again.")
            return null
        }

        override fun read(): ByteArray? {
            val uri = find() ?: return null
            return activity.contentResolver.openInputStream(uri)?.use {
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= MAX_SAVE_BYTES) { "The shared save is too large." }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: throw IOException("Couldn't open continue.sav.")
        }

        override fun replace(bytes: ByteArray?, expected: String) {
            val previous = read()
            check(FolderSyncEngine.hash(previous) == expected) { "The PC save changed. Check the folder again." }
            previous?.let { backup("folder", it) }
            var uri = find()
            if (bytes == null) {
                if (uri != null) check(DocumentsContract.deleteDocument(activity.contentResolver, uri))
                return
            }
            if (uri == null) {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                uri = DocumentsContract.createDocument(activity.contentResolver, parent, "application/octet-stream", "continue.sav")
                    ?: throw IOException("Couldn't create continue.sav.")
            }
            activity.contentResolver.openOutputStream(uri, "rwt")?.use { it.write(bytes) }
                ?: throw IOException("Couldn't write continue.sav.")
        }
    }

    companion object {
        const val REQUEST_FOLDER = 1042
        private const val MAX_SAVE_BYTES = 32 * 1024 * 1024
    }
}
