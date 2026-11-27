package com.example.camfocus

import java.nio.ByteBuffer
import java.nio.ByteOrder

class FbDesc(val type: Int, val dataStart: Int, val bufW: Int, val visW: Int, val visH: Int)

/** Parses CHDK live view header. Verify field layout against CHDK's live_view.h. */
class LvFrame(val blob: ByteArray) {
    private val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
    val paletteType = bb.getInt(12)
    val paletteStart = bb.getInt(16)
    val vp = desc(bb.getInt(20))
    val bm = desc(bb.getInt(24))

    private fun desc(off: Int) = FbDesc(
        bb.getInt(off), bb.getInt(off + 4), bb.getInt(off + 8),
        bb.getInt(off + 12), bb.getInt(off + 16)
    )
}
