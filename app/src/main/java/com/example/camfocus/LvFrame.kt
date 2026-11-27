package com.example.camfocus

import java.nio.ByteBuffer
import java.nio.ByteOrder

class FbDesc(val type: Int, val dataStart: Int, val bufW: Int, val visW: Int, val visH: Int)

/** Parses CHDK live view header per CHDK core/live_view.h (lv_data_header). */
class LvFrame(val blob: ByteArray) {
    private val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
    val versionMajor = bb.getInt(0)
    val versionMinor = bb.getInt(4)
    val lcdAspectRatio = bb.getInt(8)
    val paletteType = bb.getInt(12)
    val paletteStart = bb.getInt(16)
    val vp = desc(bb.getInt(20))
    val bm = desc(bb.getInt(24))

    private fun desc(off: Int): FbDesc {
        if (off <= 0 || off + 20 > blob.size) return FbDesc(0, 0, 0, 0, 0)
        return FbDesc(
            bb.getInt(off), bb.getInt(off + 4), bb.getInt(off + 8),
            bb.getInt(off + 12), bb.getInt(off + 16)
        )
    }
}

