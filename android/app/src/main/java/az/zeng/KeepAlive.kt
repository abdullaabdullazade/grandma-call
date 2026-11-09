package az.zeng

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Wakes the phone every ~45 s, even in deep sleep, so the server connection is checked and kept
 * alive (routers and mobile networks silently drop idle connections). Without it, calls to an old
 * phone with the screen off only arrive after it notices the dead socket on its own.
 */
class KeepAlive : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val wl = (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zeng:keepalive").apply { acquire(15000) }
        val pending = goAsync()
        Signaling.heartbeat {
            if (wl.isHeld) wl.release()
            pending.finish()
        }
        schedule(c)
    }

    companion object {
        private const val INTERVAL = 45_000L

        fun schedule(c: Context) {
            if (Prefs.token == null) return
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getBroadcast(c, 0, Intent(c, KeepAlive::class.java), flags)
            val at = SystemClock.elapsedRealtime() + INTERVAL
            when {
                Build.VERSION.SDK_INT >= 23 -> am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                else -> am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }
    }
}
