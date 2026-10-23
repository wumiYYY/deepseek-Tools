package com.jingyuniang.ledger.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.jingyuniang.ledger.MainActivity
import com.jingyuniang.ledger.R
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.WhaleApp
import com.jingyuniang.ledger.capture.CaptureNotifier
import kotlin.math.abs

/**
 * 快捷记账悬浮球：常驻一个小鲸鱼，点一下直接记一笔。
 * 需要「显示在其他应用上层」权限。
 */
class FloatingBallService : android.app.Service() {

    private var wm: WindowManager? = null
    private var ball: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CaptureNotifier.createServiceChannelIfNeeded(this)
        runCatching { startFg() }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showBall()
    }

    private fun startFg() {
        val n: Notification = NotificationCompat.Builder(this, WhaleApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentTitle("鲸鱼娘悬浮球运行中")
            .setContentText("点一下小鲸鱼即可快速记一笔")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    private fun showBall() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val size = (56 * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            text = "🐳"
            textSize = 26f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke((2 * resources.displayMetrics.density).toInt(), 0xFF4D6BFE.toInt())
                setSize(size, size)
            }
            elevation = 8f
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - 24
            y = resources.displayMetrics.heightPixels / 2
        }
        ball = view
        params = lp

        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        view.setOnTouchListener { v: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    v.alpha = 0.7f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { manager.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    if (!moved) openAdd()
                    true
                }
                else -> false
            }
        }
        runCatching { manager.addView(view, lp) }
    }

    private fun openAdd() {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "com.jingyuniang.ledger.QUICK_ADD"
        }
        startActivity(i)
    }

    override fun onDestroy() {
        super.onDestroy()
        ball?.let { v -> runCatching { wm?.removeView(v) } }
        ball = null
    }

    companion object {
        private const val NOTI_ID = 7101
    }
}
