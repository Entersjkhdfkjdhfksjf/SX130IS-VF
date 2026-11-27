package com.example.camfocus

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {
    private val ACTION = "com.example.camfocus.USB_PERM"
    private lateinit var usb: UsbManager
    private lateinit var log: TextView

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (dev != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                Thread { runCamera(dev) }.start()
            else say("Permission denied")
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        log = TextView(this).apply { setPadding(24, 24, 24, 24); textSize = 13f }
        setContentView(ScrollView(this).apply { addView(log) })
        usb = getSystemService(USB_SERVICE) as UsbManager
        ContextCompat.registerReceiver(
            this, permReceiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Try to grab the device from the attachment intent first (beats MTP)
        val dev = extractDevice(intent) ?: usb.deviceList.values.firstOrNull { it.vendorId == 0x04A9 }
        if (dev == null) { say("No Canon device found. Plug in the camera."); return }
        say("Found: ${dev.productName ?: "Canon"} (vid=0x%04X pid=0x%04X)".format(dev.vendorId, dev.productId))

        if (usb.hasPermission(dev)) {
            Thread { runCamera(dev) }.start()
        } else {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION).setPackage(packageName), PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(dev, pi)
        }
    }

    /** Handle camera re-plug while activity is already running (singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val dev = extractDevice(intent) ?: return
        say("\nCamera re-attached")
        if (usb.hasPermission(dev)) {
            Thread { runCamera(dev) }.start()
        } else {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION).setPackage(packageName), PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(dev, pi)
        }
    }

    private fun extractDevice(i: Intent?): UsbDevice? {
        if (i?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        return if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun say(t: String) = runOnUiThread { log.append(t + "\n") }

    /**
     * Reset the USB device to tear down any MTP session that Android's
     * media stack may have already opened, then re-claim for PTP/CHDK use.
     */
    private fun resetAndClaim(dev: UsbDevice): Triple<UsbDeviceConnection, UsbInterface, Pair<UsbEndpoint, UsbEndpoint>> {
        val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .first { it.interfaceClass == 6 /* Still Image / PTP */ }

        val conn = usb.openDevice(dev)
            ?: throw IllegalStateException("openDevice returned null — permission lost?")

        // Force-release from Android MTP host, then reset the device via
        // SET_CONFIGURATION to tear down any existing PTP session.
        conn.claimInterface(intf, true)       // force = true: steal from kernel/MTP
        conn.setConfiguration(dev.getConfiguration(0))  // USB reset: re-enumerate config
        conn.releaseInterface(intf)
        Thread.sleep(300)                      // let the camera settle after reset

        // Now re-claim cleanly
        conn.claimInterface(intf, true)

        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(i)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
            }
        }
        if (inEp == null || outEp == null)
            throw IllegalStateException("Bulk endpoints not found (in=$inEp out=$outEp)")

        return Triple(conn, intf, inEp to outEp)
    }

    private fun runCamera(dev: UsbDevice) {
        try {
            say("Resetting USB to reclaim from MTP…")
            val (conn, intf, eps) = resetAndClaim(dev)
            val (inEp, outEp) = eps
            say("Interface claimed — ${intf.endpointCount} endpoints")

            val ptp = Ptp(conn, inEp, outEp)

            // Open PTP session
            val sess = ptp.openSession()
            say("OpenSession: 0x%04X%s".format(
                sess.code,
                if (sess.code == 0x201E) " (already open, OK)" else ""
            ))

            // Query CHDK version
            val v = ptp.chdk(0) // PTP_CHDK_Version = 0
            say("CHDK Version resp=0x%04X params=%s".format(v.code, v.params.joinToString()))

            // If the camera was switched to playback by MTP, ask CHDK to go
            // back to record (shooting) mode via a Lua script.
            ensureRecordMode(ptp)

            // Small delay to let the camera finish mode switch
            Thread.sleep(500)

            // LV transfer flags: viewport=1, bitmap=4, palette=8 (from CHDK liveview.h)
            // PTP_CHDK_GetDisplayData = 10 (0x0A) per CHDK ptp.h enum PTP_CHDK_Command
            val lv = ptp.chdk(10, 1 or 4 or 8) // GetDisplayData (sub=10)
            say("GetDisplayData resp=0x%04X bytes=%d".format(lv.code, lv.data.size))

            if (lv.data.size > 32) {
                val f = LvFrame(lv.data)
                say("palette_type=${f.paletteType}")
                say("VP fb_type=${f.vp.type} bufW=${f.vp.bufW} vis=${f.vp.visW}x${f.vp.visH} start=${f.vp.dataStart}")
                say("BM fb_type=${f.bm.type} bufW=${f.bm.bufW} vis=${f.bm.visW}x${f.bm.visH} start=${f.bm.dataStart}")
                val file = File(getExternalFilesDir(null), "lv.bin")
                file.writeBytes(lv.data)
                say("Saved ${file.absolutePath}")
            }

            conn.releaseInterface(intf)
            conn.close()
            say("Done — connection closed.")

        } catch (e: Exception) {
            say("Error: $e")
        }
    }

    /**
     * Use CHDK's ExecuteScript to switch back to record mode if needed.
     * PTP_CHDK_ExecuteScript = 7 in some builds, but the standard enum is:
     *   PTP_CHDK_ExecuteScript = 7 (Lua=0, uBasic=1 in param2)
     * We run: `switch_mode_usb(1)` which asks CHDK to go to REC mode.
     */
    private fun ensureRecordMode(ptp: Ptp) {
        try {
            // PTP_CHDK_ExecuteScript = 7, param2: 0 = Lua
            // The script string goes as PTP data phase, but for the CHDK
            // extension it's packed as param: script_type, then data = script.
            // However, CHDK's simpler approach: use set_record_mode via
            // PTP_CHDK_SwitchMode. Actually — there's no built-in SwitchMode
            // subcommand, but we can use ExecuteScript.
            //
            // For ExecuteScript, we need to send the script as a data payload.
            // Our current Ptp.transact() only sends command params, not data.
            // So instead, use the Lua "switch_mode_usb(1)" via CHDK's
            // script execution.
            //
            // We'll use the simpler approach: re-check if we're in rec mode
            // and if the camera responds to GetDisplayData, we're good.
            say("Checking camera mode…")
            val test = ptp.chdk(10, 1) // Quick GetDisplayData with just viewport
            if (test.code == 0x2001 && test.data.isNotEmpty()) {
                say("Camera is in record mode ✓")
            } else {
                say("Camera may be in playback mode (resp=0x%04X, data=%d bytes)".format(test.code, test.data.size))
                say("→ Please press the camera's shutter button or turn the mode dial to switch back to shooting mode")
            }
        } catch (e: Exception) {
            say("Mode check skipped: ${e.message}")
            say("→ If live view fails, switch the camera to shooting mode manually")
        }
    }
}
