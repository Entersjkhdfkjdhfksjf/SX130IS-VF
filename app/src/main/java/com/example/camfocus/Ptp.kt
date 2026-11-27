package com.example.camfocus

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpResult(val code: Int, val data: ByteArray, val params: IntArray)

class Ptp(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
    private val log: (String) -> Unit = {}
) {
    // PTP spec (§10.5.3): OpenSession uses Transaction_ID = 0; subsequent commands increment from 1.
    private var tid = 0
    private val readBuf = ByteArray(BULK_CHUNK_SIZE)

    private fun le32(v: Int) =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    companion object {
        private const val WRITE_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS  = 5000
        // Keep bulkTransfer <= 16 KB so Linux devio.c do_proc_bulk() never fails
        // with -ENOMEM / -EINVAL trying to kmalloc 512 KB contiguous pages.
        private const val BULK_CHUNK_SIZE  = 16 * 1024
        private const val MAX_RETRIES      = 2

        const val RC_OK                   = 0x2001
        const val RC_SESSION_ALREADY_OPEN = 0x201E
    }

    /** Still Image Class Get Device Status request (0x67). Returns null if request fails. */
    fun getDeviceStatus(): String {
        val buf = ByteArray(16)
        val n = conn.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or 0x01 /* RECIPIENT_INTERFACE */,
            0x67, 0, intf.id, buf, buf.size, 1000
        )
        if (n < 4) return "status_req_err($n)"
        val len = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
        val code = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
        return "0x%04X(len=%d,raw=%d)".format(code, len, n)
    }

    /** Still Image Class Device Reset request (0x66) to clear any stuck PTP state. */
    private fun ptpClassReset(): Int = conn.controlTransfer(
        UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01 /* RECIPIENT_INTERFACE */,
        0x66, 0, intf.id, null, 0, 1000
    )

    /** Drain any leftover bytes sitting in the camera's Bulk IN buffer (e.g. from aborted MTP). */
    private fun drainInEndpoint() {
        for (i in 0 until 4) {
            val n = conn.bulkTransfer(inEp, readBuf, readBuf.size, 150)
            if (n <= 0) break
            log("  drained $n stale bytes from IN ep")
        }
    }

    private fun writeAllBulk(data: ByteArray): Int {
        var off = 0
        while (off < data.size) {
            val chunk = minOf(BULK_CHUNK_SIZE, data.size - off)
            val w = conn.bulkTransfer(outEp, data, off, chunk, WRITE_TIMEOUT_MS)
            if (w < 0) return w
            if (w == 0) return -1
            off += w
        }
        // PTP over USB requires a Zero-Length Packet if payload ends on exact maxPacketSize boundary
        if (data.isNotEmpty() && outEp.maxPacketSize > 0 && data.size % outEp.maxPacketSize == 0) {
            conn.bulkTransfer(outEp, ByteArray(0), 0, WRITE_TIMEOUT_MS)
        }
        return off
    }

    fun transact(opcode: Int, vararg params: Int): PtpResult =
        transactInternal(opcode, null, *params)

    fun transactSendData(opcode: Int, payload: ByteArray, vararg params: Int): PtpResult =
        transactInternal(opcode, payload, *params)

    private fun transactInternal(opcode: Int, outData: ByteArray?, vararg params: Int): PtpResult {
        val txId = tid++
        var cmdPkt = le32(12 + params.size * 4) + le16(1) + le16(opcode) + le32(txId)
        for (p in params) cmdPkt += le32(p)

        val dataPkt = if (outData != null) {
            le32(12 + outData.size) + le16(2) + le16(opcode) + le32(txId) + outData
        } else null

        var lastError = ""
        for (attempt in 0..MAX_RETRIES) {
            if (attempt == 1) {
                // Recovery step 1: inspect status, drain dangling IN data, send PTP Class Reset (0x66)
                val st = getDeviceStatus()
                log("  retry 1 recovery: devStatus=$st")
                drainInEndpoint()
                val rr = ptpClassReset()
                log("  PTP class reset (0x66) → $rr")
                Thread.sleep(300)
            } else if (attempt >= 2) {
                // Recovery step 2: official kernel SET_INTERFACE (syncs host xHCI + camera data toggles)
                val si = conn.setInterface(intf)
                log("  retry $attempt recovery: setInterface → $si, devStatus=${getDeviceStatus()}")
                Thread.sleep(300)
                drainInEndpoint()
                ptpClassReset()
                Thread.sleep(200)
            }

            val t0 = System.currentTimeMillis()
            val written = writeAllBulk(cmdPkt)
            if (written < 0) {
                val dt = System.currentTimeMillis() - t0
                lastError = "write failed (attempt ${attempt + 1}, ret=$written, ${dt}ms, op=0x%04X)".format(opcode)
                log("  $lastError")
                continue
            }

            if (dataPkt != null) {
                val tData0 = System.currentTimeMillis()
                val dw = writeAllBulk(dataPkt)
                if (dw < 0) {
                    val dt = System.currentTimeMillis() - tData0
                    lastError = "send-data failed (attempt ${attempt + 1}, ret=$dw, ${dt}ms, op=0x%04X)".format(opcode)
                    log("  $lastError")
                    continue
                }
            }

            val buf = readBuf
            var data = ByteArray(0)
            var surplus = ByteArray(0)
            var failed = false

            while (true) {
                val pkt: ByteArray
                val n: Int
                if (surplus.isNotEmpty()) {
                    pkt = surplus
                    n = surplus.size
                    surplus = ByteArray(0)
                } else {
                    val tRead0 = System.currentTimeMillis()
                    val r = conn.bulkTransfer(inEp, buf, buf.size, READ_TIMEOUT_MS)
                    if (r < 0) {
                        val dt = System.currentTimeMillis() - tRead0
                        lastError = "read failed (attempt ${attempt + 1}, ret=$r, ${dt}ms, op=0x%04X)".format(opcode)
                        log("  $lastError")
                        failed = true
                        break
                    }
                    if (r == 0) {
                        // Trailing USB Zero-Length Packet from previous transfer; keep reading.
                        continue
                    }
                    pkt = buf
                    n = r
                }

                if (n < 12) {
                    lastError = "short read (got $n bytes, need >= 12, attempt ${attempt + 1}, op=0x%04X)".format(opcode)
                    log("  $lastError")
                    failed = true
                    break
                }

                val bb = ByteBuffer.wrap(pkt, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                val total = bb.getInt(0).toLong() and 0xFFFFFFFFL
                val cType = bb.getShort(4).toInt() and 0xFFFF
                val cCode = bb.getShort(6).toInt() and 0xFFFF

                if (total < 12L) {
                    lastError = "invalid PTP container len=$total (attempt ${attempt + 1})"
                    failed = true
                    break
                }

                when (cType) {
                    2 -> { // Data container
                        val expectedPayload = (total - 12L).coerceIn(64L, 8L * 1024L * 1024L).toInt()
                        val out = ByteArrayOutputStream(expectedPayload)
                        val firstAvail = minOf(n.toLong(), total).toInt()
                        if (firstAvail > 12) {
                            out.write(pkt, 12, firstAvail - 12)
                        }
                        if (n.toLong() > total) {
                            surplus = pkt.copyOfRange(total.toInt(), n)
                        }

                        var remaining = total - firstAvail
                        while (remaining > 0L) {
                            val m = conn.bulkTransfer(inEp, buf, buf.size, READ_TIMEOUT_MS)
                            if (m < 0) {
                                lastError = "data phase failed (ret=$m, remaining=$remaining, attempt ${attempt + 1})"
                                log("  $lastError")
                                failed = true
                                break
                            }
                            if (m == 0) {
                                lastError = "unexpected ZLP in data phase (remaining=$remaining, attempt ${attempt + 1})"
                                log("  $lastError")
                                failed = true
                                break
                            }
                            val toCopy = minOf(m.toLong(), remaining).toInt()
                            out.write(buf, 0, toCopy)
                            if (m.toLong() > remaining) {
                                surplus = buf.copyOfRange(toCopy, m)
                            }
                            remaining -= toCopy
                        }
                        if (failed) break
                        data = out.toByteArray()
                    }
                    3 -> { // Response container
                        val np = ((minOf(n.toLong(), total).toInt() - 12) / 4).coerceAtLeast(0)
                        return PtpResult(
                            cCode, data,
                            IntArray(np) { bb.getInt(12 + it * 4) }
                        )
                    }
                    else -> {
                        lastError = "unexpected PTP container type=$cType (code=0x%04X, attempt ${attempt + 1})".format(cCode)
                        log("  $lastError")
                        failed = true
                        break
                    }
                }
            }
            if (!failed) break
        }

        error(lastError)
    }

    fun openSession() = transact(0x1002, 1)
    fun closeSession() = transact(0x1003)
    fun chdk(sub: Int, vararg p: Int) = transact(0x9999, sub, *p)

    /** Execute a Lua script on camera via PTP_CHDK_ExecuteScript (sub=7, SL_LUA=0). */
    fun chdkExecLua(script: String, flags: Int = 0): PtpResult {
        val bytes = script.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        return transactSendData(0x9999, bytes, 7, flags and 0xFFFFFF00.toInt())
    }
}

