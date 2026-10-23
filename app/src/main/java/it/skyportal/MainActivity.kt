package it.skyportal

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Backup/ripristino statuine Skylanders tramite portale USB (VID 0x1430).
 * Report HID da 32 byte. Comandi: R reset, A attiva, S stato, Q leggi blocco, W scrivi blocco.
 */
class MainActivity : Activity() {
    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private var conn: UsbDeviceConnection? = null
    private var inEp: UsbEndpoint? = null
    private var ifaceId = 0
    private val busy = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 96, 32, 32)

        fun addButton(label: String, action: () -> Unit) {
            val b = Button(this)
            b.text = label
            b.setOnClickListener { action() }
            root.addView(b)
        }
        addButton("Connetti portale") { connect() }
        addButton("Backup statuina") { bg { backup() } }
        addButton("Ripristina ultimo backup") { bg { restore() } }

        logView = TextView(this)
        logView.setTextIsSelectable(true)
        root.addView(logView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    private fun log(s: String) {
        runOnUiThread { logView.append(s + "\n") }
    }

    private fun bg(f: () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            log("Operazione in corso...")
            return
        }
        thread {
            try {
                f()
            } catch (e: Exception) {
                log("Errore: " + e.message)
            } finally {
                busy.set(false)
            }
        }
    }

    // ---------- USB ----------

    private fun connect() {
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == 0x1430 }
        if (dev == null) {
            log("Portale non trovato (cavo OTG collegato?)")
            return
        }
        if (!usb.hasPermission(dev)) {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent("it.skyportal.USB").setPackage(packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(dev, pi)
            log("Concedi il permesso USB, poi premi di nuovo Connetti")
            return
        }
        bg { log(if (openPortal(dev)) "Portale connesso" else "Connessione fallita") }
    }

    private fun close() {
        conn?.close()
        conn = null
        inEp = null
    }

    private fun cmd(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun openPortal(dev: UsbDevice): Boolean {
        close()
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                ) {
                    val c = usb.openDevice(dev) ?: return false
                    if (!c.claimInterface(itf, true)) {
                        c.close()
                        return false
                    }
                    conn = c
                    inEp = ep
                    ifaceId = itf.id
                    val ok = xfer(cmd('R'.code)) != null
                    xfer(cmd('A'.code, 1))
                    return ok
                }
            }
        }
        return false
    }

    private fun xfer(c: ByteArray): ByteArray? {
        val cn = conn ?: return null
        val ep = inEp ?: return null
        if (cn.controlTransfer(0x21, 0x09, 0x0200, ifaceId, c.copyOf(32), 32, 1000) < 0) return null
        for (i in 0 until 30) {
            val r = ByteArray(32)
            if (cn.bulkTransfer(ep, r, 32, 300) > 0 && r[0] == c[0]) return r
        }
        return null
    }

    // ---------- Portale ----------

    private fun figures(): List<Int> {
        val r = xfer(cmd('S'.code)) ?: return emptyList()
        var st = 0
        for (i in 4 downTo 1) st = (st shl 8) or (r[i].toInt() and 0xFF)
        return (0 until 16).filter { ((st shr (2 * it)) and 1) == 1 }
    }

    private fun readBlock(idx: Int, blk: Int): ByteArray? {
        val r = xfer(cmd('Q'.code, 0x20 or idx, blk)) ?: return null
        if ((r[1].toInt() and 0x10) == 0 || r[2].toInt() != blk) return null
        return r.copyOfRange(3, 19)
    }

    private fun writeBlock(idx: Int, blk: Int, data: ByteArray): Boolean {
        val r = xfer(cmd('W'.code, 0x20 or idx, blk) + data) ?: return false
        return (r[1].toInt() and 0x10) != 0
    }

    private fun dump(idx: Int): ByteArray? {
        val out = ByteArray(1024)
        for (blk in 0 until 64) {
            var d: ByteArray? = null
            var t = 0
            while (d == null && t < 3) {
                d = readBlock(idx, blk)
                t++
            }
            (d ?: return null).copyInto(out, blk * 16)
        }
        return out
    }

    private fun hex(d: ByteArray) = d.take(4).joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun dumpDir(): File = getExternalFilesDir("dumps") ?: filesDir

    // ---------- Azioni ----------

    private fun backup() {
        if (conn == null) return log("Prima connetti il portale")
        val idx = figures().firstOrNull() ?: return log("Nessuna statuina sul portale")
        log("Lettura in corso...")
        val d = dump(idx) ?: return log("Lettura fallita: riappoggia la statuina e riprova")
        val name = hex(d) + "_" + (System.currentTimeMillis() / 1000) + ".sky"
        File(dumpDir(), name).writeBytes(d)
        log("Backup salvato: $name")
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SkyPortal")
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(d) }
                    log("Copia in Download/SkyPortal")
                }
            } catch (e: Exception) {
                log("Copia in Download non riuscita: " + e.message)
            }
        }
    }

    private fun restore() {
        if (conn == null) return log("Prima connetti il portale")
        val idx = figures().firstOrNull() ?: return log("Nessuna statuina sul portale")
        val uid = hex(readBlock(idx, 0) ?: return log("Impossibile leggere la statuina"))
        val f = dumpDir().listFiles()
            ?.filter { it.name.startsWith(uid) && it.name.endsWith(".sky") }
            ?.maxByOrNull { it.name }
            ?: return log("Nessun backup trovato per questa statuina ($uid)")
        val d = f.readBytes()
        if (d.size != 1024) return log("File di backup non valido")
        for (blk in 1 until 64) {
            if (blk % 4 == 3) continue // salta i sector trailer (chiavi)
            val x = d.copyOfRange(blk * 16, blk * 16 + 16)
            if (!writeBlock(idx, blk, x) || readBlock(idx, blk)?.contentEquals(x) != true) {
                return log("Errore sul blocco $blk: scrittura interrotta")
            }
        }
        log("Ripristinato da " + f.name)
    }
}
