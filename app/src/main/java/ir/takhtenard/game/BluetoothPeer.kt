package ir.takhtenard.game

/* Bluetooth RFCOMM, the fallback for phones whose Wi-Fi Direct refuses to work. It fills exactly the
   same interface as Wi-Fi Direct: a pair of streams, a list of phones to pick from, and one sentence
   when it fails. The game cannot tell the two apart, which is the point. */

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

/* Permissions are asked for by MainActivity before start() is ever called, so the radio calls below
   are reached only with them granted. */
@SuppressLint("MissingPermission")
class BluetoothPeer(private val ctx: Context) : TnConnector {

    private val adapter: BluetoothAdapter? =
        (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val main = Handler(Looper.getMainLooper())

    private var server: BluetoothServerSocket? = null
    private var receiver: BroadcastReceiver? = null
    private var found: MutableMap<String, BluetoothDevice> = LinkedHashMap()

    private var role = ""
    private var busy = false
    private var stopped = false
    private var failedOnce = false
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
        val a = adapter
        if (a == null) {
            fail("This phone has no Bluetooth")
            return
        }
        if (!a.isEnabled) {
            fail("Bluetooth is turned off")
            return
        }
        TnLog.i("bt: start as $role")
        if (role == "host") hostTable(a) else lookForHost(a)
    }

    /* The host only has to be findable; accept() then blocks until the guest knocks. */
    private fun hostTable(a: BluetoothAdapter) {
        Thread {
            var ss: BluetoothServerSocket? = null
            try {
                ss = a.listenUsingRfcommWithServiceRecord(BT_NAME, BT_UUID)
                server = ss
                TnLog.i("bt: listening as \"$BT_NAME\"")
                val s = ss.accept()
                busy = true
                TnLog.i("bt: a guest sat down")
                onPipe?.invoke(TnPipe(s.getInputStream(), s.getOutputStream(), s))
            } catch (e: Exception) {
                if (!stopped) fail("Could not open the table: " + (e.message ?: e.javaClass.simpleName))
                else TnLog.i("bt: host socket closed on purpose")
            } finally {
                try { ss?.close() } catch (e: Exception) { }
            }
        }.apply { isDaemon = true; name = "tn-bt-host" }.start()
    }

    /* The guest sees the phones it already knows at once, then whatever discovery turns up. A player
       who has paired the two phones before never waits for a scan. */
    private fun lookForHost(a: BluetoothAdapter) {
        register(a)
        try {
            a.bondedDevices?.forEach { d -> found[d.address] = d }
            TnLog.i("bt: " + found.size + " bonded phone(s)")
        } catch (e: Exception) {
            TnLog.i("bt: bonded list failed — " + e.message)
        }
        publish()
        try {
            if (a.startDiscovery()) { TnLog.i("bt: discovery started"); return }
            TnLog.i("bt: startDiscovery returned false")
        } catch (e: Exception) {
            TnLog.i("bt: discovery failed — " + e.message)
        }
        if (found.isEmpty()) fail("Found no phone — is the other one on Host?")
    }

    private fun publish() {
        val list = found.values.map { d -> TnPeer(d.address, d.name ?: d.address) }
        if (list.isNotEmpty()) onPeers?.invoke(list)
    }

    override fun pick(id: String) {
        val a = adapter ?: return
        val dev = found[id] ?: return
        /* Connecting while a scan is running is the classic way an RFCOMM connect silently times out. */
        try { a.cancelDiscovery() } catch (e: Exception) { }
        busy = true
        TnLog.i("bt: connecting to " + (dev.name ?: id))
        Thread {
            /* The host only starts listening once its own "Host" tap gets going, and RFCOMM has no
               polite "not yet", so the first knock can land too early. Two knocks, not one. */
            var last = ""
            var done = false
            var tries = 0
            while (!done && !stopped && tries < 2) {
                tries++
                var s: BluetoothSocket? = null
                try {
                    s = dev.createRfcommSocketToServiceRecord(BT_UUID)
                    s.connect()
                    done = true
                    TnLog.i("bt: connected after $tries knock(s)")
                    onPipe?.invoke(TnPipe(s.getInputStream(), s.getOutputStream(), s))
                } catch (e: Exception) {
                    last = e.message ?: e.javaClass.simpleName
                    try { s?.close() } catch (ignored: Exception) { }
                    TnLog.i("bt: knock $tries failed ($last)")
                    try { a.cancelDiscovery() } catch (ignored: Exception) { }
                    if (!done && !stopped) try { Thread.sleep(1200) } catch (ignored: InterruptedException) { }
                }
            }
            if (!done && !stopped) {
                busy = false
                fail("Could not reach that phone: $last")
            }
        }.apply { isDaemon = true; name = "tn-bt-guest" }.start()
    }

    private fun register(a: BluetoothAdapter) {
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = if (Build.VERSION.SDK_INT >= 33)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION")
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (d != null && !found.containsKey(d.address)) {
                            found[d.address] = d
                            TnLog.i("bt: found " + (d.name ?: d.address))
                            publish()
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        TnLog.i("bt: discovery finished, " + found.size + " phone(s)")
                        if (!busy && found.isEmpty()) fail("Found no phone — is the other one on Host?")
                    }
                }
            }
        }
        receiver = r
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") app.registerReceiver(r, f)
    }

    /* Once only: a dead attempt can produce several of these, and a second one would send MainActivity
       off to try the other radio again after the player has already been told something. */
    private fun fail(msg: String) {
        if (failedOnce) { TnLog.i("bt: (suppressed) $msg"); return }
        failedOnce = true
        TnLog.i("bt: FAIL — $msg")
        main.post { onFail?.invoke(msg) }
    }

    override fun stop() {
        stopped = true
        busy = false
        try { adapter?.cancelDiscovery() } catch (e: Exception) { }
        try { server?.close() } catch (e: Exception) { }
        server = null
        try { receiver?.let { ctx.applicationContext.unregisterReceiver(it) } } catch (e: Exception) { }
        receiver = null
        found.clear()
        TnLog.i("bt: stopped")
    }
}
