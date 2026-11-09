package az.zeng

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.view.MotionEvent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import az.zeng.CallManager.State
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Full-screen call UI: incoming, outgoing, 1:1 or group call, and the short "call ended" screen. */
class CallActivity : BaseActivity() {
    private lateinit var grid: LinearLayout
    private lateinit var local: SurfaceViewRenderer      // small PiP renderer
    private lateinit var localBig: SurfaceViewRenderer   // my camera full screen
    private lateinit var pipBox: View
    private var swapped = false   // 1:1 video: I'm big, they're small
    private var pipMode = false   // Android picture-in-picture window
    private val tick = Handler(Looper.getMainLooper())
    private var controlsHidden = false
    private var answerVideoPending = false

    /** One square per other person: their video, or their avatar while audio-only/ringing. */
    private inner class Tile(val peerId: String) {
        val root = FrameLayout(this@CallActivity)
        val video = SurfaceViewRenderer(this@CallActivity)
        val avatar = TextView(this@CallActivity)
        val name = TextView(this@CallActivity)
        val status = TextView(this@CallActivity)
        val label = TextView(this@CallActivity)
        private val box = LinearLayout(this@CallActivity)

        private var ready = false

        /** EGL context + render thread only once this tile shows video (old phones have little RAM). */
        fun ensureVideo() {
            if (ready) return
            ready = true
            video.init(CallManager.egl.eglBaseContext, null)
            video.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            video.setEnableHardwareScaler(true)
        }

        init {
            video.visibility = View.GONE
            video.setOnClickListener { controlsHidden = !controlsHidden; render() }
            root.addView(video, FrameLayout.LayoutParams(-1, -1))

            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            avatar.gravity = Gravity.CENTER
            avatar.setTextColor(Color.WHITE)
            avatar.paint.isFakeBoldText = true
            box.addView(avatar)
            for (t in listOf(name, status)) {
                t.setTextColor(Color.WHITE); t.gravity = Gravity.CENTER
                t.setPadding(0, Ui.dp(this@CallActivity, 6), 0, 0)
                box.addView(t)
            }
            name.textSize = 18f; name.paint.isFakeBoldText = true
            status.textSize = 14f; status.alpha = 0.8f
            root.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

            label.setTextColor(Color.WHITE); label.textSize = 14f
            label.setShadowLayer(6f, 0f, 0f, Color.BLACK)
            val pad = Ui.dp(this@CallActivity, 10)
            label.setPadding(pad, pad, pad, pad)
            root.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        }

        fun bind(p: Peer, single: Boolean, me: Boolean = false) {
            val showVideo = if (me) CallManager.localVideo else p.remoteVideo && p.state != Peer.State.RINGING
            if (showVideo) ensureVideo()
            if (!me) p.sink.target = if (ready) video else null
            video.setMirror(me && CallManager.frontCamera)
            video.visibility = if (showVideo) View.VISIBLE else View.GONE
            box.visibility = if (showVideo) View.GONE else View.VISIBLE
            val size = Ui.dp(this@CallActivity, if (single) 140 else 84)
            avatar.layoutParams = LinearLayout.LayoutParams(size, size)
            avatar.textSize = if (single) 56f else 32f
            Ui.avatar(avatar, p.id, p.name)
            // 1:1 calls show name/status in the header; group tiles carry their own
            name.visibility = if (single) View.GONE else View.VISIBLE
            status.visibility = if (single) View.GONE else View.VISIBLE
            name.text = p.name
            status.text = if (me) "" else when (p.state) {
                Peer.State.RINGING -> getString(R.string.ringing)
                Peer.State.CONNECTING -> getString(R.string.connecting)
                Peer.State.RECONNECTING -> getString(R.string.reconnecting)
                Peer.State.CONNECTED -> ""
            }
            label.visibility = if (showVideo && !single) View.VISIBLE else View.GONE
            label.text = p.name
            root.setBackgroundColor(if (single) 0 else 0xFF1F2C34.toInt())
        }

        fun release() {
            CallManager.peers[peerId]?.sink?.let { if (it.target === video) it.target = null }
            if (ready) video.release()
        }
    }

