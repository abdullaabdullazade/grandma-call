package az.zeng

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/** Forwards frames to whichever renderer is currently attached (views come and go). */
class ProxySink : VideoSink {
    @Volatile var target: VideoSink? = null
    override fun onFrame(f: VideoFrame) { target?.onFrame(f) }
}

/** One other person in the call: either still ringing, or connected through its own PeerConnection. */
class Peer(val id: String, var name: String) {
    enum class State { RINGING, CONNECTING, CONNECTED, RECONNECTING }

    var state = State.RINGING
    var pc: PeerConnection? = null
    var offerer = false                  // we sent the first offer, so we drive ICE restarts
    var videoTransceiver: RtpTransceiver? = null
    var remoteTrack: VideoTrack? = null
    var remoteVideo = false
    var configured = false
    val sink = ProxySink()
    val pendingIce = mutableListOf<IceCandidate>()
    val pendingSignals = mutableListOf<JSONObject>()
}

/**
 * The current call (at most one). A call is a room on the server; each other member is a [Peer]
 * with its own WebRTC connection (mesh). Everything runs on the main thread.
 */
object CallManager : Signaling.Listener {
    enum class State { IDLE, OUTGOING, RINGING, INCOMING, CONNECTING, ACTIVE, RECONNECTING, ENDED }

    const val MAX_PEERS = 3 // + me = 4

    var onUpdate: (() -> Unit)? = null
    var onNotice: ((String) -> Unit)? = null

    var state = State.IDLE; private set
    var callId = ""; private set
    var videoCall = false; private set
    var localVideo = false; private set
    var muted = false; private set
    var frontCamera = true; private set
    var connectedAt = 0L; private set
    var endReason = ""; private set
    /** Incoming: who is calling us and who is already in the call. */
    var inviterId = ""; private set
    var inviterName = ""; private set
    var otherMembers = listOf<String>(); private set

    val peers = LinkedHashMap<String, Peer>()
    val localSink = ProxySink()

    private val main = Handler(Looper.getMainLooper())
    val egl: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(App.ctx).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(App.ctx)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var capturing = false
    private var surfaceHelper: SurfaceTextureHelper? = null

    /** Regular phone (GSM) call in progress or ringing: we must not ring over it or keep the mic. */
    var gsmBusy = false; private set

    @Suppress("DEPRECATION")
    fun init() {
        Signaling.add(this)
        val tm = App.ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        try {
            phoneListener = object : android.telephony.PhoneStateListener() {
                override fun onCallStateChanged(s: Int, n: String?) {
                    gsmBusy = s != android.telephony.TelephonyManager.CALL_STATE_IDLE
                    when {
                        // a normal call starts ringing while ours rings: ours answers "busy"
                        gsmBusy && state == State.INCOMING -> {
                            Signaling.send(JSONObject().put("type", "busy").put("callId", callId)); remember(callId); finish("declined_me")
                        }
                        // the user picked up a normal call: end ours so the mic and audio are free
                        s == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK &&
                            state in setOf(State.OUTGOING, State.RINGING, State.CONNECTING, State.ACTIVE, State.RECONNECTING) -> hangup()
                    }
                }
            }
            tm?.listen(phoneListener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
        } catch (_: Exception) {}
    }

    // Android keeps only a weak reference to the listener: hold it here or it gets collected.
    @Suppress("DEPRECATION")
    private var phoneListener: android.telephony.PhoneStateListener? = null

    /** Header text: one name, or everyone in a group call. */
    fun title(): String = when {
        state == State.INCOMING -> inviterName
        peers.isEmpty() -> inviterName
        else -> peers.values.joinToString(", ") { it.name }
    }

    val isGroup get() = peers.size > 1 || (state == State.INCOMING && otherMembers.size > 1)

    private fun update() {
        onUpdate?.invoke()
        ZengService.refresh()
    }

    private fun set(s: State) {
        state = s
        when (s) {
            State.RINGING -> Ringer.ringback()
            State.CONNECTING, State.ACTIVE, State.RECONNECTING -> Ringer.stop()
            else -> {}
        }
        update()
    }

    /** Derive the overall state from the peers once the call is underway. */
    private fun recompute() {
        if (state == State.IDLE || state == State.INCOMING || state == State.ENDED) return
        val ps = peers.values
        val next = when {
            ps.any { it.state == Peer.State.CONNECTED } -> State.ACTIVE
            connectedAt > 0 && ps.any { it.state == Peer.State.RECONNECTING } -> State.RECONNECTING
            ps.any { it.state != Peer.State.RINGING } -> State.CONNECTING
            state == State.RINGING -> State.RINGING
            else -> State.OUTGOING
        }
        if (next == State.ACTIVE && connectedAt == 0L) connectedAt = SystemClock.elapsedRealtime()
        if (next == State.ACTIVE) main.removeCallbacks(giveUp)
        if (next != state) set(next) else update()
    }

    // ---------- starting / answering ----------

    fun startCall(c: Contact, video: Boolean) {
        if (state != State.IDLE) return
        reset()
        callId = java.util.UUID.randomUUID().toString()
        videoCall = video; localVideo = video
        peers[c.id] = Peer(c.id, c.name)
        AudioRouter.start(useSpeaker = video)
        if (video) startCamera()
        set(State.OUTGOING)
        main.removeCallbacks(dialTimeout)
        main.postDelayed(dialTimeout, 60000)
        openCallScreen()
        val sent = Signaling.send(JSONObject().put("type", "call").put("to", c.id).put("callId", callId).put("video", video))
        if (!sent) finish("offline")
    }

    /** Call back a group: ring the first person, then invite the rest into the same call. */
    fun startGroup(list: List<Contact>, video: Boolean) {
        if (list.isEmpty() || state != State.IDLE) return
        startCall(list[0], video)
        if (state == State.ENDED) return
        list.drop(1).take(MAX_PEERS - 1).forEach { invite(it) }
    }

    /** Ring one more person into the current call. */
    fun invite(c: Contact) {
        if (peers.size >= MAX_PEERS || peers.containsKey(c.id)) return
        if (state !in setOf(State.CONNECTING, State.ACTIVE, State.RECONNECTING, State.RINGING, State.OUTGOING)) return
        peers[c.id] = Peer(c.id, c.name)
        Signaling.send(JSONObject().put("type", "invite").put("callId", callId).put("to", c.id))
        update()
    }

    fun accept(withVideo: Boolean) {
        if (state != State.INCOMING) return
        Ringer.stop()
        localVideo = withVideo && videoCall
        AudioRouter.start(useSpeaker = videoCall)
        if (localVideo) startCamera()
        main.removeCallbacks(ringTimeout)
        sendAccept()
        set(State.CONNECTING)
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, 45000) // never sit on "Connecting…" forever
    }

