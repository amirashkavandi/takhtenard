package ir.takhtenard.game

/* Wi-Fi Direct, the main road. The old version of this file assumed the host could always declare
   itself the group owner with createGroup() and then sit and listen. On real phones that call dies
   within a few milliseconds — the player sees "Wi-Fi Direct did not work, trying Bluetooth" before
   anything else has had a chance to happen — so the group is now formed the way every other P2P app
   forms it: both radios look around, one phone picks the other, the framework makes an owner out of
   the pair, and only then does it matter who listens. Whoever the framework made owner listens on the
   fixed port and the other phone connects to the address it reports. Which of the two is the game's
   host has nothing to do with that: the pipe carries both directions and the board authority lives in
   the messages, so turning the socket round costs nothing.

   No IP is typed, no router is involved and no internet is used — the traffic never leaves the two
   radios. Everything that can fail quietly is written to TnLog, because the person who has to
   diagnose a failure is a player with two phones and no adb. */

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WpsInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class WifiDirectPeer(private val ctx: Context) : TnConnector {

    private val manager = ctx.applicationContext
        .getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val main = Handler(Looper.getMainLooper())

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var server: ServerSocket? = null
    private var devices: Map<String, WifiP2pDevice> = emptyMap()

    private var role = ""
    private var busy = false
    private var stopped = false
    private var failedOnce = false
    private var listening = false
    private var deadline = 0L
    private var onPipe: ((TnPipe) -> Unit)? = null
    private var onPeers: ((List<TnPeer>) -> Unit)? = null
    private var onFail: ((String) -> Unit)? = null

    override fun start(role: String,
                       onPipe: (TnPipe) -> Unit,
                       onPeers: (List<TnPeer>) -> Unit,
                       onFail: (String) -> Unit) {
        this.role = role
        this.onPipe = onPipe
        this.onPeers = onPeers
        this.onFail = onFail
        stopped = false
        failedOnce = false
        listening = false
        if (manager == null) {
            fail("This phone has no Wi-Fi Direct")
            return
        }
        TnLog.i("wifi: start as $role")
        channel = manager.initialize(ctx.applicationContext, Looper.getMainLooper()) {
            fail("Wi-Fi is turned off")
        }
        register()
        /* Both radios look around, even the one that hosts. A phone that is not scanning does not
           answer the other one's probes, so a host that only ever calls createGroup is invisible to
           the guest the moment createGroup fails — and the guest then reports "found no phone". */
        deadline = System.currentTimeMillis() + LOOK_MS
        discover()
        main.postDelayed(keepLooking, 10000)
        if (role == "host") hostTable()
    }

    /* The quick road: ask to be the owner outright. Where the phone allows it the guest only has to
       find a flagged group owner and sit down, with no picking involved. */
    private fun hostTable() {
        createGroup(true)
    }

    /* A group left over from an earlier attempt — or from another app — makes createGroup fail with an
       internal error, and then the radio stays stuck until Wi-Fi is toggled. Removing it and trying
       once more is the standard way out, so the player does not have to know that. */
    private fun createGroup(canRetry: Boolean) {
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                TnLog.i("wifi: we are the owner, listening on $P2P_PORT")
                listenHere()
            }

            override fun onFailure(reason: Int) {
                TnLog.i("wifi: createGroup failed (code $reason)" + if (canRetry) ", clearing and retrying" else "")
                if (!canRetry) {
                    /* Not the end of the attempt. This phone is still looking, so the other one can
                       find it and ask it to form the group, and either phone can now pick the other.
                       Only when that window closes with nothing in it does the attempt die. */
                    deadline = System.currentTimeMillis() + PICK_MS
                    TnLog.i("wifi: waiting $PICK_MS/1000 s for the other phone to pick this one")
                    return
                }
                manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { createGroup(false) }
                    override fun onFailure(reason2: Int) {
                        TnLog.i("wifi: removeGroup failed too (code $reason2)")
                        createGroup(false)
                    }
                })
            }
        })
    }

    private fun discover() {
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { TnLog.i("wifi: discovery started") }
            override fun onFailure(reason: Int) {
                /* Code 1 is FAILURE_BUSY: a round started ten seconds ago is still running, because one
                   round lasts about twelve. That is not a dead attempt, and a phone that already owns a
                   group does not need to be findable at all — so the host only writes it down. */
                if (reason == 1 || role == "host") {
                    TnLog.i("wifi: discoverPeers code $reason (" + if (role == "host") "host" else "guest" +
                            ", keeping the attempt)")
                    return
                }
                fail("Could not look for phones (code $reason) — try Bluetooth")
            }
        })
    }

    /* One discovery run lasts about twelve seconds and then stops, so keep starting it again until the
       deadline. Whoever owns the group at that point is the one that has to wait, and the phone that
       found nothing says so instead of leaving the player watching "looking for a phone" forever. */
    private val keepLooking = object : Runnable {
        override fun run() {
            if (stopped || busy) return
            if (System.currentTimeMillis() > deadline) {
                if (devices.isEmpty())
                    fail(if (listening) "The other phone never sat down at this phone's table"
                    else if (role == "host") "This phone could not become the owner of a group and " +
                            "nothing picked it either"
                    else "Found no phone in ${LOOK_MS / 1000}s. Is the other one on \"Host\"? On Android 12 " +
                            "and below the phone's Location switch must also be on.")
                return
            }
            TnLog.i("wifi: still looking, ${devices.size} phone(s) so far")
            discover()
            main.postDelayed(this, 10000)
        }
    }

    private fun requestPeers() {
        manager?.requestPeers(channel) { peers ->
            val list = peers?.deviceList?.toList() ?: emptyList()
            devices = list.associateBy { it.deviceAddress }
            val names = StringBuilder()
            for (d in list) {
                if (names.isNotEmpty()) names.append(", ")
                names.append(d.deviceName ?: "?")
                if (d.isGroupOwner) names.append(" (owner)")
            }
            TnLog.i("wifi: peers " + list.size + if (names.isEmpty()) "" else " — " + names)
            /* A phone that is already a group owner is a table waiting for a second player, so those
               come first; if none is flagged yet the player still sees every phone around. */
            val owners = list.filter { it.isGroupOwner }
            val show = if (owners.isNotEmpty()) owners else list
            if (show.isNotEmpty())
                onPeers?.invoke(show.map { TnPeer(it.deviceAddress, it.deviceName ?: it.deviceAddress) })
        }
    }

    override fun pick(id: String) {
        val dev = devices[id] ?: return
        TnLog.i("wifi: connecting to " + (dev.deviceName ?: id))
        val cfg = WifiP2pConfig()
        cfg.deviceAddress = dev.deviceAddress
        /* Either phone can now do the picking, so say who is holding the board: the host asks to be the
           owner (it then listens), the guest asks for the other one to be it (it then connects). The
           framework still has the last word, which is exactly why the code below decides by group
           ownership and not by the role string. */
        cfg.groupOwnerIntent = if (role == "host") 15 else 4
        cfg.wps.setup = WpsInfo.PBC
        manager?.connect(channel, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { TnLog.i("wifi: connect accepted, waiting for the group") }
            override fun onFailure(reason: Int) {
                fail("Could not sit down at that phone (code $reason)")
            }
        })
    }

    /* This phone owns the group: open the port and wait for the other radio to sit down at it. */
    private fun listenHere() {
        if (listening || busy || stopped) return
        listening = true
        Thread {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(P2P_PORT))
                server = ss
                val s = ss.accept()
                s.tcpNoDelay = true
                busy = true
                TnLog.i("wifi: a phone sat down at our table")
                onPipe?.invoke(TnPipe(s.getInputStream(), s.getOutputStream(), s))
            } catch (e: Exception) {
                if (!stopped) fail("Could not open the table: " + (e.message ?: e.javaClass.simpleName))
                else TnLog.i("wifi: host socket closed on purpose")
            }
        }.apply { isDaemon = true; name = "tn-p2p-listen" }.start()
    }

    /* Somebody else owns the group: knock on the address the framework reports until it answers. */
    private fun knock(addr: InetAddress) {
        Thread {
            /* A phone starts listening only after its own group callback returns, and both phones hear
               about the group at about the same moment, so the first knock can easily land before the
               table is set. One attempt loses the link to that race; keep knocking instead. */
            val until = System.currentTimeMillis() + KNOCK_MS
            var last = ""
            var done = false
            var tries = 0
            while (!done && !stopped && System.currentTimeMillis() < until) {
                tries++
                var s: Socket? = null
                try {
                    s = Socket()
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(addr, P2P_PORT), 4000)
                    done = true
                    TnLog.i("wifi: connected after $tries knock(s)")
                    onPipe?.invoke(TnPipe(s.getInputStream(), s.getOutputStream(), s))
                } catch (e: Exception) {
                    last = e.message ?: e.javaClass.simpleName
                    try { s?.close() } catch (ignored: Exception) { }
                    try { Thread.sleep(500) } catch (ignored: InterruptedException) { done = true }
                }
            }
            if (!done && !stopped) {
                busy = false
                fail("Could not reach the other phone after $tries knocks: $last")
            }
        }.apply { isDaemon = true; name = "tn-p2p-connect" }.start()
    }

    private val connListener = WifiP2pManager.ConnectionInfoListener { info ->
        if (info == null || busy) return@ConnectionInfoListener
        if (info.isGroupOwner) {
            /* Listening is the whole job now, so stop looking — but only once there is a table to
               guard, which is why this is not done at the top of the callback. */
            main.removeCallbacks(keepLooking)
            TnLog.i("wifi: this phone is the owner of the group")
            listenHere()
            return@ConnectionInfoListener
        }
        val addr = info.groupOwnerAddress
        if (addr == null) {
            TnLog.i("wifi: connection info has no group owner address yet")
            return@ConnectionInfoListener
        }
        busy = true
        main.removeCallbacks(keepLooking)
        TnLog.i("wifi: group formed, owner is $addr")
        knock(addr)
    }

    private fun register() {
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val on = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                                WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        TnLog.i("wifi: radio " + if (on) "enabled" else "disabled")
                        if (!on && !busy) fail("Wi-Fi Direct is turned off")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        /* Whether this line ever appears is the answer to "does the receiver get the
                           system broadcasts at all", which is the first thing to check on a phone that
                           finds nobody. */
                        TnLog.i("wifi: peers changed")
                        if (!busy) requestPeers()
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val net = networkInfo(intent)
                        TnLog.i("wifi: connection changed, connected=" + (net?.isConnected == true))
                        if (net != null && net.isConnected) manager?.requestConnectionInfo(channel, connListener)
                        else if (busy) fail("The other phone went away")
                    }
                }
            }
        }
        receiver = r
        val app = ctx.applicationContext
        /* NOT_EXPORTED is enough for these three actions: only the platform can send them. */
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") app.registerReceiver(r, f)
    }

    @Suppress("DEPRECATION")
    private fun networkInfo(intent: Intent): NetworkInfo? =
        if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
        else intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as? NetworkInfo

    /* Once only. Several of these can fire for the same dead attempt, and a second one would send
       MainActivity off to try Bluetooth again after the player has already been told something. */
    private fun fail(msg: String) {
        if (failedOnce) { TnLog.i("wifi: (suppressed) $msg"); return }
        failedOnce = true
        TnLog.i("wifi: FAIL — $msg")
        main.post { onFail?.invoke(msg) }
    }

    override fun stop() {
        stopped = true
        busy = false
        listening = false
        main.removeCallbacks(keepLooking)
        try { server?.close() } catch (e: Exception) { }
        server = null
        try { manager?.stopPeerDiscovery(channel, null) } catch (e: Exception) { }
        /* Removing the group is what actually frees the radio; without it the next host attempt on
           this phone fails with "incompatible parameters" until Wi-Fi is toggled. */
        try { manager?.removeGroup(channel, null) } catch (e: Exception) { }
        try { receiver?.let { ctx.applicationContext.unregisterReceiver(it) } } catch (e: Exception) { }
        receiver = null
        channel = null
        TnLog.i("wifi: stopped")
    }

    private companion object {
        const val LOOK_MS = 40000L
        const val KNOCK_MS = 15000L
        /* A host that could not make itself the owner still waits to be picked; long enough for the
           other phone to finish a discovery round, short enough that Bluetooth arrives soon after. */
        const val PICK_MS = 25000L
    }
}
