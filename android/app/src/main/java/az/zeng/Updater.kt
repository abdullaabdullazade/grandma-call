package az.zeng

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Over-the-air updates from our own server.
 * check(): asks /app/update for the latest version.  install(): downloads the APK with the
 * device token, verifies its SHA-256 and hands it to PackageInstaller. Android itself refuses
 * an APK signed with a different key, so only builds signed with zeng.keystore can install.
 */
object Updater {
    enum class State { NONE, AVAILABLE, DOWNLOADING, INSTALLING, FAILED }

    var state = State.NONE; private set
    var progress = 0; private set
    var versionName = ""; private set
    var notes = ""; private set
    var onChange: (() -> Unit)? = null
    /** Set when in-app install is impossible; the UI opens it in the browser. */
    var fallbackUrl: String? = null

    private var downloadUrl = ""

    private var versionCode = 0
    private var sha256 = ""
    private val main = Handler(Looper.getMainLooper())
    private var checking = false

    private fun set(s: State) = main.post {
        state = s
        onChange?.invoke()
        ZengService.refreshUpdate()
    }.let {}

    fun check() {
        val token = Prefs.token ?: return
        if (checking || state == State.DOWNLOADING || state == State.INSTALLING) return
        checking = true
        Thread {
            try {
                val req = Request.Builder().url(BuildConfig.SERVER + "/app/update").header("Authorization", "Bearer $token").build()
                Net.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val j = JSONObject(r.body!!.string())
                    val code = j.optInt("versionCode")
                    if (code > BuildConfig.VERSION_CODE) {
                        versionCode = code
                        versionName = j.optString("versionName")
                        notes = j.optString("notes")
                        sha256 = j.optString("sha256")
                        downloadUrl = j.optString("download").let { if (it.isEmpty()) "" else BuildConfig.SERVER + it }
                        set(State.AVAILABLE)
                    }
                }
            } catch (_: Exception) {
            } finally { checking = false }
        }.start()
    }

    fun install(c: Context) {
        val token = Prefs.token ?: return
        if (state != State.AVAILABLE && state != State.FAILED) return
        progress = 0
        set(State.DOWNLOADING)
        Thread {
            // Android 5/6: the old installer reads a world-readable file:// APK; newer ones use sessions.
            val legacy = Build.VERSION.SDK_INT < 24
            val file = if (legacy) File(c.getExternalFilesDir(null) ?: c.filesDir, "update.apk") else File(c.cacheDir, "update.apk")
            try {
                val req = Request.Builder().url(BuildConfig.SERVER + "/app/apk").header("Authorization", "Bearer $token").build()
                val md = MessageDigest.getInstance("SHA-256")
                Net.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("http ${r.code}")
                    val body = r.body!!
                    val total = body.contentLength()
                    body.byteStream().use { inp ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); md.update(buf, 0, n); done += n
                                if (total > 0) {
                                    val p = (done * 100 / total).toInt()
                                    if (p != progress) { progress = p; main.post { onChange?.invoke() } }
                                }
                            }
                        }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (sha256.isNotEmpty() && !hex.equals(sha256, true)) throw SecurityException("sha256 mismatch")
                set(State.INSTALLING)
                if (legacy) installLegacy(c, file) else commit(c, file)
            } catch (e: Throwable) {
                android.util.Log.w("Call", "update failed", e)
                file.delete()
                fail("${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun fail(reason: String) {
        if (downloadUrl.isNotEmpty()) fallbackUrl = downloadUrl
        report("failed: $reason")
        set(State.FAILED)
    }

    /** Tell the server how an update went, so a failing install on someone's phone is visible. */
    private fun report(what: String) = main.post {
        Signaling.send(JSONObject().put("type", "diag")
            .put("msg", "update ${BuildConfig.VERSION_NAME} -> $versionName $what (Android ${Build.VERSION.SDK_INT})"))
    }.let {}

    @Suppress("DEPRECATION")
    private fun installLegacy(c: Context, file: File) {
        file.setReadable(true, false)
        file.parentFile?.setExecutable(true, false)
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.fromFile(file), "application/vnd.android.package-archive")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post {
            try { c.startActivity(i); report("legacy installer opened"); set(State.AVAILABLE) } catch (e: Exception) { fail("legacy: ${e.message}") }
        }
    }

    private fun commit(c: Context, file: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            file.inputStream().use { inp ->
                s.openWrite("call.apk", 0, file.length()).use { out ->
                    inp.copyTo(out)
                    s.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val cb = PendingIntent.getBroadcast(c, id, Intent(c, UpdateReceiver::class.java), flags)
            s.commit(cb.intentSender)
        }
        file.delete()
    }

    internal fun installResult(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> report("waiting for user confirmation")
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> { report("cancelled by user"); set(State.AVAILABLE) }
            else -> fail("installer status $status: $message")
        }
    }
}

/** PackageInstaller callback: shows Android's "Install update?" confirmation, or reports failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            try {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Updater.installResult(PackageInstaller.STATUS_FAILURE, "confirm: ${e.message}")
                return
            }
        }
        Updater.installResult(status, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