    /** Calls we already turned down; if the server re-sends one after a reconnect we decline again. */
    private val declined = LinkedHashSet<String>()

    private fun remember(id: String) {
        declined.add(id)
        while (declined.size > 20) declined.remove(declined.first())
    }

    fun decline() {
        if (state != State.INCOMING) return
        remember(callId)
        Signaling.send(JSONObject().put("type", "decline").put("callId", callId))
        finish("declined_me")
    }

    fun hangup() {
        if (state == State.IDLE || state == State.ENDED) return
        if (state == State.INCOMING) return decline()
        Signaling.send(JSONObject().put("type", "hangup").put("callId", callId))
        finish("hangup_me")
    }

    // ---------- in-call controls ----------

    fun toggleMute() {
        muted = !muted
        audioTrack?.setEnabled(!muted)
        update()
    }

    fun toggleSpeaker() = AudioRouter.setSpeaker(!AudioRouter.speaker).also { update() }

    fun toggleVideo() {
        localVideo = !localVideo
        if (localVideo) {
            startCamera()
            AudioRouter.autoSpeaker()
        } else stopCamera()
        for (p in peers.values) {
            p.videoTransceiver?.sender?.setTrack(if (localVideo) videoTrack else null, false)
            sendMedia(p)
        }
        updateVideoRouting()
        update()
    }

