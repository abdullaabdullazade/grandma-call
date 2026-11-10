package az.zeng

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        // Save crashes; Signaling uploads them on the next connection so we can see what broke.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "crash.txt").writeText(
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Signaling.deviceName()} · thread ${t.name}\n$sw")
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
        createChannels()
        CallManager.init()
    }

    companion object {
        lateinit var ctx: Context

        /** Also called after a language switch so channel names follow it. */
        fun createChannels() {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_SERVICE, L.s(R.string.ch_service), NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_INCOMING, L.s(R.string.ch_incoming), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null); enableVibration(false) }) // Ringer plays sound itself
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, L.s(R.string.ch_ongoing), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_MISSED, L.s(R.string.ch_missed), NotificationManager.IMPORTANCE_HIGH))
        }
        const val CH_SERVICE = "service"
        const val CH_INCOMING = "incoming"
        const val CH_ONGOING = "ongoing"
        const val CH_MISSED = "missed"

        fun startService(c: Context) {
            if (Prefs.token == null) return
            val i = Intent(c, ZengService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
    }
}

object Prefs {
    private val p get() = App.ctx.getSharedPreferences("zeng", Context.MODE_PRIVATE)
    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()
    /** Remembered so the app can silently re-register and never drops back to the join screen. */
    var name: String?
        get() = p.getString("name", null)
        set(v) = p.edit().putString("name", v).apply()
    var code: String?
        get() = p.getString("code", null)
        set(v) = p.edit().putString("code", v).apply()
    /** "az" (default) or "en", independent of the phone's system language. */
    var lang: String
        get() = p.getString("lang", "az")!!
        set(v) = p.edit().putString("lang", v).apply()
    var oemHintDone: Boolean
        get() = p.getBoolean("oemHintDone", false)
        set(v) = p.edit().putBoolean("oemHintDone", v).apply()
    var askedBattery: Boolean
        get() = p.getBoolean("askedBattery", false)
        set(v) = p.edit().putBoolean("askedBattery", v).apply()
}

/** In-app language: Azerbaijani by default, English optional. */
object L {
    private var cached: Pair<String, Context>? = null

    fun wrap(base: Context): Context {
        val loc = Locale(Prefs.lang)
        Locale.setDefault(loc)
        val conf = Configuration(base.resources.configuration)
        conf.setLocale(loc)
        return base.createConfigurationContext(conf)
    }

    val ctx: Context
        get() = cached?.takeIf { it.first == Prefs.lang }?.second
            ?: wrap(App.ctx).also { cached = Prefs.lang to it }

    fun s(id: Int, vararg args: Any): String = ctx.getString(id, *args)
}
