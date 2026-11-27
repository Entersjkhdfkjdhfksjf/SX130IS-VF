package com.example.camfocus

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    companion object {
        private const val ACTION_PERM = "com.example.camfocus.USB_PERM"
        private const val CANON_VID = 0x04A9
        private const val SX130_PID = 0x3211
        // Verified identical across SX130 IS firmware 101c / 101d / 101f in CHDK stubs_entry.S + lib.c
        private const val SX130_ACTIVE_PALETTE_ADDR = 0x00009460
        private const val SX130_PALETTE_BUFFER_ADDR = 0x000B3A58
        private const val SX130_PALETTE_OFFSET = 8
        private const val PALETTE_TYPE3_SIZE = 256 * 4
    }

    private lateinit var usb: UsbManager
    private lateinit var viewfinder: ViewfinderView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var toggleStreamBtn: Button
    private lateinit var overlayBtn: Button
    private lateinit var peakingBtn: Button
    private lateinit var logBtn: Button

    @Volatile private var streaming = false
    @Volatile private var showOverlay = false
    @Volatile private var focusPeaking = false
    @Volatile private var activeDeviceId = -1
    private var workerThread: Thread? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

            when (i.action) {
                ACTION_PERM -> {
                    if (dev != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                        startStream(dev)
                    else say("Permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (dev != null && dev.deviceId == activeDeviceId) {
                        say("Camera disconnected")
                        stopStream()
                    }
                }
            }
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        toggleStreamBtn = Button(this).apply {
            text = "Connect"
            setOnClickListener {
                if (streaming) stopStream() else connectCanon(null)
            }
        }
        overlayBtn = Button(this).apply {
            text = "OSD: OFF"
            setOnClickListener {
                showOverlay = !showOverlay
                text = if (showOverlay) "OSD: ON" else "OSD: OFF"
            }
        }
        peakingBtn = Button(this).apply {
            text = "Peak: OFF"
            setOnClickListener {
                focusPeaking = !focusPeaking
                text = if (focusPeaking) "Peak: ON" else "Peak: OFF"
            }
        }
        logBtn = Button(this).apply {
            text = "Hide Log"
            setOnClickListener {
                val show = scroll.visibility != View.VISIBLE
                scroll.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "Hide Log" else "Show Log"
            }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF181818.toInt())
            setPadding(12, 8, 12, 8)
            addView(toggleStreamBtn)
            addView(overlayBtn)
            addView(peakingBtn)
            addView(logBtn)
        }

        viewfinder = ViewfinderView(this)
        log = TextView(this).apply {
            setPadding(20, 12, 20, 12)
            textSize = 12f
            setTextColor(0xFFDDDDDD.toInt())
            setBackgroundColor(0xFF111111.toInt())
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF111111.toInt())
            addView(log)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(bar)
            addView(viewfinder, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 240
            ))
        })

        usb = getSystemService(USB_SERVICE) as UsbManager
        val filter = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        connectCanon(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStream()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
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
            say("No Canon device found. Plug in the camera and tap Connect.")
            return
        }
        say("Found: ${dev.productName ?: "Canon"} (vid=0x%04X pid=0x%04X)".format(dev.vendorId, dev.productId))

        if (usb.hasPermission(dev)) {
            startStream(dev)
        } else {
            say("Requesting USB permission…")
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_PERM).setPackage(packageName), PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(dev, pi)
        }
    }

    private fun startStream(dev: UsbDevice) {
        stopStream()
        streaming = true
        activeDeviceId = dev.deviceId
        runOnUiThread { toggleStreamBtn.text = "Stop" }

        workerThread = Thread {
            try {
                streamLoop(dev)
            } finally {
                streaming = false
                activeDeviceId = -1
                runOnUiThread { toggleStreamBtn.text = "Connect" }
            }
        }.also { it.start() }
    }

    private fun stopStream() {
        streaming = false
        val t = workerThread
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(1500) } catch (_: InterruptedException) {}
        }
        workerThread = null
    }

    private fun say(t: String) = runOnUiThread {
        log.append(t + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setLogVisible(visible: Boolean) = runOnUiThread {
        scroll.visibility = if (visible) View.VISIBLE else View.GONE
        logBtn.text = if (visible) "Hide Log" else "Show Log"
    }

    private fun openAndClaim(dev: UsbDevice): Triple<UsbDeviceConnection, UsbInterface, Pair<UsbEndpoint, UsbEndpoint>> {
        val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .firstOrNull { it.interfaceClass == 6 /* Still Image / PTP */ }
            ?: throw IllegalStateException("No PTP interface (class 6) found on device")

        val conn = usb.openDevice(dev)
            ?: throw IllegalStateException("openDevice returned null — USB permission missing?")

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

    private fun streamLoop(dev: UsbDevice) {
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
            say("OpenSession: 0x%04X".format(sess.code))

            val v = p.chdk(0) // PTP_CHDK_Version = 0
            say("CHDK Version resp=0x%04X params=%s".format(v.code, v.params.joinToString()))
            if (v.code != Ptp.RC_OK) {
                say("CHDK did not respond with OK (0x%04X). Is CHDK loaded on the SD card?".format(v.code))
                return
            }

            val platform = ensureRecordMode(p)
            val useSx130RamPalette =
                (platform == "sx130is") ||
                (platform == null && dev.vendorId == CANON_VID && dev.productId == SX130_PID)

            val decoder = LvDecoder()
            var frames = 0
            var totalFrames = 0
            var fpsWindowStart = System.currentTimeMillis()
            var fpsText = "--"
            var consecutiveErrors = 0
            var cachedPalette: ByteArray? = null
            var lastPalFetchMs = 0L
            var loggedPalAddr = Int.MIN_VALUE

            while (streaming) {
                val flags = if (showOverlay) (0x01 or 0x04 or 0x08) else 0x01
                try {
                    val lv = p.chdk(12, flags) // PTP_CHDK_GetDisplayData = 12
                    if (lv.code != Ptp.RC_OK || lv.data.size < 28) {
                        consecutiveErrors++
                        if (consecutiveErrors >= 5) {
                            say("GetDisplayData returned 0x%04X (${lv.data.size} bytes), stopping".format(lv.code))
                            break
                        }
                        Thread.sleep(50)
                        continue
                    }

                    val frame = LvFrame(lv.data)
                    if (showOverlay && useSx130RamPalette && frame.paletteType == 0 && frame.bm.dataStart > 0) {
                        val nowPal = System.currentTimeMillis()
                        if (cachedPalette == null || nowPal - lastPalFetchMs >= 750L) {
                            lastPalFetchMs = nowPal
                            val palResult = fetchSx130Palette(p)
                            if (palResult != null) {
                                cachedPalette = palResult.second
                                if (palResult.first != loggedPalAddr) {
                                    loggedPalAddr = palResult.first
                                    say("Loaded SX130 live palette @ 0x%08X".format(palResult.first))
                                }
                            }
                        }
                    }
                    val decoded = decoder.decode(
                        frame,
                        showOverlay,
                        focusPeaking,
                        overridePalette = if (frame.paletteType == 0) cachedPalette else null
                    )
                    if (decoded != null) {
                        consecutiveErrors = 0
                        frames++
                        totalFrames++
                        val now = System.currentTimeMillis()
                        val elapsed = now - fpsWindowStart
                        if (elapsed >= 500) {
                            val fps = frames * 1000f / elapsed
                            fpsText = "%.1f fps".format(fps)
                            frames = 0
                            fpsWindowStart = now
                        }
                        val peakTag = if (focusPeaking) " | PEAK" else ""
                        val osdTag = if (showOverlay) " | OSD" else ""
                        val hud = "${decoded.width}x${decoded.height} | $fpsText$peakTag$osdTag"
                        viewfinder.submitFrame(decoded.pixels, decoded.width, decoded.height, decoded.aspectRatio, hud)

                        if (totalFrames == 1) {
                            say("Streaming live view (${decoded.width}x${decoded.height})")
                            setLogVisible(false)
                        }
                    } else {
                        consecutiveErrors++
                        if (consecutiveErrors == 1) {
                            say("Waiting for live viewport data (vp.type=${frame.vp.type} start=${frame.vp.dataStart})…")
                        }
                        Thread.sleep(50)
                    }
                } catch (e: Exception) {
                    if (!streaming) break
                    consecutiveErrors++
                    say("Frame error ($consecutiveErrors/5): ${e.message}")
                    if (consecutiveErrors >= 5) {
                        setLogVisible(true)
                        break
                    }
                    Thread.sleep(100)
                }
            }
        } catch (e: Exception) {
            say("Error: $e")
            setLogVisible(true)
        } finally {
            try { ptp?.closeSession() } catch (_: Exception) {}
            try { if (intf != null) conn?.releaseInterface(intf) } catch (_: Exception) {}
            try { conn?.close() } catch (_: Exception) {}
            say("Connection closed.")
        }
    }

    /**
     * Switch the camera back to Record (shooting) mode over USB via CHDK's
     * PTP_CHDK_ExecuteScript (subcommand 7, SL_LUA = 0) and return the CHDK platform name.
     */
    private fun ensureRecordMode(ptp: Ptp): String? {
        var platform: String? = null
        try {
            say("Ensuring camera is in shooting mode…")
            val script = "if not get_mode() then switch_mode_usb(1) repeat sleep(50) until get_mode() end\nreturn get_buildinfo().platform"
            val res = ptp.chdkExecLua(script)
            say("ExecuteScript resp=0x%04X params=%s".format(res.code, res.params.joinToString()))
            if (res.code != Ptp.RC_OK) return null

            val deadline = System.currentTimeMillis() + 6000
            while (streaming && System.currentTimeMillis() < deadline) {
                var st = ptp.chdk(8) // PTP_CHDK_ScriptStatus
                var flags = st.params.getOrElse(0) { 0 }
                var drained = 0
                while ((flags and 0x2) != 0 && drained < 8) {
                    val msg = ptp.chdk(10) // PTP_CHDK_ReadScriptMsg
                    val msgType = msg.params.getOrElse(0) { 0 }
                    val txt = String(msg.data, Charsets.UTF_8).trimEnd('\u0000')
                    if (msgType == 2 && txt.isNotEmpty()) { // PTP_CHDK_S_MSG_TYPE_RET
                        platform = txt
                        say("Camera platform: $txt")
                    } else if (txt.isNotEmpty() || msgType == 1) {
                        say("Script msg(type=$msgType): $txt")
                    }
                    drained++
                    st = ptp.chdk(8)
                    flags = st.params.getOrElse(0) { 0 }
                }
                if ((flags and 0x1) == 0) {
                    say("Camera in shooting mode ✓")
                    Thread.sleep(300)
                    return platform
                }
                Thread.sleep(150)
            }
        } catch (e: Exception) {
            say("Mode switch failed: ${e.message}")
        }
        return platform
    }

    /**
     * Reads Canon SX130 IS's active 256-entry VUYA palette directly from RAM when CHDK
     * builds omit CAM_CHDK_PTP_LIVE_VIEW and GetDisplayData reports palette_type=0.
     */
    private fun fetchSx130Palette(ptp: Ptp): Pair<Int, ByteArray>? {
        return try {
            val actRes = ptp.chdkGetMemory(SX130_ACTIVE_PALETTE_ADDR, 4)
            if (actRes.code != Ptp.RC_OK || actRes.data.size < 4) return null
            val activeIdx = readLe32(actRes.data)
            if (activeIdx !in 0..15) return null

            val ptrRes = ptp.chdkGetMemory(SX130_PALETTE_BUFFER_ADDR + activeIdx * 4, 4)
            if (ptrRes.code != Ptp.RC_OK || ptrRes.data.size < 4) return null
            val palPtr = readLe32(ptrRes.data)
            if ((palPtr and 3) != 0 || palPtr < 0x10000 || palPtr > 0x04000000) return null

            val palAddr = palPtr + SX130_PALETTE_OFFSET
            val palRes = ptp.chdkGetMemory(palAddr, PALETTE_TYPE3_SIZE)
            if (palRes.code != Ptp.RC_OK || palRes.data.size < PALETTE_TYPE3_SIZE) return null
            palAddr to palRes.data
        } catch (_: Exception) {
            null
        }
    }

    private fun readLe32(b: ByteArray, off: Int = 0): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
