package ir.takhtenard.game

/* The whole Android app: one WebView, one JavaScript bridge, two radios. Everything a player sees —
   the board, the dice, the clock, the menus, the two-phone screen — is the single HTML file in
   assets, untouched. This file only loads it, hands it window.TNBridge, and answers the back button.

   Note what is NOT here: the game page never reaches the network by itself. It is loaded from
   file:///android_asset, which has no origin to fetch from, so the relay path inside the HTML cannot
   run at all — the game reaches for window.TNBridge first, which is why it works. INTERNET is declared
   for one thing only: the LAN socket in LanPeer, which will not accept an address outside the private
   ranges and never looks a name up, so that permission moves bytes between two phones on one Wi-Fi and
   nowhere else. */

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var web: WebView
    /* Written on the UI thread, read on the WebView's bridge thread. */
    @Volatile
    private var link: PipeLink? = null
    private var connector: TnConnector? = null
    /* Messages the game sent before the pipe existed — a guest says hello the moment it is told to
       join, which is long before a socket is up. They go out in order the moment it is.

       The queue's lifetime is one ATTEMPT, and one attempt can span both radios: open() empties it,
       then the game's hello lands in it, then Wi-Fi Direct may fail and Bluetooth may take over. So
       teardown() must not empty it — clearing it on the fallback path is what used to throw the hello
       away, after which the socket connected, "Connected" was shown, and nothing ever happened
       because the host was still waiting for a hello that had been deleted.
       `waiting` is also the lock that guards the swap between the queue and the live pipe, so a send
       can neither be lost nor overtake the ones already waiting. */
    private val waiting = ArrayDeque<String>()
    private var sends = 0
    private var dialog: AlertDialog? = null
    private var pending: Pair<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /* A hand takes minutes and the host owns the clock, so the screen stays lit while we are up. */
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(0xFF0B231C.toInt())
        setContentView(web)
        setupWeb()
        web.loadUrl("file:///android_asset/" + gameAsset())
    }

    /* The live file carries its version in its name, and the menu reads the version off that name, so
       the wrapper takes the newest copy in assets. Promoting a version is a file copy, never an edit
       of this code. */
    private fun gameAsset(): String {
        val re = Regex("^takhte-nard-v(\\d+)\\.html$")
        val best = (assets.list("") ?: emptyArray())
            .mapNotNull { n -> re.matchEntire(n)?.let { it.groupValues[1].toInt() to n } }
            .maxByOrNull { it.first }
        return best?.second ?: DEFAULT_ASSET
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            /* sessionStorage keeps the seat id across a reload, localStorage keeps the unfinished
               hand and the settings; without this both are gone on a file:// page. */
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            /* The game starts its music on the first touch by design; this only stops the WebView
               from swallowing a play() that the page itself decided on. */
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                /* The page's own console goes into the same trace, so a JavaScript error is readable
                   on the phone too and not only in logcat. */
                TnLog.i("web " + m.messageLevel() + ": " + m.message() + " @" + m.lineNumber())
                return true
            }
        }
        /* Injected before loadUrl, so window.TNBridge already exists when the page script runs. */
        web.addJavascriptInterface(Bridge(), "TNBridge")
    }

    /* ===== The three functions the game is allowed to call ===== */

    private inner class Bridge {
        @JavascriptInterface
        fun open(role: String, which: String) {
            /* One open() is one attempt, even though it may try two radios; that is the unit the trace
               on screen reports, so it starts here and not in startLink.
               Emptying the queue here is safe and is the only place it is emptied mid-flight: the game
               calls open() and only then sends its hello, and WebView dispatches every bridge call for
               a page on one thread, so the hello is always enqueued after this returns. */
            TnLog.clear()
            TnLog.i("bridge: open($role, $which)")
            /* 'wifi', 'bt', 'lan' or 'lan:<address>'. The LAN one keeps its argument because that is
               the number the player read off the other phone; anything unrecognised stays on Wi-Fi
               Direct, which is the road that needs nothing typed. */
            val w = which.trim().toLowerCase()
            val sel = when {
                w == "bt" || w == "bluetooth" -> "bt"
                w == "lan" || w.startsWith("lan:") -> w
                else -> "wifi"
            }
            synchronized(waiting) { waiting.clear(); sends = 0 }
            runOnUiThread { startLink(role, sel) }
        }

        @JavascriptInterface
        fun send(text: String) {
            /* Runs on the WebView's bridge thread, never on the UI thread. The lock is the same one
               attach() holds while it swaps the queue for the live pipe, so a message can neither be
               dropped nor overtake the ones already waiting. */
            synchronized(waiting) {
                val l = link
                if (sends++ < 4)
                    TnLog.i("bridge: send " + (if (l != null) "-> pipe: " else "-> queue: ") + text.take(60))
                if (l != null) l.send(text) else waiting.addLast(text)
            }
        }

        @JavascriptInterface
        fun close() {
            TnLog.i("bridge: close()")
            synchronized(waiting) { waiting.clear() }
            runOnUiThread { teardown() }
        }
    }

    /* ===== Bringing a channel up ===== */

    private fun startLink(role: String, which: String) {
        teardown()
        val need = permissionsFor(which).filter { !granted(it) }
        if (need.isNotEmpty()) {
            TnLog.i("$which: asking for " + need.joinToString { it.substringAfterLast('.') })
            pending = role to which
            requestPermissions(need.toTypedArray(), REQ_PERM)
            return
        }
        begin(role, which)
    }

    private fun begin(role: String, which: String) {
        TnLog.i("$which/$role: begin")
        if (which == "bt") {
            val a = (applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (a == null || !a.isEnabled) {
                TnLog.i("bt: adapter missing or turned off")
                say(R.string.bt_off)
                jsFail()
                return
            }
            /* A guest that has never been paired can only find a host that is discoverable. */
            if (role == "host") runCatching {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300), REQ_DISCOVERABLE)
            }
        } else if (which.startsWith("lan")) {
            /* A WebView page has no way to read the phone's own address, and the second phone has to be
               told one, so this is the only place it is looked up. The host puts it on the game's
               screen; a guest types what it reads there and needs nothing of its own. */
            val addrs = lanAddresses()
            TnLog.i("lan: private address(es) " + if (addrs.isEmpty()) "none" else addrs.joinToString(", "))
            if (role == "host") {
                if (addrs.isEmpty()) say(R.string.lan_none)
                js("window.TNNet&&window.TNNet.onInfo&&window.TNNet.onInfo(" +
                        JSONObject.quote(addrs.joinToString("   ")) + ")")
            }
        } else {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && !wm.isWifiEnabled) {
                TnLog.i("wifi: radio turned off")
                say(R.string.wifi_off)
                jsFail()
                return
            }
        }
        openChannel(role, which)
    }

    private fun openChannel(role: String, which: String) {
        TnLog.i("openChannel $which/$role")
        say(when {
            which.startsWith("lan") -> if (role == "host") R.string.lan_wait else R.string.lan_knock
            role == "host" -> R.string.waiting_guest
            else -> R.string.looking
        })
        val c: TnConnector = when {
            which == "bt" -> BluetoothPeer(this)
            which.startsWith("lan") -> LanPeer(which.substringAfter(':', ""))
            else -> WifiDirectPeer(this)
        }
        connector = c
        c.start(role,
            onPipe = { p -> runOnUiThread { attach(p) } },
            onPeers = { list -> runOnUiThread { showPeers(list) } },
            onFail = { msg -> runOnUiThread { failed(msg, role, which) } })
    }

    /* Wi-Fi Direct is the road that needs no pairing, so it is tried first; if this phone's radio
       refuses, Bluetooth gets one try before the game is told the link is dead. */
    private fun failed(msg: String, role: String, which: String) {
        TnLog.i("link failed ($which/$role): $msg")
        if (which == "wifi") {
            say(R.string.trying_bt)
            teardown()
            startLink(role, "bt")
            return
        }
        teardown()
        jsFail()
        showTrace(msg)
    }

    private fun attach(p: TnPipe) {
        val l = PipeLink(p,
            { line -> js("window.TNNet&&window.TNNet.onMessage(" + JSONObject.quote(line) + ")") },
            { runOnUiThread { pipeLost() } })
        l.start()
        dialog?.dismiss()
        dialog = null
        /* The pipe is published and the queue drained under the same lock send() takes, so a message
           can neither land in the queue after the drain nor overtake the ones already in it. */
        val queued = synchronized(waiting) { link = l; val q = waiting.toList(); waiting.clear(); q }
        queued.forEach { l.send(it) }
        TnLog.i("pipe up, " + queued.size + " queued message(s) flushed")
        say(R.string.connected)
    }

    /* The whole diagnosis on one screen, and the reason it is a dialog rather than a toast: nobody
       who plays this game has adb, so the only report that can ever come back from a phone is a
       screenshot. Selectable text means it can also be copied out. */
    private fun showTrace(head: String) {
        val body = TextView(this)
        body.text = head + "\n\n" + TnLog.dump()
        body.setTextIsSelectable(true)
        body.textSize = 12f
        body.setPadding(40, 28, 40, 28)
        val scroll = ScrollView(this)
        scroll.addView(body)
        AlertDialog.Builder(this)
            .setTitle(R.string.why_failed)
            .setView(scroll)
            .setPositiveButton(R.string.ok_label) { d, _ -> d.dismiss() }
            .show()
    }

    private fun pipeLost() {
        TnLog.i("pipe died")
        teardown()
        jsClose()
    }

    private fun showPeers(list: List<TnPeer>) {
        val c = connector ?: return
        if (list.isEmpty() || dialog?.isShowing == true) return
        TnLog.i("peer list: " + list.joinToString { it.name })
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pick_phone)
            .setItems(list.map { it.name }.toTypedArray()) { _, i -> c.pick(list[i].id) }
            .setNegativeButton(R.string.cancel) { _, _ ->
                TnLog.i("player cancelled the peer list")
                teardown(); jsFail()
            }
            .setOnDismissListener { dialog = null }
            .show()
    }

    /* Detach, never notify: the game learns a link died only from the pipe itself, or from an
       explicit onClose when the attempt ended before a pipe existed. Telling it twice used to look
       like the other phone leaving in the middle of a hand.
       The queue survives on purpose — see the note on `waiting`. */
    private fun teardown() {
        dialog?.dismiss()
        dialog = null
        val l = link
        link = null
        val c = connector
        connector = null
        runCatching { l?.detach() }
        runCatching { c?.stop() }
    }

    /* The private IPv4 addresses this phone holds — wlan0 for a shared router, the ap interface for a
       hotspot the other phone joined, eth0 for a cable. Loopback and anything outside the private
       ranges is left out: showing a public address here would invite a player to point the game at a
       machine on the internet, which is the one thing this road must never be. */
    private fun lanAddresses(): List<String> {
        val out = ArrayList<String>()
        runCatching {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (intf == null || !intf.isUp || intf.isLoopback) continue
                for (a in intf.inetAddresses)
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress)
                        a.hostAddress?.let { out.add(it) }
            }
        }.onFailure { TnLog.i("lan: address lookup failed: " + (it.message ?: it.javaClass.simpleName)) }
        return out.distinct()
    }

    /* ===== Permissions ===== */

    private fun permissionsFor(which: String): List<String> {
        val l = ArrayList<String>()
        val sdk = Build.VERSION.SDK_INT
        if (which.startsWith("lan")) return l
        if (which == "wifi") {
            /* On 13+ nearby devices covers Wi-Fi Direct and, with neverForLocation, needs no
               location. Below that, discovery is a location API and needs the location permission. */
            if (sdk >= 33) l.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            else l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            if (sdk >= 31) {
                l.add(Manifest.permission.BLUETOOTH_CONNECT)
                l.add(Manifest.permission.BLUETOOTH_SCAN)
            } else l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return l
    }

    private fun granted(p: String): Boolean = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        val p = pending ?: return
        if (requestCode != REQ_PERM) return
        pending = null
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            TnLog.i("permissions granted")
            begin(p.first, p.second)
        } else {
            TnLog.i("permissions REFUSED: " + permissions.joinToString { it.substringAfterLast('.') } +
                    " -> " + results.joinToString())
            say(R.string.need_perm)
            jsFail()
            showTrace(getString(R.string.need_perm))
        }
    }

    /* ===== Chrome ===== */

    /* The game decides what the back button does on each screen and says so by returning true; only
       "false" (or a page that never loaded) leaves the app. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript("(function(){try{return window.appBack?window.appBack():false}catch(e){return false}})()") { v ->
            if (v == "true") return@evaluateJavascript
            teardown()
            finish()
        }
    }

    override fun onDestroy() {
        teardown()
        runCatching { web.destroy() }
        super.onDestroy()
    }

    /* A swallowed exception here would look exactly like "the other phone never answered", so it goes
       into the trace instead. */
    private fun js(expr: String) {
        runOnUiThread {
            try { web.evaluateJavascript(expr, null) }
            catch (e: Exception) { TnLog.i("evaluateJavascript failed: " + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    private fun jsClose() {
        js("window.TNNet&&window.TNNet.onClose()")
    }

    /* The channel never opened on this phone. The game shows "the link could not be opened" instead
       of "the host left", because blaming the other phone here sends the player to the wrong one. */
    private fun jsFail() {
        js("window.TNNet&&window.TNNet.onFail&&window.TNNet.onFail()")
    }

    private fun say(res: Int) {
        runOnUiThread { Toast.makeText(this, res, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val REQ_PERM = 71
        const val REQ_DISCOVERABLE = 72
        /* Only used if assets holds no versioned game file at all, which the build should not allow. */
        const val DEFAULT_ASSET = "takhte-nard-v42.html"
    }
}
