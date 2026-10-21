package ir.takhtenard.game

/* The native half of the contract written in tn/android/BRIDGE.md. Everything the game knows about a
   radio is: open a channel, hand it strings, get strings back. Dice, moves, the clock and every
   refusal stay in the HTML — nothing here reads, rewrites or answers a message. */

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal const val TAG = "TakhteNard"

/**
 * A rolling trace of what the radios actually did. Nobody who plays this game has adb, so the only
 * place a failure can be diagnosed is the phone's own screen: MainActivity shows this text when an
 * attempt ends, and one screenshot of it says which stage stopped and with which code. Every stage
 * that can fail quietly writes here as it happens, including the ones that succeed.
 */
object TnLog {
    private val lines = ArrayList<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(msg: String) {
        Log.i(TAG, msg)
        synchronized(lines) {
            lines.add(stamp.format(Date()) + "  " + msg)
            while (lines.size > 80) lines.removeAt(0)
        }
    }

    fun clear() { synchronized(lines) { lines.clear() } }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }
}

/* Both sides use the same port, so nobody ever has to be told one: the group owner's address comes
   from WifiP2pManager and the port is this constant. */
internal const val P2P_PORT = 45678

/* One fixed service UUID for the Bluetooth fallback; it is how a guest finds the host's socket. */
internal val BT_UUID: UUID = UUID.fromString("7d3f4a2e-5b18-4c96-9a2d-1e6f0c8b54a7")
internal const val BT_NAME = "Takhte Nard"

/** A device the guest can choose. id is what pick() wants back, name is what the player reads. */
data class TnPeer(val id: String, val name: String)

/** One connected pair of streams, whichever radio it came from. */
class TnPipe(val input: InputStream, val output: OutputStream, private val owner: Closeable) {
    fun close() {
        try { owner.close() } catch (e: Exception) { Log.i(TAG, "pipe close: " + e.message) }
    }
}

/**
 * What a radio has to do. Callbacks may fire on any thread; the activity marshals them onto the UI
 * thread before they touch a dialog or the WebView.
 *
 * onPipe  — fires once, when the two phones are connected.
 * onPeers — fires on the guest with the phones around it; the player picks one, then pick() runs.
 * onFail  — ends the attempt with a sentence a player can read.
 */
interface TnConnector {
    fun start(role: String, onPipe: (TnPipe) -> Unit, onPeers: (List<TnPeer>) -> Unit, onFail: (String) -> Unit)
    fun pick(id: String)
    fun stop()
}

/**
 * Splits a byte stream into whole lines. A socket read can hand over half a message, or two messages
 * glued together, and the game tolerates neither, so framing lives here. It buffers bytes rather than
 * characters: a Persian name can be cut in the middle of a multi-byte sequence, and decoding each
 * read on its own would corrupt it.
 */
class LineFramer {
    private val buf = ByteArrayOutputStream()

    fun push(bytes: ByteArray, n: Int, out: (String) -> Unit) {
        buf.write(bytes, 0, n)
        val a = buf.toByteArray()
        var start = 0
        var i = 0
        while (i < a.size) {
            if (a[i] == NL) {
                if (i > start) out(String(a, start, i - start, Charsets.UTF_8))
                start = i + 1
            }
            i++
        }
        if (start > 0) {
            buf.reset()
            if (start < a.size) buf.write(a, start, a.size - start)
        }
    }

    private companion object { val NL: Byte = 10 }
}

/**
 * The two threads around one pipe: a reader that frames lines, and a writer fed by a queue, so a
 * JavaScript send() never blocks on a radio and a burst of messages keeps its order. onDone fires
 * exactly once, whichever end broke first — that is what tells the game "the other phone is gone".
 */
class PipeLink(private val pipe: TnPipe,
               private val onText: (String) -> Unit,
               private val onDone: () -> Unit) {

    private val out = LinkedBlockingQueue<String>()
    private val alive = AtomicBoolean(true)
    private var writer: Thread? = null

    fun start() {
        val reader = Thread {
            val framer = LineFramer()
            val b = ByteArray(8192)
            try {
                while (alive.get()) {
                    val n = pipe.input.read(b)
                    if (n < 0) break
                    framer.push(b, n) { line -> onText(line) }
                }
            } catch (e: Exception) {
                Log.i(TAG, "read ended: " + e.message)
            }
            shutdown()
        }
        reader.isDaemon = true
        reader.name = "tn-read"
        reader.start()

        val w = Thread {
            try {
                while (alive.get()) {
                    val s = out.take()
                    pipe.output.write((s + "\n").toByteArray(Charsets.UTF_8))
                    pipe.output.flush()
                }
            } catch (e: Exception) {
                Log.i(TAG, "write ended: " + e.message)
            }
        }
        w.isDaemon = true
        w.name = "tn-write"
        w.start()
        writer = w
    }

    fun send(text: String) {
        if (alive.get()) out.offer(text)
    }

    fun shutdown() {
        if (!alive.compareAndSet(true, false)) return
        pipe.close()
        writer?.interrupt()
        onDone()
    }

    /** Close without telling the game — used when the page itself asked for a new channel. */
    fun detach() {
        if (!alive.compareAndSet(true, false)) return
        pipe.close()
        writer?.interrupt()
    }
}
