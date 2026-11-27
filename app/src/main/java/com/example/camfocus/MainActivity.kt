package com.example.camfocus

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
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
                Thread { runTest(dev) }.start()
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

        val cam = usb.deviceList.values.firstOrNull { it.vendorId == 0x04A9 }
        if (cam == null) { say("No Canon device found. Plug in the camera."); return }
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION).setPackage(packageName), PendingIntent.FLAG_MUTABLE
        )
        usb.requestPermission(cam, pi)
    }

    private fun say(t: String) = runOnUiThread { log.append(t + "\n") }

    private fun runTest(dev: UsbDevice) {
        try {
            val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }
                .first { it.interfaceClass == 6 }
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val e = intf.getEndpoint(i)
                if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
                }
            }
            val conn = usb.openDevice(dev)
            conn.claimInterface(intf, true)
            val ptp = Ptp(conn, inEp!!, outEp!!)

            val sess = ptp.openSession()
            say("OpenSession: 0x%04X%s".format(
                sess.code,
                if (sess.code == 0x201E) " (already open, OK)" else ""
            ))

            val v = ptp.chdk(0) // PTP_CHDK_Version = 0
            say("CHDK Version resp=0x%04X params=%s".format(v.code, v.params.joinToString()))

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
        } catch (e: Exception) {
            say("Error: $e")
        }
    }
}
