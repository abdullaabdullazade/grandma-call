package az.zeng

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject

/**
 * Foreground service: keeps the server connection alive so calls ring even when the app is closed.
 * Its notification doubles as the incoming-call / ongoing-call notification.
 */
class ZengService : Service(), Signaling.Listener {

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(ID_MAIN, build())
        Signaling.add(this)
        Signaling.start(this)
        KeepAlive.schedule(this)
    }

    // Also called again after a fresh join: (re)start the connection if it was stopped.
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        Signaling.start(this)
        return START_STICKY
    }
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        Signaling.remove(this)
        instance = null
        super.onDestroy()
    }

    override fun onState() = update()

    override fun onMessage(m: JSONObject) {
        when (m.optString("type")) {
            "missed" -> notifyMissed(m.getString("name"), m.optBoolean("video"))
            "hello", "update" -> Updater.check()
        }
    }

    @Suppress("DEPRECATION")
    fun updateNotice() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Updater.state != Updater.State.AVAILABLE) { nm.cancel(ID_UPDATE); return }
        nm.notify(ID_UPDATE, builder(App.CH_MISSED)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(L.s(R.string.update_available, Updater.versionName))
            .setContentText(L.s(R.string.update_tap))
            .setAutoCancel(true)
            .setContentIntent(pending(MainActivity::class.java, 6))
            .build())
    }

    private fun pending(cls: Class<*>, req: Int) = PendingIntent.getActivity(this, req,
        Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags())

    private fun action(act: String, req: Int) = PendingIntent.getBroadcast(this, req,
        Intent(this, CallActionReceiver::class.java).setAction(act), flags())

    private fun builder(ch: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else @Suppress("DEPRECATION") Notification.Builder(this)

    @Suppress("DEPRECATION")
    private fun build(): Notification {
        val cm = CallManager
        return when (cm.state) {
            CallManager.State.INCOMING -> builder(App.CH_INCOMING)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(cm.title())
                .setContentText(L.s(if (cm.videoCall) R.string.incoming_video else R.string.incoming_voice))
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_MAX)
                .setOngoing(true)
                .setFullScreenIntent(pending(CallActivity::class.java, 1), true)
                .setContentIntent(pending(CallActivity::class.java, 1))
                .addAction(0, L.s(R.string.decline), action(ACT_DECLINE, 2))
                .addAction(0, L.s(R.string.answer), action(ACT_ANSWER, 3))
                .build()
            CallManager.State.IDLE, CallManager.State.ENDED -> builder(App.CH_SERVICE)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(L.s(R.string.app_name))
                .setContentText(L.s(if (Signaling.connected) R.string.notif_ready else R.string.connecting_server))
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .setContentIntent(pending(MainActivity::class.java, 0))
                .build()
            else -> builder(App.CH_ONGOING)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(cm.title())
                .setContentText(L.s(if (cm.state == CallManager.State.ACTIVE) R.string.notif_in_call else R.string.connecting))
                .setUsesChronometer(cm.connectedAt > 0)
                .setWhen(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - cm.connectedAt))
                .setOngoing(true)
                .setContentIntent(pending(CallActivity::class.java, 1))
                .addAction(0, L.s(R.string.hangup), action(ACT_HANGUP, 4))
                .build()
        }
    }

    fun update() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_MAIN, build())
    }

    private fun notifyMissed(name: String, video: Boolean) {
        val n = builder(App.CH_MISSED)
            .setSmallIcon(R.drawable.ic_call_missed)
            .setContentTitle(L.s(if (video) R.string.missed_video else R.string.missed_voice))
            .setContentText(name)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(PendingIntent.getActivity(this, 5,
                Intent(this, MainActivity::class.java).putExtra("tab", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags()))
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_MISSED + (name.hashCode() and 0xffff), n) // one per caller
    }

    companion object {
        const val ID_MAIN = 1
        const val ID_MISSED = 2
        const val ID_UPDATE = 3
        const val ACT_ANSWER = "az.zeng.ANSWER"
        const val ACT_DECLINE = "az.zeng.DECLINE"
        const val ACT_HANGUP = "az.zeng.HANGUP"
        private var instance: ZengService? = null

        fun refresh() = instance?.update()
        fun refreshUpdate() = instance?.updateNotice()

        fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ZengService.ACT_DECLINE -> CallManager.decline()
            ZengService.ACT_HANGUP -> CallManager.hangup()
            ZengService.ACT_ANSWER -> c.startActivity(Intent(c, CallActivity::class.java)
                .putExtra(CallActivity.EXTRA_ANSWER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = App.startService(c)
}
