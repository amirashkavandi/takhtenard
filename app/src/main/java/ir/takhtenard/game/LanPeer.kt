package ir.takhtenard.game

/* The third road: one plain TCP socket between two phones that sit on the same Wi-Fi. Wi-Fi Direct is
   still the better answer when it works — nobody has to read out or type an address — but on phones
   whose Wi-Fi Direct is locked by the manufacturer or by an app that already owns the radio, this one
   opens in a second and needs no pairing.

   What it is NOT: a way onto the internet. The address is typed by a player and has to be a private
   one (10.x, 172.16-31.x, 192.168.x), and it is parsed as four numbers, never looked up, so a name
   cannot send this socket out to the world. The traffic stays inside the same building. */

import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class LanPeer(private val target: String) : TnConnector {

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var role = ""
    private var stopped = false
    private var failedOnce = false
    private var onPipe: ((TnPipe) -> Unit)? = null
    private var onFail: ((String) -> Unit)? = null

    /* Only four dotted numbers are accepted, and only from the private ranges a router hands out.
       No getByName(), so no DNS, so a typed word can never resolve to a machine somewhere else. */
    private fun privateAddress(text: String): InetAddress? {
        val p = text.trim().split(".")
        if (p.size != 4) return null
        val n = IntArray(4)
        for (i in 0..3) {
            if (!p[i].matches(Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"))) return null
            n[i] = p[i].toInt()
        }
        val isPrivate = n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
        if (!isPrivate) return null
        return try { InetAddress.getByAddress(byteArrayOf(n[0].toByte(), n[1].toByte(), n[2].toByte(), n[3].toByte())) }
        catch (e: Exception) { null }
    }

    override fun start(role: String,
                       onPipe: (TnPipe) -> Unit,
                       onPeers: (List<TnPeer>) -> Unit,
                       onFail: (String) -> Unit) {
        /* There is nothing to list here: a LAN table is found by reading an address off the other
           phone, not by looking around, so onPeers stays unused by design. */
        this.role = role
        this.onPipe = onPipe
        this.onFail = onFail
        stopped = false
        failedOnce = false
        if (role == "host") listen() else knock()
    }

    private fun listen() {
        Thread {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(P2P_PORT))
                server = ss
                TnLog.i("lan: waiting on port $P2P_PORT for the second phone")
                val s = ss.accept()
                s.tcpNoDelay = true
                TnLog.i("lan: the second phone arrived from " + s.inetAddress.hostAddress)
                onPipe?.invoke(TnPipe(s.getInputStream(), s.getOutputStream(), s))
            } catch (e: Exception) {
                if (!stopped) fail("Could not open the LAN table: " + (e.message ?: e.javaClass.simpleName))
                else TnLog.i("lan: listener closed on purpose")
            }
        }.apply { isDaemon = true; name = "tn-lan-listen" }.start()
    }

    private fun knock() {
        val addr = privateAddress(target)
        if (addr == null) {
            TnLog.i("lan: refused \"$target\" — not a private 4-number address")
            fail("That address is not on this Wi-Fi. It has to look like 192.168.x.x, the number the " +
                    "hosting phone shows on its screen.")
            return
        }
        Thread {
            /* The host opens its listener a moment after its own screen changes, and the player types
               by hand, so the first knocks usually land on a closed port. Keep knocking until the
               deadline, the same way Wi-Fi Direct does. */
            val until = System.currentTimeMillis() + KNOCK_MS
            var last = ""
            var tries = 0
            var done = false
            while (!done && !stopped && System.currentTimeMillis() < until) {
                tries++
                var s: Socket? = null
                try {
                    s = Socket()
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(addr, P2P_PORT), 4000)
                    done = true
                    TnLog.i("lan: connected after $tries try(ies)")
                    onPipe?.invoke(TnPipe(s.getInputStream(), s.getOutputStream(), s))
                } catch (e: Exception) {
                    last = e.message ?: e.javaClass.simpleName
                    try { s?.close() } catch (ignored: Exception) { }
                    try { Thread.sleep(500) } catch (ignored: InterruptedException) { done = true }
                }
            }
            if (!done && !stopped)
                fail("Could not reach $target after $tries tries: $last. Is the other phone on \"Host\" " +
                        "with the same word typed, and is the Wi-Fi on?")
        }.apply { isDaemon = true; name = "tn-lan-connect" }.start()
    }

    private fun fail(msg: String) {
        if (failedOnce) { TnLog.i("lan: (suppressed) $msg"); return }
        failedOnce = true
        TnLog.i("lan: FAIL — $msg")
        main.post { onFail?.invoke(msg) }
    }

    override fun pick(id: String) { TnLog.i("lan: pick($id) is not used — the address is typed") }

    override fun stop() {
        stopped = true
        try { server?.close() } catch (e: Exception) { }
        server = null
        TnLog.i("lan: stopped")
    }

    private companion object {
        const val KNOCK_MS = 20000L
    }
}
