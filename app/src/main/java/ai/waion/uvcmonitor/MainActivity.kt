package ai.waion.uvcmonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var usb: UsbManager
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView

    private var camera: UvcCamera? = null
    private var holder: SurfaceHolder? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private var fill = false

    // Frame hand-off: reader -> latest slot -> decode workers.
    private class Frame(size: Int) {
        var buf = ByteArray(size)
        var len = 0
        var seq = 0L
    }
    private val lock = Object()
    private var pending: Frame? = null
    private var free = ArrayBlockingQueue<Frame>(POOL)
    private var seq = 0L
    private var lastDrawn = 0L
    private var workers = listOf<Thread>()
    @Volatile private var streaming = false

    private val rxCount = AtomicInteger()
    private val drawCount = AtomicInteger()
    private var decodeNanos = 0L

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val dev = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) startCamera(dev)
                    else showStatus("USB 권한이 거부되었습니다")
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect(dev)
                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    if (camera != null) { stopCamera(); showStatus("캡처카드 분리됨") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(USB_SERVICE) as UsbManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            preferredRefreshRate = 60f
        }

        surface = SurfaceView(this).also { it.holder.addCallback(this) }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            textSize = 14f
            setPadding(24, 12, 24, 12)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface)
            addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        })
        surface.setOnLongClickListener { showMenu(); true }

        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, filter)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            connect(intent.usbDevice() ?: findDevice())
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        connect(intent.usbDevice() ?: findDevice())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.usbDevice()?.let { connect(it) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Running as the home app: Back must not leave the monitor.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    /** Long-press menu: the only way out of kiosk mode. */
    private fun showMenu() {
        val items = arrayOf(
            if (fill) "비율 유지 (레터박스)" else "화면 꽉 채우기",
            if (status.visibility == TextView.VISIBLE) "상태 표시 끄기" else "상태 표시 켜기",
            "스트림 재시작",
            "설정 열기",
            "삼성 홈 화면 열기",
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("UVC Monitor")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> fill = !fill
                    1 -> status.visibility =
                        if (status.visibility == TextView.VISIBLE) TextView.GONE else TextView.VISIBLE
                    2 -> { stopCamera(); connect(findDevice()) }
                    3 -> startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    4 -> startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .setPackage("com.sec.android.app.launcher")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .show()
    }

    override fun onDestroy() {
        stopCamera()
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun findDevice(): UsbDevice? =
        usb.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == 0x0e }
        }

    private fun connect(dev: UsbDevice?) {
        if (dev == null) { showStatus("캡처카드를 연결하세요"); return }
        if (camera != null) return
        if (usb.hasPermission(dev)) {
            startCamera(dev)
        } else {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(dev, pi)
        }
    }

    private fun startCamera(dev: UsbDevice) {
        if (camera != null) return
        val cam = UvcCamera(usb, dev)
        if (!cam.open()) { cam.close(); showStatus("캡처카드 열기 실패"); return }
        val mode = cam.selectMode(1920, 1080)
        if (mode == null) { cam.close(); showStatus("MJPEG 모드 없음"); return }

        val frameSize = mode.width * mode.height * 2
        free = ArrayBlockingQueue(POOL)
        repeat(POOL) { free.offer(Frame(frameSize)) }
        pending = null
        seq = 0; lastDrawn = 0
        streaming = true
        workers = (0 until WORKERS).map { idx ->
            Thread({ decodeLoop() }, "decoder-$idx").apply { priority = Thread.MAX_PRIORITY - 1; start() }
        }
        camera = cam
        if (!cam.start(mode) { buf, len -> onFrame(buf, len) }) {
            stopCamera(); showStatus("스트림 시작 실패"); return
        }
        runOnUiThread {
            status.text = "${dev.productName ?: "UVC"} $mode"
            status.visibility = TextView.GONE // clean monitor view; toggle from the long-press menu
            Log.i(TAG, status.text.toString())
        }
        startStats()
    }

    private fun stopCamera() {
        streaming = false
        camera?.close()
        camera = null
        synchronized(lock) { (lock as Object).notifyAll() }
        workers.forEach { it.join(500) }
        workers = emptyList()
    }

    /** Called on the USB reader thread. Keeps only the newest frame (lowest latency). */
    private fun onFrame(buf: ByteArray, len: Int) {
        rxCount.incrementAndGet()
        val f = free.poll() ?: synchronized(lock) { pending.also { pending = null } } ?: return
        if (f.buf.size < len) f.buf = ByteArray(len)
        System.arraycopy(buf, 0, f.buf, 0, len)
        f.len = len
        synchronized(lock) {
            f.seq = ++seq
            pending?.let { free.offer(it) }
            pending = f
            (lock as Object).notify()
        }
    }

    private fun decodeLoop() {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val dst = Rect()
        while (streaming) {
            val f = synchronized(lock) {
                while (streaming && pending == null) (lock as Object).wait(200)
                pending.also { pending = null }
            } ?: continue

            val t0 = SystemClock.elapsedRealtimeNanos()
            val bmp = try {
                BitmapFactory.decodeByteArray(f.buf, 0, f.len, opts)
            } catch (e: IllegalArgumentException) {
                opts.inBitmap = null
                BitmapFactory.decodeByteArray(f.buf, 0, f.len, opts)
            }
            val frameSeq = f.seq
            free.offer(f)
            if (bmp == null) continue
            opts.inBitmap = bmp
            decodeNanos += SystemClock.elapsedRealtimeNanos() - t0

            synchronized(this) {
                if (frameSeq <= lastDrawn) return@synchronized
                val h = holder ?: return@synchronized
                val canvas = try { h.lockHardwareCanvas() } catch (e: Exception) { null } ?: return@synchronized
                try {
                    canvas.drawColor(Color.BLACK)
                    layoutRect(bmp.width, bmp.height, dst)
                    canvas.drawBitmap(bmp, null, dst, paint)
                } finally {
                    h.unlockCanvasAndPost(canvas)
                }
                lastDrawn = frameSeq
                drawCount.incrementAndGet()
            }
        }
    }

    private fun layoutRect(w: Int, h: Int, out: Rect) {
        if (fill || surfaceW == 0) { out.set(0, 0, surfaceW, surfaceH); return }
        val scale = minOf(surfaceW.toFloat() / w, surfaceH.toFloat() / h)
        val dw = (w * scale).toInt()
        val dh = (h * scale).toInt()
        val x = (surfaceW - dw) / 2
        val y = (surfaceH - dh) / 2
        out.set(x, y, x + dw, y + dh)
    }

    private fun startStats() {
        val base = status.text.toString()
        surface.postDelayed(object : Runnable {
            override fun run() {
                if (camera == null) return
                val rx = rxCount.getAndSet(0)
                val dr = drawCount.getAndSet(0)
                val dec = if (dr > 0) decodeNanos / dr / 1_000_000.0 else 0.0
                decodeNanos = 0
                status.text = "$base\n수신 ${rx}fps · 표시 ${dr}fps · 디코드 ${"%.1f".format(dec)}ms"
                Log.i(TAG, "rx=$rx draw=$dr decode=${"%.1f".format(dec)}ms")
                surface.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun showStatus(msg: String) = runOnUiThread {
        status.text = msg
        status.visibility = TextView.VISIBLE
        Log.i(TAG, msg)
    }

    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
        synchronized(this) { holder = h; surfaceW = w; surfaceH = hgt }
    }
    override fun surfaceDestroyed(h: SurfaceHolder) {
        synchronized(this) { holder = null }
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val TAG = "UvcMonitor"
        private const val ACTION_PERMISSION = "ai.waion.uvcmonitor.USB_PERMISSION"
        private const val WORKERS = 2
        private const val POOL = WORKERS + 2
    }
}
