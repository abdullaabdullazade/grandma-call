package az.zeng

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/**
 * Google push (FCM). The server sends a high-priority "wake" when a call can't reach this phone
 * over the socket; Android then starts the app even from deep sleep, we reconnect and it rings.
 */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) {
        if (m.data["type"] != "wake") return
        val d = m.data
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            CallManager.incomingFromPush(d["callId"] ?: "", d["from"] ?: "", d["name"] ?: "", d["video"] == "1")
            App.startService(this)
            Signaling.ensureFresh()
        }
    }

    override fun onNewToken(token: String) = send(token)

    companion object {
        /** Hand the current FCM token to the server (phones without Google services just skip push). */
        fun register() {
            try {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { send(it) }
            } catch (_: Exception) {}
        }

        private fun send(token: String) {
            Signaling.send(JSONObject().put("type", "push_token").put("token", token))
        }
    }
}
