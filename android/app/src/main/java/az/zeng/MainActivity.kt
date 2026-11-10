package az.zeng

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : BaseActivity(), Signaling.Listener {
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var banner: TextView
    private lateinit var indicator: View
    private var tab = 0 // 0 contacts, 1 calls
    private var pendingCall: Pair<Contact, Boolean>? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Prefs.token == null) {
            startActivity(Intent(this, JoinActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        banner = findViewById(R.id.banner)
        indicator = findViewById(R.id.indicator)
        list.emptyView = empty
        list.adapter = adapter

        findViewById<View>(R.id.tabContacts).setOnClickListener { selectTab(0) }
        findViewById<View>(R.id.tabCalls).setOnClickListener { selectTab(1) }
        findViewById<View>(R.id.lang).setOnClickListener {
            Prefs.lang = if (Prefs.lang == "az") "en" else "az"
            App.createChannels()
            ZengService.refresh()
            recreate()
        }
        indicator.post { selectTab(intent.getIntExtra("tab", tab)) }

        findViewById<View>(R.id.updateBar).setOnClickListener {
            // failed in-app install (old ROM, bad network): the tap opens the download in the browser
            val url = Updater.fallbackUrl
            if (Updater.state == Updater.State.FAILED && url != null) openDownload(url) else Updater.install(this)
        }
        App.startService(this)
        askPermissions()
        setupOemHint()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        selectTab(i.getIntExtra("tab", tab))
    }

    override fun onResume() {
        super.onResume()
        Signaling.add(this)
        Signaling.ensureFresh()
        Updater.onChange = { renderUpdate() }
        Updater.check()
        onState()
        renderUpdate()
    }

    // Installer failed (some old ROMs): let the browser download the APK instead.
    private fun openDownload(url: String) {
        Updater.fallbackUrl = null
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    private fun renderUpdate() {
        val bar = findViewById<TextView>(R.id.updateBar)
        bar.visibility = if (Updater.state == Updater.State.NONE) View.GONE else View.VISIBLE
        bar.text = when (Updater.state) {
            Updater.State.AVAILABLE -> getString(R.string.update_available, Updater.versionName) + " · " + getString(R.string.update_btn)
            Updater.State.DOWNLOADING -> getString(R.string.update_downloading, Updater.progress)
            Updater.State.INSTALLING -> getString(R.string.update_installing)
            Updater.State.FAILED -> getString(R.string.update_failed)
            Updater.State.NONE -> ""
        }
    }

    override fun onPause() {
        Signaling.remove(this)
        Updater.onChange = null
        super.onPause()
    }

    override fun onState() {
        findViewById<TextView>(R.id.me).text = Signaling.myName
        // "Connecting…" only after 3 s offline: short reconnects stay invisible
        banner.removeCallbacks(showConnecting)
        when {
            Signaling.banned -> { banner.setText(R.string.banned); banner.visibility = View.VISIBLE }
            !Signaling.connected -> banner.postDelayed(showConnecting, 3000)
            else -> banner.visibility = View.GONE
        }
        checkCallAlerts()
        if (Prefs.token == null) { // server forgot us (deleted by admin): register again
            startActivity(Intent(this, JoinActivity::class.java))
            finish()
            return
        }
        adapter.notifyDataSetChanged()
    }

    private val showConnecting = Runnable {
        if (!Signaling.connected && !Signaling.banned) {
            banner.setText(R.string.connecting_server)
            banner.visibility = View.VISIBLE
        }
    }

    private fun selectTab(t: Int) {
        tab = t
        val w = (indicator.parent as View).width / 2
        indicator.layoutParams = (indicator.layoutParams as LinearLayout.LayoutParams).apply { width = w; leftMargin = w * t }
        indicator.requestLayout()
        findViewById<TextView>(R.id.tabContacts).alpha = if (t == 0) 1f else 0.7f
        findViewById<TextView>(R.id.tabCalls).alpha = if (t == 1) 1f else 0.7f
        empty.setText(if (t == 0) R.string.empty_contacts else R.string.empty_calls)
        adapter.notifyDataSetChanged()
    }

    // ---------- can this phone actually show an incoming call? ----------

    /**
     * Incoming calls are a full-screen notification: if notifications, the call channel or (Android 14+)
     * full-screen intents are off, a call rings nowhere. Say so loudly and link to the right switch.
     */
    private fun checkCallAlerts() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val target: Intent? = when {
            Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled() ->
                if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(App.CH_INCOMING)?.importance == android.app.NotificationManager.IMPORTANCE_NONE ->
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, App.CH_INCOMING)
            Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() ->
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
            else -> null
        }
        val bar = findViewById<TextView>(R.id.alertBar)
        bar.visibility = if (target != null) View.VISIBLE else View.GONE
        bar.setOnClickListener {
            try { startActivity(target!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    // ---------- phone makers that block calls on the lock screen ----------

    /**
     * Honor/Huawei/Xiaomi/Oppo/Vivo ship extra switches ("show on lock screen", "auto-launch",
     * "pop-up windows") that are off for apps outside their store, so incoming calls don't open.
     * Show a one-time hint that takes the user to the right settings page.
     */
    private fun setupOemHint() {
        val maker = Build.MANUFACTURER.lowercase()
        val strict = listOf("honor", "huawei", "xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "oneplus").any { maker.contains(it) }
        val bar = findViewById<View>(R.id.oemBar)
        bar.visibility = if (strict && !Prefs.oemHintDone) View.VISIBLE else View.GONE
        bar.setOnClickListener {
            Prefs.oemHintDone = true
            bar.visibility = View.GONE
            openOemSettings()
        }
    }

    private fun openOemSettings() {
        val pkg = packageName
        val candidates = listOf(
            // Honor (MagicOS) / Huawei (EMUI): app launch management
            Intent().setClassName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            // Xiaomi (MIUI/HyperOS): other permissions incl. "show on lock screen"
            Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", pkg),
            // everyone else, and the fallback: the app's own settings page
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (i in candidates) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Toast.makeText(this, R.string.oem_hint_steps, Toast.LENGTH_LONG).show(); return } catch (_: Exception) {}
        }
    }

    // ---------- calling ----------

    /** From the call log: one person, or everyone from a group call (WhatsApp rings them all). */
    private fun callBack(e: CallEntry) {
        val people = e.members.mapNotNull { id -> Signaling.contacts.find { it.id == id } }
        if (people.isEmpty()) return
        if (people.size == 1) { call(people[0], e.video); return }
        pendingGroup = people
        call(people[0], e.video)
    }

    private var pendingGroup: List<Contact>? = null

    private fun call(c: Contact, video: Boolean) {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) need += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 23 && need.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            pendingCall = c to video
            requestPermissions(need.toTypedArray(), REQ_CALL)
            return
        }
        startNow(c, video)
    }

    private fun startNow(c: Contact, video: Boolean) {
        val g = pendingGroup
        pendingGroup = null
        if (g != null && g.firstOrNull()?.id == c.id) CallManager.startGroup(g, video) else CallManager.startCall(c, video)
    }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val need = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_START)
        else askBattery()
    }

    // Without this, some phones kill the background connection and calls stop ringing.
    private fun askBattery() {
        if (Build.VERSION.SDK_INT < 23 || Prefs.askedBattery) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        Prefs.askedBattery = true
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        if (req == REQ_START) askBattery()
        if (req == REQ_CALL) {
            val p = pendingCall ?: return
            pendingCall = null
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
                return
            }
            val camOk = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            startNow(p.first, p.second && camOk)
        }
    }

    // ---------- list ----------

    private fun contacts() = Signaling.contacts.filter { it.id != Signaling.myId }
        .sortedWith(compareByDescending<Contact> { it.online }.thenBy { it.name.lowercase() })

    private val adapter = object : BaseAdapter() {
        override fun getCount() = if (tab == 0) contacts().size else Signaling.history.size
        override fun getItem(p: Int): Any = if (tab == 0) contacts()[p] else Signaling.history[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = tab

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View =
            if (tab == 0) contactRow(contacts()[p], convert, parent) else callRow(Signaling.history[p], convert, parent)
    }

    private fun contactRow(c: Contact, convert: View?, parent: ViewGroup): View {
        val v = convert ?: layoutInflater.inflate(R.layout.item_contact, parent, false)
        Ui.avatar(v.findViewById(R.id.avatar), c.id, c.name)
        v.findViewById<View>(R.id.online).visibility = if (c.online) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.name).text = c.name
        val talk = Signaling.totals[c.id] ?: 0
        val status = when {
            c.online -> getString(R.string.online)
            c.lastSeen > 0 -> getString(R.string.last_seen, Ui.whenText(c.lastSeen))
            else -> getString(R.string.offline)
        }
        v.findViewById<TextView>(R.id.sub).text =
            if (talk > 0) "$status · ${getString(R.string.talk_total, Ui.duration(talk))}" else status
        v.findViewById<ImageButton>(R.id.voice).setOnClickListener { call(c, false) }
        v.findViewById<ImageButton>(R.id.video).setOnClickListener { call(c, true) }
        v.setOnClickListener { call(c, false) }
        return v
    }

    private fun callRow(e: CallEntry, convert: View?, parent: ViewGroup): View {
        val v = convert ?: layoutInflater.inflate(R.layout.item_call, parent, false)
        val av = v.findViewById<TextView>(R.id.avatar)
        Ui.avatar(av, e.peer, e.name)
        if (e.group) Ui.groupAvatar(av)
        val missed = e.incoming && e.status != "answered"
        v.findViewById<TextView>(R.id.name).apply {
            text = e.name
            setTextColor(if (missed) color(R.color.red) else color(R.color.text))
        }
        v.findViewById<ImageView>(R.id.dir).apply {
            setImageResource(when {
                missed -> R.drawable.ic_call_missed
                e.incoming -> R.drawable.ic_call_received
                else -> R.drawable.ic_call_made
            })
            setColorFilter(when {
                missed || e.status == "declined" -> color(R.color.red)
                e.status == "answered" -> color(R.color.green_light)
                else -> Color.GRAY
            })
        }
        val parts = mutableListOf(Ui.whenText(e.started))
        if (e.duration > 0) parts += Ui.duration(e.duration) else Ui.statusText(e.status)?.let { parts += it }
        v.findViewById<TextView>(R.id.sub).text = parts.joinToString(" · ")
        val again = { callBack(e) }
        v.findViewById<ImageButton>(R.id.again).apply {
            setImageResource(if (e.video) R.drawable.ic_videocam else R.drawable.ic_call)
            setOnClickListener { again() }
        }
        v.setOnClickListener { again() }
        return v
    }

    @Suppress("DEPRECATION")
    private fun color(id: Int) = resources.getColor(id)

    companion object {
        const val REQ_START = 1
        const val REQ_CALL = 2
    }
}
