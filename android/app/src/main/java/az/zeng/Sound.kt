package az.zeng

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/** Incoming ringtone/vibration, outgoing ringback and short status tones. */
object Ringer {
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private val main = Handler(Looper.getMainLooper())
    private val vibrator get() = App.ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val am get() = App.ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun ringIncoming() {
        stop()
        val mode = am.ringerMode
        if (mode != AudioManager.RINGER_MODE_SILENT) vibrate()
        if (mode != AudioManager.RINGER_MODE_NORMAL) return
        // custom tone -> phone's ringtone -> plain beep: the phone must never ring silently
        val custom = Tone.file()
        player = (if (custom != null) play { setDataSource(custom.path) } else null)
            ?: play { setDataSource(App.ctx, defaultUri()) }
        if (player == null) playTone(ToneGenerator.TONE_SUP_RINGTONE, -1, AudioManager.STREAM_RING)
    }

    private fun defaultUri() = RingtoneManager.getActualDefaultRingtoneUri(App.ctx, RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    private fun play(source: MediaPlayer.() -> Unit): MediaPlayer? = try {
        MediaPlayer().apply {
            source()
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            isLooping = true
            setOnErrorListener { mp, _, _ -> // broke while playing: fall back to the phone's ringtone
                mp.release()
                if (player === mp) { player = play { setDataSource(App.ctx, defaultUri()) } }
                true
            }
            prepare()
            start()
        }
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val pattern = longArrayOf(0, 800, 1200)
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        else vibrator.vibrate(pattern, 0)
    }

    /** "tuuut … tuuut" the caller hears while the other phone rings. */
    /** Real network ringback the caller hears: 425 Hz, 1 s on / 4 s off. Keeps playing if already on. */
    fun ringback() {
        if (signal == "ringback") return
        playSignal("ringback", R.raw.ringback, true)
    }

    /** Busy signal: 425 Hz, 0.5 s on / 0.5 s off, three times. */
    fun busy() = playSignal("busy", R.raw.busy, false)

    private var signal: String? = null

    // Call-progress tones go to the call audio path (earpiece/speaker), like on a normal phone call.
    private fun playSignal(name: String, res: Int, loop: Boolean) {
        stop()
        player = try {
            MediaPlayer().apply {
                App.ctx.resources.openRawResourceFd(res).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                isLooping = loop
                if (!loop) setOnCompletionListener { if (player === it) stop() }
                prepare()
                start()
            }
        } catch (e: Exception) { null }
        if (player != null) signal = name
        else playTone(if (name == "busy") ToneGenerator.TONE_SUP_BUSY else ToneGenerator.TONE_SUP_RINGTONE, if (loop) -1 else 2500)
    }
    fun ended() = playTone(ToneGenerator.TONE_PROP_PROMPT, 400)

    private fun playTone(type: Int, ms: Int, stream: Int = AudioManager.STREAM_VOICE_CALL) {
        if (stream == AudioManager.STREAM_VOICE_CALL) stop() else stopTone()
        tone = try { ToneGenerator(stream, if (stream == AudioManager.STREAM_RING) 100 else 70) } catch (e: Exception) { null }
        tone?.startTone(type)
        if (ms > 0) main.postDelayed({ stopTone() }, ms.toLong())
    }

    private fun stopTone() {
        tone?.release()
        tone = null
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        signal = null
        player?.run { try { stop() } catch (_: Exception) {}; release() }
        player = null
        stopTone()
        vibrator.cancel()
    }
}

/** Call audio routing: earpiece vs loudspeaker, plus proximity screen-off for earpiece calls. */
object AudioRouter {
    private val am get() = App.ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var proximity: PowerManager.WakeLock? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private var active = false
    var speaker = false; private set
    var onChange: (() -> Unit)? = null

    @Suppress("DEPRECATION")
    fun start(useSpeaker: Boolean) {
        if (active) return
        active = true
        savedMode = am.mode
        am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (bluetoothHeadset()) { // a connected headset wins over speaker and earpiece
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            sco = true
            setSpeaker(false)
        } else setSpeaker(useSpeaker && !am.isWiredHeadsetOn)
    }

    private var sco = false
    private var scoPaused = false

    private fun bluetoothHeadset(): Boolean = try {
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            ?.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
    } catch (_: Exception) { false }

    /** Switch to loudspeaker for video, unless the user listens through a headset. */
    @Suppress("DEPRECATION")
    fun autoSpeaker() {
        if (!speaker && !sco && !am.isWiredHeadsetOn) setSpeaker(true)
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (!active) return
        active = false
        releaseProximity()
        if (sco) { am.isBluetoothScoOn = false; am.stopBluetoothSco(); sco = false }
        scoPaused = false
        am.isSpeakerphoneOn = false
        am.isMicrophoneMute = false
        am.mode = savedMode
        am.abandonAudioFocus(null)
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) {
        // Bluetooth headset: loudspeaker means leaving SCO; back to earpiece returns to the headset
        if (on && sco) { am.isBluetoothScoOn = false; am.stopBluetoothSco(); sco = false; scoPaused = true }
        else if (!on && scoPaused) { am.startBluetoothSco(); am.isBluetoothScoOn = true; sco = true; scoPaused = false }
        speaker = on
        am.isSpeakerphoneOn = on
        updateProximity()
        onChange?.invoke()
    }

    // Turn the screen off near the ear only for earpiece calls (like WhatsApp voice calls).
    var videoActive = false
        set(v) { field = v; updateProximity() }

    private fun updateProximity() {
        if (!active || speaker || videoActive) { releaseProximity(); return }
        if (proximity == null) {
            val pm = App.ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "zeng:proximity").apply { acquire() }
            }
        }
    }

    private fun releaseProximity() {
        proximity?.let { if (it.isHeld) it.release() }
        proximity = null
    }
}
