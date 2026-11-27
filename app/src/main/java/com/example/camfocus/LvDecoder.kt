package com.example.camfocus

import kotlin.math.abs

/**
 * Decodes CHDK Live View frames (LV_FB_YUV8 / LV_FB_YUV8B viewport + LV_FB_PAL8 bitmap overlay)
 * matching chdkptp's liveimg.c color conversion and palette formats, with optional luma focus peaking.
 */
class LvDecoder {
    private var pixels = IntArray(0)
    private var yPlane = IntArray(0)
    private val palRgba = IntArray(256)

    companion object {
        // chdkptp liveimg.c default AYUV palette when palette_type == 0 or palette_data_start == 0
        private val PALETTE_TYPE1_DEFAULT = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0xff.toByte(), 0xe0.toByte(), 0x00, 0x00,
            0xff.toByte(), 0x60, 0xee.toByte(), 0x62,
            0xff.toByte(), 0xb9.toByte(), 0x00, 0x00,
            0x7f, 0x00, 0x00, 0x00,
            0xff.toByte(), 0x7e, 0xa1.toByte(), 0xb3.toByte(),
            0xff.toByte(), 0xcc.toByte(), 0xb8.toByte(), 0x5e,
            0xff.toByte(), 0x5f, 0x00, 0x00,
            0xff.toByte(), 0x94.toByte(), 0xc5.toByte(), 0x5d,
            0xff.toByte(), 0x8a.toByte(), 0x50, 0xb0.toByte(),
            0xff.toByte(), 0x4b, 0x3d, 0xd4.toByte(),
            0x7f, 0x28, 0x00, 0x00,
            0x7f, 0x00, 0x7b, 0xe2.toByte(),
            0xff.toByte(), 0x30, 0x00, 0x00,
            0xff.toByte(), 0x69, 0x00, 0x00,
            0xff.toByte(), 0x00, 0x00, 0x00
        )

