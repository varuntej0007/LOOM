package com.loom.studio.guard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * Small draggable LOOM pill drawn over every app (games, Camera…) while Guard is on:
 * current heat and which cooling levers are active. Needs "Display over other apps".
 * Plain Android View (no Compose) so it works from the service with no activity.
 */
class GuardOverlay(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    val allowed: Boolean get() = Settings.canDrawOverlays(ctx)

    fun show() = main.post {
        if (view != null || !allowed) return@post
        try {
            val tv = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                val pad = dp(10)
                setPadding(pad, dp(5), pad, dp(5))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xCC1F2937.toInt()) }
                text = "LOOM"
            }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(80) }
            drag(tv, p)
            wm.addView(tv, p)
            view = tv
            params = p
        } catch (_: Throwable) { }
    }

    fun update(text: String, heat: Float, limit: Float) = main.post {
        val v = view ?: return@post
        v.text = text
        val color = when {
            heat.isNaN() -> 0xCC1F2937.toInt()
            heat >= limit -> 0xE6DC2626.toInt()
            heat >= limit - 0.08f -> 0xE6D97706.toInt()
            else -> 0xCC047857.toInt()
        }
        (v.background as? GradientDrawable)?.setColor(color)
    }

    fun hide() = main.post {
        view?.let { try { wm.removeView(it) } catch (_: Throwable) { } }
        view = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun drag(v: TextView, p: WindowManager.LayoutParams) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - tx).toInt(); p.y = sy + (e.rawY - ty).toInt()
                    try { wm.updateViewLayout(v, p) } catch (_: Throwable) { }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
