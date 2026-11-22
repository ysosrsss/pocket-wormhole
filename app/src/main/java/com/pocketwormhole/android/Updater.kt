package com.pocketwormhole.android

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONObject
import xyz.znix.xftl.sys.PlatformSpecific
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Looks for a newer Pocket Wormhole+ release on GitHub when the game starts,
 * and offers to download and install it (Update / Not now / Skip this
 * version). The APK is checked against the size and SHA-256 that GitHub
 * lists for it, then handed to Android's package installer, which asks the
 * user to confirm. Saves are kept, as the update is signed with the same key.
 *
 * Turned on and off (and run by hand) from the MODS panel.
 */
class Updater(private val activity: Activity) : PlatformSpecific.Updates {

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val debuggable = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Test builds may point the check at another URL (a local server). */
    private val testUrl: String? =
        if (debuggable) File(activity.filesDir, TEST_URL_FILE).takeIf { it.exists() }?.readText()?.trim() else null

    /** Only the release app checks: a test build would be offered the release's package. */
    val available: Boolean = activity.packageName == RELEASE_PACKAGE || testUrl != null

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    private val busy = AtomicBoolean(false)
    private var receiverRegistered = false

    private class Release(val tag: String, val apkUrl: String, val size: Long, val sha256: String?)

    /** Called once the game is started. */
    fun checkOnStart() {
        cleanUp()
        if (!available || checkedThisProcess || !enabled) return
        checkedThisProcess = true
        check(manual = false)
    }

    /** From the MODS panel: also offers a skipped version, and says when there's nothing new. */
    override fun checkNow() {
        if (!available) return
        toast("Looking for updates...")
        check(manual = true)
    }

    private fun check(manual: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        thread(name = "update-check", isDaemon = true) {
            val result = try {
                Result.success(fetchLatest())
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                Result.failure(e)
            }
            activity.runOnUiThread {
                busy.set(false)
                result.onSuccess { onChecked(it, manual) }
                result.onFailure { if (manual) toast("Couldn't look for updates: ${it.message}") }
            }
        }
    }

    private fun onChecked(release: Release?, manual: Boolean) {
        if (activity.isFinishing) return
        val current = currentVersion()
        if (release == null || !isNewer(release.tag, current)) {
            if (manual) toast("You have the latest version ($current).")
            return
        }
        if (!manual && prefs.getString(KEY_SKIP, null) == release.tag) return

        val version = release.tag.removePrefix("v")
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(
                "Pocket Wormhole+ $version is available (you have $current).\n\n" +
                        "Your saves and settings are kept."
            )
            .setPositiveButton("Update") { _, _ -> download(release) }
            .setNegativeButton("Not now", null)
            .setNeutralButton("Skip this version") { _, _ ->
                prefs.edit().putString(KEY_SKIP, release.tag).apply()
            }
            .show()
    }

    private fun fetchLatest(): Release? {
        val conn = open(testUrl ?: "https://api.github.com/repos/$REPO/releases/latest", READ_TIMEOUT_MS)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null // no releases
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (!asset.getString("name").endsWith(".apk", ignoreCase = true)) continue
                val digest = asset.optString("digest", "")
                val sha256 = if (digest.startsWith("sha256:")) digest.substring(7).lowercase() else null
                return Release(tag, asset.getString("browser_download_url"), asset.getLong("size"), sha256)
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun download(release: Release) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            setPadding(48, 32, 48, 32)
        }
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Downloading ${release.tag.removePrefix("v")}")
            .setView(bar)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .show()

