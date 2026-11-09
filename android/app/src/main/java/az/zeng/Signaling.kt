package az.zeng

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class Contact(val id: String, val name: String, val online: Boolean, val lastSeen: Long = 0)
data class CallEntry(
    val id: String, val peer: String, val name: String, val incoming: Boolean,
    val video: Boolean, val started: Long, val duration: Long, val status: String,
    val members: List<String> = listOf(peer), // everyone else in the call (group calls: 2-3 people)
) {
    val group get() = members.size > 1
}

/** WebSocket link to the server. All callbacks run on the main thread. */
object Signaling {
    interface Listener {
        fun onState() {}
        fun onMessage(m: JSONObject) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var backoff = 1000L
    private var running = false
    private val listeners = mutableSetOf<Listener>()

    var connected = false; private set
    var myId = ""; private set
    var myName = ""; private set
    var ice = JSONArray(); private set
    var contacts = listOf<Contact>(); private set
    var history = listOf<CallEntry>(); private set
    var totals = mapOf<String, Long>(); private set

    fun add(l: Listener) = listeners.add(l)
    fun remove(l: Listener) = listeners.remove(l)
    private fun emitState() = listeners.toList().forEach { it.onState() }

    private var netCallback = false
    private var lastNetwork: Network? = null

    fun start(c: Context) {
        if (running) return
        running = true
        banned = false
        connect()
        if (netCallback) return
        netCallback = true
        // A new network (Wi-Fi <-> mobile) means the old socket is dead even if it looks open:
        // reconnect right away instead of waiting for pings to time out.
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = main.post {
                if (network == lastNetwork) return@post // same network reported again: socket is fine
                val first = lastNetwork == null
                lastNetwork = network
                backoff = 1000
                if (running && !(first && connected)) reconnectNow()
            }.let {}
            override fun onLost(network: Network) = main.post {
                if (network != lastNetwork) return@post
                lastNetwork = null
                if (running) { connected = false; emitState(); backoff = 1000; main.removeCallbacks(retry); main.postDelayed(retry, 1000) }
            }.let {}
        }
        // Default network only (API 24+): ignore VPNs and the idle second radio
        if (android.os.Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(cb)
        else cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
    }

    fun stop() {
        running = false
        ws?.close(1000, null)
        ws = null
    }

    private val retry = Runnable { if (!connected && running) reconnectNow() }

    private fun reconnectNow() {
        ws?.cancel()
        connect()
    }

    private fun connect() {
        val token = Prefs.token ?: return
        val url = BuildConfig.SERVER.replaceFirst("http", "ws") + "/ws"
        ws = Net.ws.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(JSONObject().put("type", "auth").put("token", token).put("version", BuildConfig.VERSION_NAME).toString())
            }

            override fun onMessage(w: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (e: Exception) { return }
                main.post { if (w === ws) handle(m) }
            }

