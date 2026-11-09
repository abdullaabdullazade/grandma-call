package az.zeng

import android.content.Context
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Ringtone chosen by the admin: downloaded once, verified by SHA-256, played instead of the phone's default. */
object Tone {
    private val dest get() = File(App.ctx.filesDir, "ringtone")
    private val prefs get() = App.ctx.getSharedPreferences("zeng", Context.MODE_PRIVATE)
    @Volatile private var syncing = false

    /** Custom ringtone file, or null to use the phone's default ringtone. */
    fun file(): File? = dest.takeIf { it.exists() && prefs.getString("toneSha", null) != null }

    fun sync(info: JSONObject?) {
        val want = info?.optString("sha256")?.takeIf { it.isNotEmpty() }
        if (want == null) {
            dest.delete()
            prefs.edit().remove("toneSha").apply()
            return
        }
        if (want == prefs.getString("toneSha", null) && dest.exists()) return
        val token = Prefs.token ?: return
        if (syncing) return
        syncing = true
        Thread {
            try {
                val req = Request.Builder().url(BuildConfig.SERVER + "/app/ringtone").header("Authorization", "Bearer $token").build()
                val tmp = File(App.ctx.filesDir, "ringtone.part")
                val md = MessageDigest.getInstance("SHA-256")
                Net.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@Thread
                    r.body!!.byteStream().use { inp ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); md.update(buf, 0, n)
                            }
                        }
                    }
                }
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (got.equals(want, true) && tmp.renameTo(dest)) prefs.edit().putString("toneSha", want).apply()
                else tmp.delete()
            } catch (_: Exception) {
            } finally { syncing = false }
        }.start()
    }
}