        thread(name = "update-download", isDaemon = true) {
            val file = File(updateDir(), "update.apk")
            try {
                downloadTo(release, file, cancelled) { permille ->
                    activity.runOnUiThread { bar.progress = permille }
                }
                activity.runOnUiThread {
                    dialog.dismiss()
                    install(file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                cleanUp()
                activity.runOnUiThread {
                    dialog.dismiss()
                    if (!cancelled.get()) showError("The update couldn't be downloaded: ${e.message}")
                }
            }
        }
    }

    private fun downloadTo(release: Release, file: File, cancelled: AtomicBoolean, progress: (Int) -> Unit) {
        if (release.size <= 0 || release.size > MAX_APK_BYTES) throw IOException("unexpected size ${release.size}")
        val conn = open(release.apkUrl, DOWNLOAD_TIMEOUT_MS)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            val sha = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var shown = -1
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled.get()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > release.size) throw IOException("the file is bigger than expected")
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        val permille = (total * 1000 / release.size).toInt()
                        if (permille != shown) {
                            shown = permille
                            progress(permille)
                        }
                    }
                }
            }
            if (total != release.size) throw IOException("the download is incomplete")
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && hex != release.sha256) throw IOException("the file's checksum doesn't match")
        } finally {
            conn.disconnect()
        }
    }

    /** A downloaded update waiting for the user to allow installs in Settings. */
    private var waitingForPermission: File? = null

    /** On the UI thread. */
    private fun install(file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            // Without this, Android's installer sends the user to Settings and drops
            // the install when they come back, so ask first and carry on in [onResume].
            waitingForPermission = file
            AlertDialog.Builder(activity)
                .setTitle("Allow installing the update")
                .setMessage(
                    "Android needs your permission for Pocket Wormhole+ to install its updates. " +
                            "Tap Settings, turn on \"Allow from this source\" and come back - " +
                            "the update then installs. You only need to do this once."
                )
                .setPositiveButton("Settings") { _, _ ->
                    activity.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + activity.packageName)
                        )
                    )
                }
                .setNegativeButton("Cancel") { _, _ ->
                    waitingForPermission = null
                    cleanUp()
                }
                .setCancelable(false)
                .show()
            return
        }
        thread(name = "update-install", isDaemon = true) {
            try {
                commit(file)
            } catch (e: Exception) {
                Log.w(TAG, "Update install failed", e)
                cleanUp()
                showError("The update couldn't be installed: ${e.message}")
            }
        }
    }

    /** Called from the activity's onResume: back from Settings, finish a waiting install. */
    fun onResume() {
        val file = waitingForPermission ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) return
        waitingForPermission = null
        install(file)
    }

    private fun commit(file: File) {
        registerReceiver()
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(activity.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            val intent = Intent(ACTION_INSTALLED).setPackage(activity.packageName)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            // The installer adds the result to the intent, so it must be mutable.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(activity, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(confirm)
                    }
                }
                // On success Android replaces this process with the new version.
                PackageInstaller.STATUS_SUCCESS -> cleanUp()
                else -> {
                    cleanUp()
                    if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"
                        showError("The update couldn't be installed: $message")
                    }
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        val filter = IntentFilter(ACTION_INSTALLED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val u = URL(url)
        if (u.protocol != "https" && !(debuggable && testUrl != null)) throw IOException("not an https address")
        return (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PocketWormholePlus-updater")
        }
    }

    private fun currentVersion(): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"

    private fun updateDir(): File = File(activity.cacheDir, "update").apply { mkdirs() }

    private fun cleanUp() {
        File(activity.cacheDir, "update").deleteRecursively()
    }

    private fun toast(text: String) = activity.runOnUiThread {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }

    private fun showError(text: String) = activity.runOnUiThread {
        if (!activity.isFinishing)
            AlertDialog.Builder(activity).setTitle("Update").setMessage(text).setPositiveButton("OK", null).show()
    }

    companion object {
        private const val TAG = "XFTL"
        const val REPO = "ysosrsss/pocket-wormhole"
        private const val RELEASE_PACKAGE = "com.pocketwormhole.android.plus"
        private const val PREFS = "updater"
        private const val KEY_ENABLED = "check_updates"
        private const val KEY_SKIP = "update_skip"
        private const val TEST_URL_FILE = "update-test-url.txt"
        private const val ACTION_INSTALLED = "com.pocketwormhole.android.UPDATE_INSTALLED"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val DOWNLOAD_TIMEOUT_MS = 30_000
        private const val MAX_APK_BYTES = 512L shl 20

        /** Once per process, not on every activity restart. */
        private var checkedThisProcess = false

        /** Is [tag] (v0.2.7-plus.36) newer than [current] (0.2.7-plus.35)? Compares the numbers in order. */
        fun isNewer(tag: String, current: String): Boolean {
            val a = numbers(tag)
            val b = numbers(current)
            if (a.isEmpty()) return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { -1 }
                val y = b.getOrElse(i) { -1 }
                if (x != y) return x > y
            }
            return false
        }

        private fun numbers(s: String): List<Long> =
            Regex("\\d+").findAll(s).map { it.value.toLongOrNull() ?: 0 }.toList()
    }
}