    private val tiles = LinkedHashMap<String, Tile>()
    private var layoutKey = ""

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_call)

        grid = findViewById(R.id.grid)
        local = findViewById(R.id.local)
        local.setZOrderMediaOverlay(true)
        localBig = findViewById(R.id.localBig)
        localBig.setOnClickListener { controlsHidden = !controlsHidden; render() }
        pipBox = findViewById(R.id.pipBox)
        setupPipDrag()
        findViewById<View>(R.id.minimize).setOnClickListener { minimize() }

        findViewById<View>(R.id.answer).setOnClickListener { answer(false) }
        findViewById<View>(R.id.answerVideo).setOnClickListener { answer(true) }
        findViewById<View>(R.id.decline).setOnClickListener { CallManager.decline() }
        findViewById<View>(R.id.hangup).setOnClickListener { CallManager.hangup() }
        findViewById<View>(R.id.mute).setOnClickListener { CallManager.toggleMute() }
        findViewById<View>(R.id.speaker).setOnClickListener { CallManager.toggleSpeaker() }
        findViewById<View>(R.id.flip).setOnClickListener { CallManager.switchCamera() }
        findViewById<View>(R.id.video).setOnClickListener { toggleVideo() }
        findViewById<View>(R.id.add).setOnClickListener { pickPerson() }
        grid.setOnClickListener { controlsHidden = !controlsHidden; render() }

        if (intent.getBooleanExtra(EXTRA_ANSWER, false)) answer(false)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (i.getBooleanExtra(EXTRA_ANSWER, false)) answer(false)
    }

    override fun onStart() {
        super.onStart()
        CallManager.onUpdate = { render() }
        CallManager.onNotice = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        AudioRouter.onChange = { render() }
        render()
        tick.post(clock)
    }

    override fun onStop() {
        tick.removeCallbacks(clock)
        CallManager.onUpdate = null
        CallManager.onNotice = null
        AudioRouter.onChange = null
        super.onStop()
    }

    override fun onDestroy() {
        CallManager.localSink.target = null
        tiles.values.forEach { it.release() }
        tiles.clear()
        if (localReady) { local.release(); localBig.release() }
        super.onDestroy()
    }

    // Back leaves the call running: floating window for video calls, background otherwise.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (CallManager.state == State.INCOMING) return
        minimize()
    }

    private fun videoInCall() = CallManager.localVideo || CallManager.peers.values.any { it.remoteVideo }

    private fun minimize() {
        if (!enterPip()) moveTaskToBack(true)
    }

    private fun enterPip(): Boolean {
        val active = CallManager.state in setOf(State.CONNECTING, State.ACTIVE, State.RECONNECTING)
        if (Build.VERSION.SDK_INT < 26 || !active || !videoInCall()) return false
        return try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build())
        } catch (_: Exception) { false }
    }

    // Home button during a video call: shrink into a floating window like WhatsApp.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(inPip, cfg)
        pipMode = inPip
        render()
    }

    /** Drag the small window to either side; a tap swaps big and small. */
    private fun setupPipDrag() {
        var dx = 0f; var dy = 0f; var sx = 0f; var sy = 0f; var moved = false
        pipBox.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = v.translationX - e.rawX; dy = v.translationY - e.rawY; sx = e.rawX; sy = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - sx) + Math.abs(e.rawY - sy) > Ui.dp(this, 8)) moved = true
                    v.translationX = e.rawX + dx; v.translationY = e.rawY + dy
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { swapped = !swapped; render() }
                    else {
                        val parent = v.parent as View
                        val margin = Ui.dp(this, 12).toFloat()
                        val left = v.left + v.translationX + v.width / 2 < parent.width / 2
                        val targetX = if (left) margin - v.left else 0f
                        val minY = -v.top + margin * 3
                        val maxY = parent.height - v.bottom - Ui.dp(this, 150).toFloat()
                        v.animate().translationX(targetX).translationY(v.translationY.coerceIn(minY, maxY)).setDuration(150).start()
                    }
                }
            }
            true
        }
    }

    private fun answer(video: Boolean) {
        if (CallManager.state != State.INCOMING) return
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) need += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 23 && need.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            answerVideoPending = video
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        CallManager.accept(video)
    }

    private fun toggleVideo() {
        if (!CallManager.localVideo && Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
            return
        }
        CallManager.toggleVideo()
    }

    /** "Add person": contacts of my group who aren't in the call yet. */
    private fun pickPerson() {
        if (CallManager.peers.size >= CallManager.MAX_PEERS) {
            Toast.makeText(this, R.string.call_full, Toast.LENGTH_SHORT).show()
            return
        }
        val list = Signaling.contacts
            .filter { it.id != Signaling.myId && !CallManager.peers.containsKey(it.id) }
            .sortedWith(compareByDescending<Contact> { it.online }.thenBy { it.name.lowercase() })
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.no_one_to_add, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { c -> if (c.online) "● ${c.name}" else "○ ${c.name}" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.add_person)
            .setItems(labels) { _, i -> CallManager.invite(list[i]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        if (Build.VERSION.SDK_INT < 23) return
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        when (req) {
            1 -> if (mic) CallManager.accept(answerVideoPending && cam)
            else { Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show(); CallManager.decline() }
            2 -> if (cam) CallManager.toggleVideo()
        }
    }

    private val clock = object : Runnable {
        override fun run() {
            if (CallManager.state == State.ACTIVE) status()
            tick.postDelayed(this, 1000)
        }
    }

    private fun status() {
        val cm = CallManager
        findViewById<TextView>(R.id.status).text = when (cm.state) {
            State.OUTGOING -> getString(R.string.calling)
            State.RINGING -> getString(R.string.ringing)
            State.INCOMING -> when {
                cm.otherMembers.size > 1 -> getString(R.string.incoming_group, cm.inviterName)
                cm.videoCall -> getString(R.string.incoming_video)
                else -> getString(R.string.incoming_voice)
            }
            State.CONNECTING -> getString(R.string.connecting)
            State.ACTIVE -> Ui.clock((SystemClock.elapsedRealtime() - cm.connectedAt) / 1000)
            State.RECONNECTING -> getString(R.string.reconnecting)
            State.ENDED -> Ui.endText(cm.endReason)
            State.IDLE -> ""
        }
    }

    private fun render() {
        val cm = CallManager
        if (cm.state == State.IDLE) { finish(); return }
        val incoming = cm.state == State.INCOMING
        val inCall = cm.state in setOf(State.OUTGOING, State.RINGING, State.CONNECTING, State.ACTIVE, State.RECONNECTING)

        findViewById<TextView>(R.id.kind).text = getString(when {
            cm.isGroup -> R.string.group_call
            cm.videoCall -> R.string.video_call
            else -> R.string.voice_call
        })
        findViewById<TextView>(R.id.name).apply {
            text = cm.title()
            textSize = if (cm.isGroup) 20f else 28f
        }
        status()
        renderTiles(incoming)

        routeVideo(incoming)
        val hideUi = controlsHidden || pipMode

        findViewById<View>(R.id.incoming).visibility = if (incoming) View.VISIBLE else View.GONE
        findViewById<View>(R.id.answerVideoBox).visibility = if (incoming && cm.videoCall) View.VISIBLE else View.GONE
        findViewById<View>(R.id.controls).visibility = if (inCall && !hideUi) View.VISIBLE else View.GONE
        findViewById<View>(R.id.header).visibility = if (hideUi) View.GONE else View.VISIBLE
        findViewById<View>(R.id.minimize).visibility = if (inCall && !hideUi) View.VISIBLE else View.GONE
        findViewById<View>(R.id.add).visibility =
            if (inCall && !hideUi && cm.peers.size < CallManager.MAX_PEERS) View.VISIBLE else View.GONE
        if (incoming) findViewById<View>(R.id.incoming).visibility = if (pipMode) View.GONE else View.VISIBLE

        toggle(R.id.speaker, AudioRouter.speaker, R.drawable.ic_speaker)
        toggle(R.id.mute, cm.muted, if (cm.muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        toggle(R.id.video, cm.localVideo, if (cm.localVideo) R.drawable.ic_videocam else R.drawable.ic_videocam_off)
        findViewById<View>(R.id.flipBox).visibility = if (cm.localVideo) View.VISIBLE else View.GONE
    }

    /** 1 person: full screen. 2: stacked. 3: one on top, two below. */
    private fun renderTiles(incoming: Boolean) {
        val group = !incoming && CallManager.peers.size > 1
        val peers = if (incoming) listOf(Peer(CallManager.inviterId, CallManager.inviterName))
                    else CallManager.peers.values.toList() + if (group) listOf(Peer(ME, getString(R.string.you))) else listOf()
        val key = peers.joinToString(",") { it.id }
        if (key != layoutKey) {
            layoutKey = key
            val ids = peers.map { it.id }.toSet()
            tiles.keys.filter { it !in ids }.forEach { tiles.remove(it)?.release() }
            grid.removeAllViews()
            tiles.values.forEach { (it.root.parent as? ViewGroup)?.removeView(it.root) }
            val rows = when (peers.size) {
                0, 1 -> listOf(peers)
                2 -> peers.map { listOf(it) }
                3 -> listOf(peers.take(1), peers.drop(1))
                else -> peers.chunked(2)
            }
            val gap = if (peers.size > 1) Ui.dp(this, 2) else 0
            for (row in rows) {
                val r = LinearLayout(this)
                for (p in row) {
                    val t = tiles.getOrPut(p.id) { Tile(p.id) }
                    r.addView(t.root, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(gap, gap, gap, gap) })
                }
                grid.addView(r, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        val single = peers.size <= 1
        for (p in peers) tiles[p.id]?.bind(p, single, me = p.id == ME)
        // group tiles start under the header; a 1:1 call is full screen with the header on top
        val top = if (group && !pipMode) findViewById<View>(R.id.header).height else 0
        val bottom = if (group && !pipMode) Ui.dp(this, 130) else 0
        grid.setPadding(0, top, 0, bottom)
    }

    /**
     * Where each video goes, like WhatsApp:
     *  group  -> everyone (me included) in the grid;
     *  1:1    -> their video big + me small; tap the small one to swap;
     *            only my camera on -> me full screen.
     */
    private var localReady = false

    /** My own preview views get their EGL context only on the first video call. */
    private fun ensureLocal() {
        if (localReady) return
        localReady = true
        for (v in listOf(local, localBig)) {
            v.init(CallManager.egl.eglBaseContext, null)
            v.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            v.setEnableHardwareScaler(true)
        }
    }

    private fun routeVideo(incoming: Boolean) {
        val cm = CallManager
        if (cm.localVideo || cm.peers.values.any { it.remoteVideo }) ensureLocal()
        val p = cm.peers.values.firstOrNull()
        val group = !incoming && cm.peers.size > 1
        val mine = cm.localVideo && cm.state != State.ENDED
        val theirs = !incoming && p != null && p.remoteVideo && p.state != Peer.State.RINGING
        var bigMe = false; var pip = false
        when {
            group -> cm.localSink.target = tiles[ME]?.let { if (mine) { it.ensureVideo(); it.video } else null }
            mine && theirs && swapped -> { bigMe = true; pip = true; tiles[p!!.id]?.ensureVideo(); p.sink.target = local; cm.localSink.target = localBig }
            mine && theirs -> { pip = true; cm.localSink.target = local }
            mine -> { bigMe = true; cm.localSink.target = localBig }
            else -> cm.localSink.target = null
        }
        if (!(mine && theirs)) swapped = false
        localBig.visibility = if (bigMe) View.VISIBLE else View.GONE
        localBig.setMirror(cm.frontCamera)
        local.setMirror(!swapped && cm.frontCamera)
        pipBox.visibility = if (pip && !pipMode) View.VISIBLE else View.GONE
        // in 1:1 the tile shows their video, or their avatar unless my camera fills the screen
        if (!group && p != null) tiles[p.id]?.let { t ->
            if (swapped) t.video.visibility = View.GONE
            if (bigMe && !theirs) t.root.visibility = View.GONE else t.root.visibility = View.VISIBLE
        }
        if (!mine && !theirs) controlsHidden = false
    }

    /** White filled circle with dark icon = on; translucent circle with white icon = off. */
    private fun toggle(id: Int, on: Boolean, icon: Int) {
        findViewById<ImageButton>(id).apply {
            setImageResource(icon)
            setBackgroundResource(if (on) R.drawable.btn_round_on else R.drawable.btn_round)
            setColorFilter(if (on) 0xFF0B141A.toInt() else Color.WHITE)
        }
    }

    companion object {
        const val EXTRA_ANSWER = "answer"
        private const val ME = "\u0000me"
    }
}
