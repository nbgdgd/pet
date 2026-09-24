package com.timeoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Держит оверлей и раз в секунду обновляет время.
 *
 * Два источника времени: [SystemClock.elapsedRealtime] для счёта (монотонные часы не сдвинутся
 * при смене времени в системе) и [System.currentTimeMillis] для запросов UsageStats — того требует API.
 */
class OverlayService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var windowManager: WindowManager
    private lateinit var tracker: ForegroundAppTracker
    private lateinit var timer: SessionTimer

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: OverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attached = false
    private var screenOn = true
    private var launcherPackages: Set<String> = emptySet()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tracker = ForegroundAppTracker(this)
        timer = SessionTimer(prefs.graceMillis)
        launcherPackages = resolveLauncherPackages()
        prefs.registerListener(this)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        if (!PermissionHelper.hasRequired(this)) {
            notifyPermissionLost()
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        prefs.enabled = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        prefs.unregisterListener(this)
        runCatching { unregisterReceiver(screenReceiver) }
        detachOverlay()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_ENABLED -> if (!prefs.enabled) stopSelf()
            Prefs.KEY_GRACE -> timer.graceMillis = prefs.graceMillis
            Prefs.KEY_POS_X, Prefs.KEY_POS_Y -> Unit // позицию пишет сам оверлей
            else -> applyStyle()
        }
    }

    // --- цикл ---

    private fun update() {
        if (!PermissionHelper.hasRequired(this)) {
            notifyPermissionLost()
            prefs.enabled = false
            stopSelf()
            return
        }

        val foreground = if (screenOn) tracker.currentPackage(System.currentTimeMillis()) else null
        val hidden = foreground == null ||
            foreground == packageName ||
            (prefs.hideOnLauncher && foreground in launcherPackages)

        val now = SystemClock.elapsedRealtime()
        timer.onForeground(if (hidden) null else foreground, now)

        if (hidden) {
            detachOverlay()
        } else {
            attachOverlay()
            overlayView?.setTime(TimeFormat.format(timer.elapsed(now)))
        }
    }

    // --- оверлей ---

    private fun attachOverlay() {
        if (attached) return
        val view = overlayView ?: createOverlayView().also { overlayView = it }
        val params = layoutParams ?: createLayoutParams().also { layoutParams = it }
        params.x = prefs.posX
        params.y = prefs.posY
        try {
            windowManager.addView(view, params)
            attached = true
            applyStyle()
        } catch (e: Exception) {
            // Разрешение отозвали между проверкой и добавлением вью — не роняем процесс.
            notifyPermissionLost()
            prefs.enabled = false
            stopSelf()
        }
    }

    private fun detachOverlay() {
        val view = overlayView ?: return
        if (!attached) return
        runCatching { windowManager.removeView(view) }
        attached = false
    }

    private fun createOverlayView(): OverlayView {
        val view = OverlayView(this)
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val metrics = resources.displayMetrics
                    params.x = (startX + (event.rawX - touchX)).roundToInt()
                        .coerceIn(0, (metrics.widthPixels - view.width).coerceAtLeast(0))
                    params.y = (startY + (event.rawY - touchY)).roundToInt()
                        .coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.posX = params.x
                    prefs.posY = params.y
                    true
                }

                else -> false
            }
        }
        return view
    }

    private fun createLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.posX
        y = prefs.posY
    }

    private fun applyStyle() {
        overlayView?.setStyle(
            textSizeSp = prefs.textSizeSp,
            cornerPercent = prefs.cornerPercent,
            bgColor = prefs.bgColor,
            textColor = prefs.textColor,
            opacityPercent = prefs.opacityPercent
        )
    }

    // --- уведомления ---

    private fun startForegroundNotification() {
        createChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun notifyPermissionLost() {
        createChannel()
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(getString(R.string.notif_permission_title))
            .setContentText(getString(R.string.notif_permission_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (PermissionHelper.hasNotifications(this)) {
            manager.notify(NOTIF_PERMISSION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun resolveLauncherPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager
            .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    companion object {
        const val ACTION_STOP = "com.timeoverlay.STOP"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 1
        private const val NOTIF_PERMISSION_ID = 2
        private const val TICK_MILLIS = 1_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
