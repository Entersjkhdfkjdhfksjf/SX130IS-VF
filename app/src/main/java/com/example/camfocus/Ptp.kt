package com.example.camfocus

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpResult(val code: Int, val data: ByteArray, val params: IntArray)

class Ptp(
    private val conn: UsbDeviceConnection,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint
) {
    private var tid = 1

    private fun le32(v: Int) =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    companion object {
        private const val WRITE_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS  = 8000
        private const val MAX_RETRIES      = 2
    }

    /**
     * Clear a STALL / HALT condition on [ep] via USB control transfer
     * (CLEAR_FEATURE, feature selector 0 = ENDPOINT_HALT).
     * This is necessary when a previous transfer times out and the camera
     * stalls the endpoint; without clearing, all subsequent transfers fail.
     */
    private fun clearHalt(ep: UsbEndpoint) {
        conn.controlTransfer(
            UsbConstants.USB_TYPE_STANDARD or UsbConstants.USB_DIR_OUT
                or 0x02 /* RECIPIENT_ENDPOINT */,
            1,                     /* bRequest = CLEAR_FEATURE */
            0,                     /* wValue   = ENDPOINT_HALT feature selector */
            ep.address,            /* wIndex   = endpoint address */
            null, 0, 1000
        )
    }

    fun transact(opcode: Int, vararg params: Int): PtpResult {
        var pkt = le32(12 + params.size * 4) + le16(1) + le16(opcode) + le32(tid++)
        for (p in params) pkt += le32(p)

        var lastError = ""
        for (attempt in 0..MAX_RETRIES) {
            // Always clear possible stall on both endpoints (MTP detach can
            // leave them stalled, and a failed prior attempt definitely does)
            clearHalt(outEp)
            clearHalt(inEp)
            if (attempt > 0) Thread.sleep(200)

            val written = conn.bulkTransfer(outEp, pkt, pkt.size, WRITE_TIMEOUT_MS)
            if (written < 0) {
                lastError = "write failed (attempt ${attempt + 1}, returned $written)"
                continue
            }

            val buf = ByteArray(512 * 1024)
            var data = ByteArray(0)
            var failed = false

            while (true) {
                val n = conn.bulkTransfer(inEp, buf, buf.size, READ_TIMEOUT_MS)
                if (n < 0) {
                    lastError = "read timed out (attempt ${attempt + 1}, returned $n for opcode 0x%04X)".format(opcode)
                    failed = true
                    break
                }
                if (n < 12) {
                    lastError = "short read (got $n bytes, need >= 12, attempt ${attempt + 1})"
                    failed = true
                    break
                }

                val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                val total = bb.getInt(0)
                when (bb.getShort(4).toInt()) {
                    2 -> { // data container
                        val out = ByteArrayOutputStream(total.coerceAtLeast(64))
                        out.write(buf, 12, n - 12)
                        var remaining = total - n
                        while (remaining > 0) {
                            val m = conn.bulkTransfer(inEp, buf, buf.size, READ_TIMEOUT_MS)
                            if (m <= 0) {
                                lastError = "data phase failed (returned $m, remaining=$remaining)"
                                failed = true
                                break
                            }
                            out.write(buf, 0, m)
                            remaining -= m
                        }
                        if (failed) break
                        data = out.toByteArray()
                    }
                    3 -> { // response container
                        val np = (total - 12) / 4
                        return PtpResult(
                            bb.getShort(6).toInt() and 0xFFFF, data,
                            IntArray(np) { bb.getInt(12 + it * 4) }
                        )
                    }
                }
            }
            if (!failed) break
        }

        error(lastError)
    }

    fun openSession() = transact(0x1002, 1)
    fun chdk(sub: Int, vararg p: Int) = transact(0x9999, sub, *p)
}