            override fun onClosed(w: WebSocket, code: Int, reason: String) = main.post { lost(w) }.let {}
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = main.post { lost(w) }.let {}
        })
    }

    private fun lost(w: WebSocket) {
        if (w !== ws) return
        connected = false
        emitState()
        if (!running || Prefs.token == null) return
        main.removeCallbacks(retry)
        main.postDelayed(retry, backoff)
        backoff = (backoff * 2).coerceAtMost(5000)
    }

    private fun handle(m: JSONObject) {
        when (m.optString("type")) {
            "hello" -> {
                connected = true
                backoff = 1000
                myId = m.getJSONObject("me").getString("id")
                myName = m.getJSONObject("me").getString("name")
                ice = m.getJSONArray("ice")
                Tone.sync(m.optJSONObject("ringtone"))
                PushService.register()
                if (CallManager.state == CallManager.State.IDLE) send(JSONObject().put("type", "idle"))
                emitState()
                sendCrash()
            }
            "contacts" -> {
                val a = m.getJSONArray("list")
                contacts = (0 until a.length()).map { a.getJSONObject(it) }
                    .map { Contact(it.getString("id"), it.getString("name"), it.getBoolean("online"), it.optLong("lastSeen")) }
                emitState()
            }
            "history" -> {
                val a = m.getJSONArray("list")
                history = (0 until a.length()).map { a.getJSONObject(it) }.map {
                    val mem = it.optJSONArray("members")
                    CallEntry(it.getString("id"), it.getString("peer"), it.getString("name"), it.getString("dir") == "in",
                        it.getBoolean("video"), it.getLong("started"), it.getLong("duration"), it.getString("status"),
                        if (mem == null) listOf(it.getString("peer")) else (0 until mem.length()).map { i -> mem.getString(i) })
                }
                val t = m.optJSONObject("totals") ?: JSONObject()
                totals = t.keys().asSequence().associateWith { t.getLong(it) }
                emitState()
            }
            "ringtone" -> Tone.sync(m.optJSONObject("ringtone"))
            "banned" -> {
                banned = true
                stop()
                emitState()
            }
            "denied" -> rejoin() // account gone on the server: register this phone again, quietly
            "pong" -> {
                lastPong = android.os.SystemClock.elapsedRealtime()
                pongWaiter?.let { main.removeCallbacks(it); pongWaiter = null; it.run() }
            }
        }
        listeners.toList().forEach { it.onMessage(m) }
    }

    var banned = false; private set

    /** App came to the foreground: make sure the link is alive now, not at the next alarm. */
    fun ensureFresh() {
        if (!running || Prefs.token == null) return
        backoff = 1000
        heartbeat {}
    }

    @Volatile private var lastPong = 0L
    private var pongWaiter: Runnable? = null
    private val heartbeatDone = mutableListOf<() -> Unit>()

    /**
     * Called by the keep-alive alarm every ~45 s, also while the screen is off. Sends a ping and
     * reconnects if no pong comes back: old phones otherwise keep a dead socket for minutes and
     * incoming calls arrive late.
     */
    fun heartbeat(done: () -> Unit) {
        main.post {
            if (!running || Prefs.token == null) return@post done()
            if (!connected) { reconnectNow(); main.postDelayed(done, 5000); return@post }
            heartbeatDone.add(done)
            if (pongWaiter != null) return@post // a ping is already in flight: wait for its answer
            val sentAt = android.os.SystemClock.elapsedRealtime()
            // pong -> done right away (CPU sleeps again); no pong in 8 s -> the socket is dead
            val waiter = Runnable {
                pongWaiter = null
                if (running && lastPong < sentAt) { connected = false; emitState(); reconnectNow() }
                heartbeatDone.toList().also { heartbeatDone.clear() }.forEach { it() }
            }
            pongWaiter = waiter
            send(JSONObject().put("type", "ping"))
            main.postDelayed(waiter, 8000)
        }
    }

    private fun rejoin() {
        val name = Prefs.name; val code = Prefs.code
        ws?.cancel(); ws = null; connected = false
        if (name == null || code == null) { Prefs.token = null; stop(); emitState(); return }
        Thread {
            val status = join(App.ctx, name, code)
            main.post {
                when (status) {
                    200 -> reconnectNow()
                    403, 451 -> { Prefs.token = null; banned = status == 451; stop(); emitState() }
                    else -> main.postDelayed(retry, 5000) // network trouble: try again later
                }
            }
        }.start()
    }

    /** Uploads a crash report saved by App's uncaught-exception handler, then deletes it. */
    private fun sendCrash() {
        val f = java.io.File(App.ctx.filesDir, "crash.txt")
        val token = Prefs.token ?: return
        if (!f.exists()) return
        Thread {
            try {
                val body = f.readText().toRequestBody("text/plain".toMediaType())
                Net.http.newCall(Request.Builder().url(BuildConfig.SERVER + "/app/crash")
                    .header("Authorization", "Bearer $token").post(body).build()).execute().close()
                f.delete()
            } catch (_: Exception) {}
        }.start()
    }

    fun send(m: JSONObject): Boolean = connected && ws?.send(m.toString()) == true

    fun deviceName(): String {
        val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, true)) Build.MODEL else "${Build.MANUFACTURER} ${Build.MODEL}"
        return "$model · Android ${Build.VERSION.RELEASE}"
    }

    /** Registers this device. Runs on a background thread; returns HTTP status (200 ok). */
    fun join(c: Context, name: String, code: String): Int {
        val body = JSONObject()
            .put("name", name).put("code", code)
            .put("device", deviceName())
            .put("deviceId", Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID))
            .toString().toRequestBody("application/json".toMediaType())
        return try {
            Net.http.newCall(Request.Builder().url(BuildConfig.SERVER + "/join").post(body).build()).execute().use { r ->
                if (r.isSuccessful) {
                    Prefs.token = JSONObject(r.body!!.string()).getString("token")
                    Prefs.name = name
                    Prefs.code = code
                }
                r.code
            }
        } catch (e: IOException) {
            android.util.Log.w("Call", "join failed", e)
            0
        }
    }
}