    fun switchCamera() {
        (capturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) = main.post { frontCamera = front; update() }.let {}
            override fun onCameraSwitchError(e: String?) {}
        })
    }

    private fun updateVideoRouting() {
        AudioRouter.videoActive = localVideo || peers.values.any { it.remoteVideo }
    }

    // ---------- signaling ----------

    private var wasConnected = false

    private var acceptPending = false   // accept couldn't be sent yet (socket down)
    private var awaitingReRing = false  // reconnected while ringing: is the call still alive?

    override fun onState() {
        val reconnected = Signaling.connected && !wasConnected
        wasConnected = Signaling.connected
        if (!reconnected) return
        when (state) {
            State.CONNECTING, State.ACTIVE, State.RECONNECTING -> {
                if (acceptPending) sendAccept()
                Signaling.send(JSONObject().put("type", "resume").put("callId", callId))
                // our own ICE restarts were lost while offline: re-offer to every peer we drive
                peers.values.filter { it.offerer && it.pc != null && it.state != Peer.State.CONNECTED }.forEach { offer(it, true) }
            }
            State.OUTGOING, State.RINGING -> if (peers.values.none { it.pc != null }) {
                // the server dropped the ringing call while we were offline
                Signaling.send(JSONObject().put("type", "hangup").put("callId", callId))
                finish("lost")
            }
            State.INCOMING -> {
                // the server re-sends "incoming" right after auth if the call still exists
                awaitingReRing = true
                main.removeCallbacks(reRingCheck)
                main.postDelayed(reRingCheck, 5000)
            }
            else -> {}
        }
    }

    private val reRingCheck = Runnable { if (awaitingReRing && state == State.INCOMING) { awaitingReRing = false; finish("hangup") } }

    // Safety nets: never ring or "call" forever, even if the server's "ended" was lost.
    private val ringTimeout = Runnable { if (state == State.INCOMING) finish("no_answer") }
    private val dialTimeout = Runnable {
        if ((state == State.OUTGOING || state == State.RINGING) && peers.values.none { it.pc != null }) hangupAs("no_answer")
    }

    private fun hangupAs(reason: String) {
        Signaling.send(JSONObject().put("type", "hangup").put("callId", callId))
        finish(reason)
    }

    private fun sendAccept() {
        acceptPending = !Signaling.send(JSONObject().put("type", "accept").put("callId", callId))
    }

    override fun onMessage(m: JSONObject) {
        val id = m.optString("callId")
        when (m.optString("type")) {
            "incoming" -> {
                if (gsmBusy) { Signaling.send(JSONObject().put("type", "busy").put("callId", id)); return }
                if (id in declined) { Signaling.send(JSONObject().put("type", "decline").put("callId", id)); return } // re-delivered after reconnect
                if (state == State.ENDED) { main.removeCallbacksAndMessages(ENDED_TOKEN); state = State.IDLE; peers.clear() }
                if (state != State.IDLE) {
                    if (id != callId) Signaling.send(JSONObject().put("type", "busy").put("callId", id))
                    else if (state == State.INCOMING) { awaitingReRing = false; ringAck() } // re-delivered after a reconnect
                    else if (acceptPending) sendAccept()
                    return
                }
                reset()
                callId = id
                inviterId = m.getString("from"); inviterName = m.getString("name")
                videoCall = m.optBoolean("video")
                val a = m.optJSONArray("members") ?: JSONArray()
                otherMembers = (0 until a.length()).map { a.getString(it) }
                set(State.INCOMING)
                ringAck()
                Ringer.ringIncoming()
                main.removeCallbacks(ringTimeout)
                main.postDelayed(ringTimeout, 50000)
                openCallScreen()
            }
            "ringing" -> if (id == callId) {
                peers[m.optString("peer")]?.let { if (it.state == Peer.State.RINGING && state == State.OUTGOING) set(State.RINGING) }
            }
            "joined" -> if (id == callId) { // we answered: offer to everyone already talking
                val a = m.getJSONArray("peers")
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val p = peers.getOrPut(o.getString("id")) { Peer(o.getString("id"), o.getString("name")) }
                    p.offerer = true
                    p.state = Peer.State.CONNECTING
                    createPeer(p)
                    offer(p, false)
                }
                recompute()
            }
            "peer_joined" -> if (id == callId) { // someone answered: wait for their offer
                val p = peers.getOrPut(m.getString("peer")) { Peer(m.getString("peer"), m.getString("name")) }
                if (p.name.isEmpty()) p.name = m.getString("name")
                p.offerer = false
                p.state = Peer.State.CONNECTING
                createPeer(p)
                p.pendingSignals.toList().also { p.pendingSignals.clear() }.forEach { onSignal(p, it) }
                recompute()
            }
            "signal" -> if (id == callId) {
                val from = m.optString("from")
                val p = peers.getOrPut(from) { Peer(from, "") }
                val d = m.getJSONObject("data")
                if (p.pc == null) p.pendingSignals.add(d) else onSignal(p, d)
            }
            "peer_left" -> if (id == callId) {
                val p = peers.remove(m.optString("peer")) ?: return
                closePeer(p)
                noticeFor(p.name, m.optString("reason"))?.let { onNotice?.invoke(it) }
                updateVideoRouting()
                recompute()
            }
            "resumed" -> if (id == callId) {
                peers[m.optString("peer")]?.let { if (it.offerer && it.state != Peer.State.CONNECTED) offer(it, true) }
            }
            "ended" -> if (id == callId && state != State.IDLE && state != State.ENDED) finish(m.optString("reason"))
        }
    }

    /** Tell the server this phone is really ringing; otherwise it treats the socket as dead and re-delivers. */
    private fun ringAck() = Signaling.send(JSONObject().put("type", "ring_ack").put("callId", callId))

    private fun noticeFor(name: String, reason: String): String? = when (reason) {
        "busy" -> L.s(R.string.peer_busy, name)
        "declined" -> L.s(R.string.peer_declined, name)
        "no_answer" -> L.s(R.string.peer_no_answer, name)
        "hangup" -> L.s(R.string.peer_left, name)
        "lost" -> L.s(R.string.peer_lost, name)
        else -> null
    }

    private fun send(p: Peer, d: JSONObject) =
        Signaling.send(JSONObject().put("type", "signal").put("callId", callId).put("to", p.id).put("data", d))

    private fun sendMedia(p: Peer) = send(p, JSONObject().put("media", JSONObject().put("video", localVideo)))

    private fun onSignal(p: Peer, d: JSONObject) {
        val pc = p.pc ?: return
        d.optJSONObject("sdp")?.let { s ->
            val type = SessionDescription.Type.fromCanonicalForm(s.getString("type"))
            pc.setRemoteDescription(Sdp(onSet = {
                main.post {
                    if (p.pc !== pc) return@post // peer closed meanwhile: pc is disposed
                    p.pendingIce.forEach { pc.addIceCandidate(it) }
                    p.pendingIce.clear()
                    if (type == SessionDescription.Type.OFFER) answer(p)
                }
            }), SessionDescription(type, s.getString("sdp")))
        }
        d.optJSONObject("ice")?.let {
            val c = IceCandidate(it.getString("sdpMid"), it.getInt("sdpMLineIndex"), it.getString("candidate"))
            if (pc.remoteDescription == null) p.pendingIce.add(c) else pc.addIceCandidate(c)
        }
        d.optJSONObject("media")?.let {
            p.remoteVideo = it.getBoolean("video")
            updateVideoRouting()
            if (p.remoteVideo && !localVideo) AudioRouter.autoSpeaker()
            update()
        }
    }

    // ---------- WebRTC ----------

    private fun iceServers(): List<PeerConnection.IceServer> {
        val a = Signaling.ice
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val u = o.get("urls")
            val urls = if (u is JSONArray) (0 until u.length()).map { u.getString(it) } else listOf(u.toString())
            PeerConnection.IceServer.builder(urls)
                .setUsername(o.optString("username"))
                .setPassword(o.optString("credential"))
                .createIceServer()
        }
    }

    private fun ensureAudio() {
        if (audioTrack != null) return
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("a0", audioSource).apply { setEnabled(!muted) }
    }

    private fun createPeer(p: Peer) {
        if (p.pc != null) return
        ensureAudio()
        val cfg = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(cfg, PeerObserver(p)) ?: return
        p.pc = pc
        if (p.offerer) {
            // Audio + an always-present video transceiver: camera on/off never needs renegotiation.
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("s")))
                .sender.setTrack(audioTrack, false)
            p.videoTransceiver = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("s")))
            if (localVideo) p.videoTransceiver?.sender?.setTrack(videoTrack, false)
            p.configured = true
        }
    }

    /** Answerer: attach our tracks to the transceivers the offer created. */
    private fun configureAnswerer(p: Peer) {
        if (p.configured) return
        p.configured = true
        for (t in p.pc!!.transceivers) {
            t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            when (t.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> t.sender.setTrack(audioTrack, false)
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    p.videoTransceiver = t
                    if (localVideo) t.sender.setTrack(videoTrack, false)
                }
                else -> {}
            }
        }
    }

    private fun offer(p: Peer, iceRestart: Boolean) {
        val pc = p.pc ?: return
        val mc = MediaConstraints()
        if (iceRestart) mc.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        pc.createOffer(Sdp(onCreate = { sdp ->
            pc.setLocalDescription(Sdp(onSet = {
                main.post { send(p, JSONObject().put("sdp", JSONObject().put("type", "offer").put("sdp", sdp.description))) }
            }), sdp)
        }), mc)
    }

    private fun answer(p: Peer) {
        val pc = p.pc ?: return
        configureAnswerer(p)
        pc.createAnswer(Sdp(onCreate = { sdp ->
            pc.setLocalDescription(Sdp(onSet = {
                main.post { send(p, JSONObject().put("sdp", JSONObject().put("type", "answer").put("sdp", sdp.description))) }
            }), sdp)
        }), MediaConstraints())
    }

    private val giveUp = Runnable {
        if (peers.values.none { it.state == Peer.State.CONNECTED } && state in setOf(State.CONNECTING, State.RECONNECTING)) hangup()
    }

    private class PeerObserver(val p: Peer) : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = CallManager.main.post {
            CallManager.send(p, JSONObject().put("ice", JSONObject()
                .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp)))
        }.let {}

        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = CallManager.main.post {
            CallManager.onIce(p, s)
        }.let {}

        override fun onTrack(t: RtpTransceiver) = CallManager.main.post {
            if (p.pc == null || CallManager.peers[p.id] !== p) return@post
            val track = t.receiver.track()
            if (track is VideoTrack) {
                p.remoteTrack = track
                track.addSink(p.sink)
            }
        }.let {}

        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
    }

    private fun onIce(p: Peer, s: PeerConnection.IceConnectionState) {
        if (p.pc == null || peers[p.id] !== p) return
        when (s) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                if (p.state != Peer.State.CONNECTED) {
                    p.state = Peer.State.CONNECTED
                    if (localVideo) sendMedia(p)
                }
            }
            PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED -> {
                p.state = Peer.State.RECONNECTING
                if (p.offerer) main.postDelayed({
                    if (peers[p.id] === p && p.state == Peer.State.RECONNECTING) offer(p, true)
                }, if (s == PeerConnection.IceConnectionState.FAILED) 0 else 3000)
                main.removeCallbacks(giveUp)
                main.postDelayed(giveUp, 45000)
            }
            else -> {}
        }
        recompute()
    }

    private class Sdp(val onCreate: (SessionDescription) -> Unit = {}, val onSet: () -> Unit = {}) : SdpObserver {
        override fun onCreateSuccess(s: SessionDescription) = onCreate(s)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    // ---------- camera ----------

    private fun startCamera() {
        if (videoTrack == null) {
            val e = if (Camera2Enumerator.isSupported(App.ctx)) Camera2Enumerator(App.ctx) else Camera1Enumerator(true)
            val name = e.deviceNames.firstOrNull { e.isFrontFacing(it) } ?: e.deviceNames.firstOrNull() ?: return
            frontCamera = e.isFrontFacing(name)
            val cap = e.createCapturer(name, null) ?: return
            surfaceHelper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
            videoSource = factory.createVideoSource(false)
            cap.initialize(surfaceHelper, App.ctx, videoSource!!.capturerObserver)
            capturer = cap
            videoTrack = factory.createVideoTrack("v0", videoSource).apply { addSink(localSink) }
        }
        if (!capturing) {
            // modest resolution: old phones (Galaxy A3 2015) encode it smoothly, even to several peers
            capturer?.startCapture(640, 480, if (peers.size > 1) 15 else 24)
            capturing = true
        }
    }

    private fun stopCamera() {
        if (capturing) try { capturer?.stopCapture() } catch (_: InterruptedException) {}
        capturing = false
    }

    // ---------- teardown ----------

    private fun closePeer(p: Peer) {
        p.sink.target = null
        p.remoteTrack?.removeSink(p.sink)
        p.remoteTrack = null
        p.pc?.dispose()
        p.pc = null
    }

    private val ENDED_TOKEN = Any()

    private fun finish(reason: String) {
        endReason = reason
        acceptPending = false; awaitingReRing = false
        main.removeCallbacks(giveUp); main.removeCallbacks(ringTimeout); main.removeCallbacks(dialTimeout); main.removeCallbacks(reRingCheck)
        peers.values.forEach(::closePeer)
        release()
        AudioRouter.stop()
        when (reason) {
            "busy" -> Ringer.busy()
            "declined_me", "answered_elsewhere" -> Ringer.stop()
            else -> Ringer.ended()
        }
        state = State.ENDED
        update()
        main.postAtTime({ if (state == State.ENDED) { state = State.IDLE; peers.clear(); update() } }, ENDED_TOKEN,
            SystemClock.uptimeMillis() + 2000)
    }

    private fun release() {
        stopCamera()
        videoTrack?.dispose(); videoTrack = null
        capturer?.dispose(); capturer = null
        videoSource?.dispose(); videoSource = null
        surfaceHelper?.dispose(); surfaceHelper = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
    }

    private fun reset() {
        peers.clear()
        muted = false; localVideo = false
        connectedAt = 0; endReason = ""
        inviterId = ""; inviterName = ""; otherMembers = listOf()
        AudioRouter.videoActive = false
    }

    private fun openCallScreen() {
        val i = Intent(App.ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // On Android 10+ this is ignored when we're in background; ZengService's full-screen notification covers it.
        App.ctx.startActivity(i)
    }
}
