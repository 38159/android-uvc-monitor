package ai.waion.uvcmonitor

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal UVC driver for MJPEG cameras / HDMI capture cards using a bulk video endpoint
 * (e.g. MacroSilicon MS2109/MS2130). Pure Android USB host API, no native code.
 */
class UvcCamera(private val usb: UsbManager, private val device: UsbDevice) {

    data class FrameMode(
        val formatIndex: Int,
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        val intervals: List<Int>, // 100ns units
    ) {
        val bestInterval get() = intervals.minOrNull() ?: 333_333
        override fun toString() = "${width}x$height@${"%.0f".format(1e7 / bestInterval)}"
    }

    /** Receives one complete JPEG frame. The buffer is only valid during the call. */
    fun interface FrameSink {
        fun onFrame(buf: ByteArray, len: Int)
    }

    private var conn: UsbDeviceConnection? = null
    private var vsIface: UsbInterface? = null
    private var vsEp: UsbEndpoint? = null
    private var bcdUvc = 0x0100
    private var vsIfaceNum = 1
    private var maxPayload = 0
    private var maxFrame = 0
    @Volatile private var running = false
    private var thread: Thread? = null

    val modes = mutableListOf<FrameMode>()
    var activeMode: FrameMode? = null
        private set

    fun open(): Boolean {
        val c = usb.openDevice(device) ?: return false
        conn = c
        parseDescriptors(c.rawDescriptors)
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_VIDEO && itf.interfaceSubclass == 2) {
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                    ) {
                        vsIface = itf; vsEp = ep; vsIfaceNum = itf.id
                    }
                }
            }
        }
        if (vsIface == null) {
            Log.e(TAG, "No bulk UVC streaming interface found")
            return false
        }
        if (!c.claimInterface(vsIface, true)) {
            Log.e(TAG, "claimInterface failed")
            return false
        }
        Log.i(TAG, "UVC ${"%04x".format(bcdUvc)} iface=$vsIfaceNum ep=${vsEp!!.address} modes=$modes")
        return true
    }

    /** Pick the largest MJPEG mode not exceeding the requested size, preferring the highest fps. */
    fun selectMode(maxW: Int, maxH: Int): FrameMode? =
        modes.filter { it.width <= maxW && it.height <= maxH }
            .maxWithOrNull(compareBy<FrameMode> { it.width * it.height }.thenBy { -it.bestInterval })
            ?: modes.firstOrNull()

    fun start(mode: FrameMode, sink: FrameSink): Boolean {
        val c = conn ?: return false
        val len = when {
            bcdUvc >= 0x0150 -> 48
            bcdUvc >= 0x0110 -> 34
            else -> 26
        }
        val probe = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        probe.putShort(0, 1) // bmHint: keep dwFrameInterval
        probe.put(2, mode.formatIndex.toByte())
        probe.put(3, mode.frameIndex.toByte())
        probe.putInt(4, mode.bestInterval)

        if (!setCur(VS_PROBE, probe.array())) return false
        val cur = ByteArray(len)
        if (getCur(VS_PROBE, cur) < 26) return false
        if (!setCur(VS_COMMIT, cur)) return false

        val res = ByteBuffer.wrap(cur).order(ByteOrder.LITTLE_ENDIAN)
        maxFrame = res.getInt(18).takeIf { it > 0 } ?: (mode.width * mode.height * 2)
        maxPayload = res.getInt(22).takeIf { it > 0 } ?: 16384
        activeMode = mode
        Log.i(
            TAG, "Committed $mode fmt=${cur[2]} frm=${cur[3]} interval=${res.getInt(4)} " +
                "maxFrame=$maxFrame maxPayload=$maxPayload"
        )

        running = true
        thread = Thread({ readLoop(c, sink) }, "uvc-reader").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    private fun readLoop(c: UsbDeviceConnection, sink: FrameSink) {
        val ep = vsEp!!
        val pkt = ByteArray(maxPayload)
        val frame = ByteArray(maxFrame + maxPayload)
        var fill = 0
        var lastFid = -1
        var errors = 0
        while (running) {
            val n = c.bulkTransfer(ep, pkt, pkt.size, 1000)
            if (n < 0) {
                if (++errors > 5 && running) {
                    Log.w(TAG, "bulkTransfer failing repeatedly, stopping")
                    break
                }
                continue
            }
            errors = 0
            if (n < 2) continue
            val hlen = pkt[0].toInt() and 0xff
            if (hlen < 2 || hlen > n) continue
            val flags = pkt[1].toInt()
            val fid = flags and 0x01
            val eof = flags and 0x02 != 0
            val err = flags and 0x40 != 0

            if (lastFid != -1 && fid != lastFid && fill > 0) {
                // Frame boundary without EOF bit.
                emit(frame, fill, sink); fill = 0
            }
            lastFid = fid
            if (err) { fill = 0; continue }

            val dlen = n - hlen
            if (dlen > 0) {
                if (fill + dlen <= frame.size) {
                    System.arraycopy(pkt, hlen, frame, fill, dlen)
                    fill += dlen
                } else {
                    fill = 0 // overflow, drop
                }
            }
            if (eof && fill > 0) {
                emit(frame, fill, sink); fill = 0
            }
        }
        running = false
    }

    private fun emit(frame: ByteArray, len: Int, sink: FrameSink) {
        // Must start with SOI marker.
        if (len > 4 && frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte()) {
            sink.onFrame(frame, len)
        }
    }

    fun close() {
        running = false
        thread?.join(1500)
        thread = null
        conn?.let { c ->
            vsIface?.let { c.releaseInterface(it) }
            c.close()
        }
        conn = null
    }

    private fun setCur(selector: Int, data: ByteArray): Boolean {
        val r = conn!!.controlTransfer(0x21, SET_CUR, selector shl 8, vsIfaceNum, data, data.size, 1000)
        if (r < 0) Log.e(TAG, "SET_CUR($selector) failed: $r")
        return r >= 0
    }

    private fun getCur(selector: Int, data: ByteArray): Int {
        val r = conn!!.controlTransfer(0xA1, GET_CUR, selector shl 8, vsIfaceNum, data, data.size, 1000)
        if (r < 0) Log.e(TAG, "GET_CUR($selector) failed: $r")
        return r
    }

    private fun parseDescriptors(raw: ByteArray) {
        var i = 0
        var ifClass = -1
        var ifSub = -1
        var mjpegFormat = -1
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 0xff
            if (len < 2 || i + len > raw.size) break
            val type = raw[i + 1].toInt() and 0xff
            fun u8(o: Int) = raw[i + o].toInt() and 0xff
            fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
            fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
            when (type) {
                0x04 -> { ifClass = u8(5); ifSub = u8(6); mjpegFormat = -1 }
                0x24 -> if (ifClass == 0x0e) {
                    val sub = u8(2)
                    if (ifSub == 1 && sub == 0x01 && len >= 5) bcdUvc = u16(3)
                    if (ifSub == 2) when (sub) {
                        0x06 -> mjpegFormat = u8(3)
                        0x07 -> if (mjpegFormat > 0 && len >= 26) {
                            val nInt = u8(25)
                            val ivals = if (nInt == 0) listOf(u32(26))
                            else (0 until nInt).mapNotNull { k ->
                                if (26 + 4 * k + 4 <= len) u32(26 + 4 * k) else null
                            }
                            modes += FrameMode(mjpegFormat, u8(3), u16(5), u16(7), ivals.filter { it > 0 })
                        }
                        0x04, 0x10 -> mjpegFormat = -1
                    }
                }
            }
            i += len
        }
    }

    companion object {
        private const val TAG = "UvcCamera"
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val VS_PROBE = 0x01
        private const val VS_COMMIT = 0x02
    }
}