        private val ALPHA2_LOOKUP = intArrayOf(128, 171, 214, 255)
    }

    /**
     * Decodes [frame] into the reusable pixel buffer and returns [DecodedPixels].
     * Returns null if the frame contains no viewport image data.
     */
    fun decode(
        frame: LvFrame,
        showOverlay: Boolean,
        focusPeaking: Boolean,
        peakingThreshold: Int = 75,
        overridePalette: ByteArray? = null
    ): DecodedPixels? {
        val vp = frame.vp
        if (vp.dataStart <= 0 || vp.visW <= 0 || vp.visH <= 0) return null

        val w = vp.visW
        val h = vp.visH
        val totalPx = w * h
        if (pixels.size < totalPx) {
            pixels = IntArray(totalPx)
            yPlane = IntArray(totalPx)
        }

        val blob = frame.blob
        when (vp.type) {
            0 -> { // LV_FB_YUV8: UYVYYY (6 bytes per 4 pixels)
                val rowInc = (vp.bufW * 12) / 8
                val needed = vp.dataStart + (h - 1) * rowInc + ((w + 3) / 4) * 6
                if (needed > blob.size) return null

                var outRow = 0
                for (row in 0 until h) {
                    var p = vp.dataStart + row * rowInc
                    var x = 0
                    while (x + 3 < w) {
                        val u = blob[p].toInt()     // signed -128..127
                        val v = blob[p + 2].toInt() // signed -128..127
                        val y0 = blob[p + 1].toInt() and 0xFF
                        val y1 = blob[p + 3].toInt() and 0xFF
                        val y2 = blob[p + 4].toInt() and 0xFF
                        val y3 = blob[p + 5].toInt() and 0xFF

                        val i0 = outRow + x
                        pixels[i0]     = yuvToRgb(y0, u, v)
                        pixels[i0 + 1] = yuvToRgb(y1, u, v)
                        pixels[i0 + 2] = yuvToRgb(y2, u, v)
                        pixels[i0 + 3] = yuvToRgb(y3, u, v)

                        if (focusPeaking) {
                            yPlane[i0]     = y0
                            yPlane[i0 + 1] = y1
                            yPlane[i0 + 2] = y2
                            yPlane[i0 + 3] = y3
                        }
                        p += 6
                        x += 4
                    }
                    outRow += w
                }
            }
            2, 3 -> { // LV_FB_YUV8B / YUV8C: UYVY (4 bytes per 2 pixels)
                val rowInc = vp.bufW * 2
                val needed = vp.dataStart + (h - 1) * rowInc + ((w + 1) / 2) * 4
                if (needed > blob.size) return null

                var outRow = 0
                for (row in 0 until h) {
                    var p = vp.dataStart + row * rowInc
                    var x = 0
                    while (x + 1 < w) {
                        val u = blob[p].toInt()
                        val y0 = blob[p + 1].toInt() and 0xFF
                        val v = blob[p + 2].toInt()
                        val y1 = blob[p + 3].toInt() and 0xFF

                        val i0 = outRow + x
                        pixels[i0]     = yuvToRgb(y0, u, v)
                        pixels[i0 + 1] = yuvToRgb(y1, u, v)
                        if (focusPeaking) {
                            yPlane[i0]     = y0
                            yPlane[i0 + 1] = y1
                        }
                        p += 4
                        x += 2
                    }
                    outRow += w
                }
            }
            else -> return null
        }

        if (focusPeaking && w > 4 && h > 2) {
            applyFocusPeaking(w, h, peakingThreshold)
        }

        if (showOverlay) {
            blendBitmapOverlay(frame, w, h, overridePalette)
        }

        val aspectRatio = when (frame.lcdAspectRatio) {
            1 -> 16f / 9f
            2 -> 3f / 2f
            else -> 4f / 3f
        }
        return DecodedPixels(pixels, w, h, aspectRatio)
    }

    private fun applyFocusPeaking(w: Int, h: Int, threshold: Int) {
        val dx = if (w >= h * 2) 2 else 1 // account for 2:1 horizontal pixel aspect ratio on 720x240
        val peakColor = 0xFFFF2A2A.toInt()
        for (y in 1 until h - 1) {
            val rowOff = y * w
            for (x in dx until w - dx) {
                val idx = rowOff + x
                val gx = abs(yPlane[idx + dx] - yPlane[idx - dx])
                val gy = abs(yPlane[idx + w] - yPlane[idx - w])
                if (gx + gy > threshold) {
                    pixels[idx] = peakColor
                }
            }
        }
    }

    private fun blendBitmapOverlay(frame: LvFrame, dstW: Int, dstH: Int, overridePalette: ByteArray?) {
        val bm = frame.bm
        if (bm.type != 1 || bm.dataStart <= 0 || bm.visW <= 0 || bm.visH <= 0) return
        val blob = frame.blob
        val needed = bm.dataStart + (bm.visH - 1) * bm.bufW + bm.visW
        if (needed > blob.size) return

        buildPalette(frame, overridePalette)

        val bmW = bm.visW
        val bmH = bm.visH
        val bufW = bm.bufW
        for (y in 0 until dstH) {
            val sy = (y * bmH) / dstH
            val srcRow = bm.dataStart + sy * bufW
            val dstRow = y * dstW
            for (x in 0 until dstW) {
                val sx = (x * bmW) / dstW
                val idx = blob[srcRow + sx].toInt() and 0xFF
                val argb = palRgba[idx]
                val a = (argb ushr 24) and 0xFF
                if (a == 0) continue
                if (a == 255) {
                    pixels[dstRow + x] = argb
                } else {
                    val dst = pixels[dstRow + x]
                    val invA = 255 - a
                    val r = (((argb ushr 16) and 0xFF) * a + ((dst ushr 16) and 0xFF) * invA) / 255
                    val g = (((argb ushr 8) and 0xFF) * a + ((dst ushr 8) and 0xFF) * invA) / 255
                    val b = ((argb and 0xFF) * a + (dst and 0xFF) * invA) / 255
                    pixels[dstRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    private fun buildPalette(frame: LvFrame, overridePalette: ByteArray?) {
        val blob = frame.blob
        val pType = frame.paletteType
        val pStart = frame.paletteStart
        val neededEntries = when (pType) {
            1, 2, 4 -> 16
            3, 5 -> 256
            else -> 0
        }

        val palBytes: ByteArray
        val base: Int
        val effType: Int
        if (neededEntries > 0 && pStart > 0 && pStart + neededEntries * 4 <= blob.size) {
            palBytes = blob
            base = pStart
            effType = pType
        } else if (overridePalette != null && overridePalette.size >= 1024) {
            palBytes = overridePalette
            base = 0
            effType = 3
        } else if (frame.bm.visW >= 720) {
            fillSx130FallbackPalette()
            return
        } else {
            palBytes = PALETTE_TYPE1_DEFAULT
            base = 0
            effType = 1
        }

        for (pixel in 0 until 256) {
            palRgba[pixel] = when (effType) {
                1 -> palType1(palBytes, base, pixel)
                2 -> palType2(palBytes, base, pixel)
                3 -> palAyuv(palBytes, base, pixel, 0)
                4 -> palType4(palBytes, base, pixel)
                5 -> palAyuv(palBytes, base, pixel, 4)
                else -> 0
            }
        }
    }

    /** Clean fallback ARGB lookup for 8-bit 720px DIGIC 4 bitmaps if RAM palette is unavailable. */
    private fun fillSx130FallbackPalette() {
        palRgba.fill(0xB0D8D8D8.toInt())
        palRgba[0x00] = 0x00000000
        palRgba[0x01] = 0xFFFFFFFF.toInt()
        palRgba[0x10] = 0xFFD0D8E0.toInt()
        palRgba[0x14] = 0xFF002080.toInt()
        palRgba[0x19] = 0xFF404040.toInt()
        palRgba[0x1A] = 0xFF808080.toInt()
        palRgba[0x1E] = 0xFFE000E0.toInt()
        palRgba[0x45] = 0xFFFFB000.toInt()
        palRgba[0x60] = 0xFFFFFF00.toInt()
        palRgba[0x61] = 0xFF0070FF.toInt()
        palRgba[0x66] = 0xFFFF2020.toInt()
        palRgba[0x69] = 0xFF00E0E0.toInt()
        palRgba[0x6A] = 0xFFFFFF00.toInt()
        palRgba[0x6B] = 0xFF00E040.toInt()
        palRgba[0x6C] = 0xFF2080FF.toInt()
        palRgba[0xA0] = 0xFFFF2020.toInt()
        palRgba[0xA1] = 0xFF00E040.toInt()
        palRgba[0xA2] = 0xFF0070FF.toInt()
        palRgba[0xFF] = 0xFF000000.toInt()
    }

    // AYUV: [a, y, u, v]
    private fun palType1(pal: ByteArray, base: Int, px: Int): Int {
        val i1 = px and 0xF
        val i2 = (px ushr 4) and 0xF
        val o1 = base + i1 * 4
        val o2 = base + i2 * 4
        val a = ((pal[o1].toInt() and 0xFF) + (pal[o2].toInt() and 0xFF)) ushr 1
        val y = ((pal[o1 + 1].toInt() and 0xFF) + (pal[o2 + 1].toInt() and 0xFF)).coerceAtMost(255)
        val u = if (i1 == 0) 0 else (pal[o1 + 2].toInt() + pal[o2 + 2].toInt()).coerceIn(-128, 127)
        val v = if (i1 == 0) 0 else (pal[o1 + 3].toInt() + pal[o2 + 3].toInt()).coerceIn(-128, 127)
        val rgb = yuvToRgb(y, u, v)
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }

    private fun palType2(pal: ByteArray, base: Int, px: Int): Int {
        val i1 = px and 0xF
        val i2 = (px ushr 4) and 0xF
        val o1 = base + i1 * 4
        val o2 = base + i2 * 4
        val aIdx = (((pal[o1].toInt() and 0xFF) + (pal[o2].toInt() and 0xFF)) ushr 1) and 3
        val a = ALPHA2_LOOKUP[aIdx]
        val y = ((pal[o1 + 1].toInt() and 0xFF) + (pal[o2 + 1].toInt() and 0xFF)).coerceAtMost(255)
        val u = (pal[o1 + 2].toInt() + pal[o2 + 2].toInt()).coerceIn(-128, 127)
        val v = (pal[o1 + 3].toInt() + pal[o2 + 3].toInt()).coerceIn(-128, 127)
        val rgb = yuvToRgb(y, u, v)
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }

    // VUYA: [v, u, y, a]
    private fun palAyuv(pal: ByteArray, base: Int, px: Int, shift: Int): Int {
        if (px == 0) return 0
        val o = base + px * 4
        val v = pal[o].toInt()
        val u = pal[o + 1].toInt()
        val y = pal[o + 2].toInt() and 0xFF
        val a = ALPHA2_LOOKUP[((pal[o + 3].toInt() and 0xFF) ushr shift) and 3]
        val rgb = yuvToRgb(y, u, v)
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }

    private fun palType4(pal: ByteArray, base: Int, px: Int): Int {
        if (px == 0) return 0
        val i1 = px and 0xF
        val i2 = (px ushr 4) and 0xF
        val o1 = base + i1 * 4
        val o2 = base + i2 * 4
        val aIdx = (((pal[o1 + 3].toInt() and 0xFF) + (pal[o2 + 3].toInt() and 0xFF)) ushr 1) and 3
        val a = ALPHA2_LOOKUP[aIdx]
        val v = (pal[o1].toInt() + pal[o2].toInt()).coerceIn(-128, 127)
        val u = (pal[o1 + 1].toInt() + pal[o2 + 1].toInt()).coerceIn(-128, 127)
        val y = ((pal[o1 + 2].toInt() and 0xFF) + (pal[o2 + 2].toInt() and 0xFF)).coerceAtMost(255)
        val rgb = yuvToRgb(y, u, v)
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }

    private fun yuvToRgb(y: Int, u: Int, v: Int): Int {
        val y12 = y shl 12
        var r = (y12 + v * 5743 + 2048) shr 12
        var g = (y12 - u * 1411 - v * 2925 + 2048) shr 12
        var b = (y12 + u * 7258 + 2048) shr 12
        if (r < 0) r = 0 else if (r > 255) r = 255
        if (g < 0) g = 0 else if (g > 255) g = 255
        if (b < 0) b = 0 else if (b > 255) b = 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

class DecodedPixels(val pixels: IntArray, val width: Int, val height: Int, val aspectRatio: Float)
