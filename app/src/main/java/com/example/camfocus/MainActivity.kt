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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {
    private val ACTION = "com.example.camfocus.USB_PERM"
    private lateinit var usb: UsbManager
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView

    @Volatile
    private var running = false

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (dev != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                startCameraThread(dev)
            else say("Permission denied")
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val retryBtn = Button(this).apply {
            text = "Connect / Retry"
            setOnClickListener { connectCanon(null) }
        }
        val clearBtn = Button(this).apply {
            text = "Clear"
            setOnClickListener { log.text = "" }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 8)
            addView(retryBtn)
            addView(clearBtn)
        }
        log = TextView(this).apply { setPadding(24, 16, 24, 24); textSize = 13f }
        scroll = ScrollView(this).apply { addView(log) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        })

        usb = getSystemService(USB_SERVICE) as UsbManager
        ContextCompat.registerReceiver(
            this, permReceiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        connectCanon(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(permReceiver) } catch (_: Exception) {}
    }

    /** Handle camera plug-in while activity is already open (singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val dev = extractDevice(intent) ?: return
        say("\nCamera attached")
        connectCanon(intent, dev)
    }

    private fun extractDevice(i: Intent?): UsbDevice? {
        if (i?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        return if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun connectCanon(launchIntent: Intent?, explicitDev: UsbDevice? = null) {
        val dev = explicitDev
            ?: extractDevice(launchIntent)
            ?: usb.deviceList.values.firstOrNull { it.vendorId == 0x04A9 }
        if (dev == null) {
            say("No Canon device found. Plug in the camera and tap Connect / Retry.")
            return
        }
        say("Found: ${dev.productName ?: "Canon"} (vid=0x%04X pid=0x%04X)".format(dev.vendorId, dev.productId))

        if (usb.hasPermission(dev)) {
            startCameraThread(dev)
        } else {
            say("Requesting USB permission…")
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION).setPackage(packageName), PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(dev, pi)
        }
    }

    private fun startCameraThread(dev: UsbDevice) {
        if (running) {
            say("Already connecting…")
            return
        }
        running = true
        Thread {
            try {
                runCamera(dev)
            } finally {
                running = false
            }
        }.start()
    }

    private fun say(t: String) = runOnUiThread {
        log.append(t + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openAndClaim(dev: UsbDevice): Triple<UsbDeviceConnection, UsbInterface, Pair<UsbEndpoint, UsbEndpoint>> {
        val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .firstOrNull { it.interfaceClass == 6 /* Still Image / PTP */ }
            ?: throw IllegalStateException("No PTP interface (class 6) found on device")

        val conn = usb.openDevice(dev)
            ?: throw IllegalStateException("openDevice returned null — USB permission missing?")

        // Retry claim briefly in case Android's MTP scanner is still releasing the interface.
        // Do NOT send raw controlTransfer(SET_INTERFACE) or controlTransfer(CLEAR_FEATURE)
        // here: those desync host xHCI data toggles from the camera and hang USB3 pipes.
        var claimed = false
        for (i in 0 until 5) {
            claimed = conn.claimInterface(intf, true)
            if (claimed) break
            say("  claimInterface busy (try ${i + 1}/5), waiting…")
            Thread.sleep(300)
        }
        say("claimInterface(id=${intf.id}, force=true) → $claimed")
        if (!claimed) {
            conn.close()
            throw IllegalStateException("Could not claim PTP interface (held by Android MTP/another app)")
        }

        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(i)
            say("  ep[$i] addr=0x%02X type=%d dir=%s maxPkt=%d".format(
                e.address, e.type,
                if (e.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
                e.maxPacketSize
            ))
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
            }
        }
        if (inEp == null || outEp == null) {
            conn.releaseInterface(intf)
            conn.close()
            throw IllegalStateException("Bulk endpoints not found (in=$inEp out=$outEp)")
        }

        Thread.sleep(100)
        return Triple(conn, intf, inEp to outEp)
    }

    private fun runCamera(dev: UsbDevice) {
        var conn: UsbDeviceConnection? = null
        var intf: UsbInterface? = null
        var ptp: Ptp? = null
        try {
            val claimed = openAndClaim(dev)
            conn = claimed.first
            intf = claimed.second
            val (inEp, outEp) = claimed.third

            val p = Ptp(conn, intf, inEp, outEp, ::say)
            ptp = p

            val sess = p.openSession()
            say("OpenSession: 0x%04X%s".format(
                sess.code,
                when (sess.code) {
                    Ptp.RC_OK -> " (OK)"
                    Ptp.RC_SESSION_ALREADY_OPEN -> " (already open, OK)"
                    else -> ""
                }
            ))

            // PTP_CHDK_Version = 0 per CHDK ptp.h
            val v = p.chdk(0)
            say("CHDK Version resp=0x%04X params=%s".format(v.code, v.params.joinToString()))
            if (v.code != Ptp.RC_OK) {
                say("CHDK did not respond with OK (0x%04X). Is CHDK loaded on the SD card?".format(v.code))
                return
            }

            // Canon PowerShots switch to Playback on USB connect; use CHDK Lua switch_mode_usb(1)
            // to extend the lens back into Record (shooting) mode.
            ensureRecordMode(p)

            // PTP_CHDK_GetDisplayData = 12 (0x0C) per CHDK ptp.h enum PTP_CHDK_Command
            // LV_TFR flags per live_view.h: viewport=0x01, bitmap=0x04, palette=0x08
            val lv = p.chdk(12, 0x01 or 0x04 or 0x08)
            say("GetDisplayData resp=0x%04X bytes=%d params=%s".format(
                lv.code, lv.data.size, lv.params.joinToString()
            ))

            if (lv.data.size >= 28) {
                val f = LvFrame(lv.data)
                say("LV v${f.versionMajor}.${f.versionMinor} aspect=${f.lcdAspectRatio} palette_type=${f.paletteType}")
                say("VP fb_type=${f.vp.type} bufW=${f.vp.bufW} vis=${f.vp.visW}x${f.vp.visH} start=${f.vp.dataStart}")
                say("BM fb_type=${f.bm.type} bufW=${f.bm.bufW} vis=${f.bm.visW}x${f.bm.visH} start=${f.bm.dataStart}")
                val file = File(getExternalFilesDir(null), "lv.bin")
                file.writeBytes(lv.data)
                say("Saved ${file.absolutePath}")
            }
        } catch (e: Exception) {
            say("Error: $e")
        } finally {
            try { ptp?.closeSession() } catch (_: Exception) {}
            try { if (intf != null) conn?.releaseInterface(intf) } catch (_: Exception) {}
            try { conn?.close() } catch (_: Exception) {}
            say("Connection closed.")
        }
    }

    /**
     * Switch the camera back to Record (shooting) mode over USB via CHDK's
     * PTP_CHDK_ExecuteScript (subcommand 7, SL_LUA = 0).
     */
    private fun ensureRecordMode(ptp: Ptp) {
        try {
            say("Ensuring camera is in shooting mode…")
            val script = "if not get_mode() then switch_mode_usb(1) repeat sleep(50) until get_mode() end"
            val res = ptp.chdkExecLua(script)
            say("ExecuteScript resp=0x%04X params=%s".format(res.code, res.params.joinToString()))
            if (res.code != Ptp.RC_OK) return

            // Poll PTP_CHDK_ScriptStatus (sub=8) until script finishes (bit 0 = RUN)
            val deadline = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < deadline) {
                val st = ptp.chdk(8)
                val flags = st.params.getOrElse(0) { 0 }
                if ((flags and 0x2) != 0) {
                    // Drain waiting script message (PTP_CHDK_ReadScriptMsg = 10)
                    val msg = ptp.chdk(10)
                    val msgType = msg.params.getOrElse(0) { 0 }
                    val txt = String(msg.data, Charsets.UTF_8).trimEnd('\u0000')
                    if (txt.isNotEmpty() || msgType == 1) {
                        say("Script msg(type=$msgType): $txt")
                    }
                }
                if ((flags and 0x1) == 0) {
                    say("Camera in shooting mode ✓")
                    Thread.sleep(300) // let live view viewport stabilize after lens extension
                    return
                }
                Thread.sleep(150)
            }
            say("Timed out waiting for mode switch (continuing anyway)")
        } catch (e: Exception) {
            say("Mode switch failed: ${e.message}")
        }
    }
}

