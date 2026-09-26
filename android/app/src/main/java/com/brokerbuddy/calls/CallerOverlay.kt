package com.brokerbuddy.calls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.brokerbuddy.R
import com.brokerbuddy.core.caller.CallerCard

/**
 * Truecaller-style floating card drawn over the incoming-call screen. Only used when
 * the user has granted "Display over other apps" (SYSTEM_ALERT_WINDOW); otherwise the
 * notification alone is shown. The card can be dragged up/down, closes itself after a
 * minute, and never takes keyboard focus away from the dialer.
 */
object CallerOverlay {
    private const val TAG = "CallerOverlay"
    private const val AUTO_HIDE_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private val autoHide = Runnable { hide() }

    fun show(context: Context, card: CallerCard) = main.post { showOnMain(context.applicationContext, card) }

    fun hide() = main.post {
        val v = view ?: return@post
        runCatching { windowManager?.removeViewImmediate(v) }
        view = null
        windowManager = null
        main.removeCallbacks(autoHide)
    }

    private fun showOnMain(app: Context, card: CallerCard) {
        if (!Settings.canDrawOverlays(app)) return
        val existing = view
        val ctx = if (existing != null) existing.context else overlayContext(app)
        val wm = windowManager ?: ctx.getSystemService(WindowManager::class.java)
        val v = existing ?: LayoutInflater.from(ContextThemeWrapper(ctx, R.style.Theme_BrokerBuddy))
            .inflate(R.layout.caller_overlay, null)
        bind(v, app, card)
        if (existing == null) {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP
                y = (app.resources.displayMetrics.heightPixels * 0.12).toInt()
            }
            try {
                wm.addView(v, lp)
            } catch (e: RuntimeException) {
                // BadTokenException / SecurityException on devices that restrict overlays;
                // the heads-up notification is still shown.
                Log.w(TAG, "Overlay not allowed: ${e.message}")
                return
            }
            view = v
            windowManager = wm
            params = lp
            enableDrag(v, wm)
        }
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    /** Android 11+ expects overlays to be added from a window context, not the application context. */
    private fun overlayContext(app: Context): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            app
        }

    private fun bind(v: View, app: Context, card: CallerCard) {
        v.findViewById<TextView>(R.id.caller_title).text = card.title
        v.findViewById<TextView>(R.id.caller_subtitle).text = card.subtitle
        v.findViewById<TextView>(R.id.caller_lines).text = card.lines.joinToString("\n") { "• $it" }

        fun button(id: Int, label: String?, route: String?) {
            val b = v.findViewById<Button>(id)
            if (label == null || route == null) {
                b.visibility = View.GONE
                return
            }
            b.visibility = View.VISIBLE
            b.text = label
            b.setOnClickListener {
                hide()
                // Allowed: user tapped a visible overlay window of an app holding SYSTEM_ALERT_WINDOW.
                runCatching { app.startActivity(openRouteIntent(app, route)) }
            }
        }
        if (card.kind == CallerCard.Kind.UNKNOWN) {
            button(R.id.caller_action_1, "Create client", CallerRoutes.newClient(card.number))
            button(R.id.caller_action_2, null, null)
            button(R.id.caller_action_3, null, null)
        } else {
            button(R.id.caller_action_1, "Profile", card.clientId?.let(CallerRoutes::client))
            button(R.id.caller_action_2, if (card.singleInquiryId != null) "Matches" else "Requirements", CallerRoutes.matches(card))
            button(R.id.caller_action_3, "Add note", CallerRoutes.caller(card.number) + "&note=1")
        }
        v.findViewById<View>(R.id.caller_close).setOnClickListener { hide() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(v: View, wm: WindowManager) {
        var startY = 0f
        var startParamY = 0
        v.findViewById<View>(R.id.caller_drag_handle).setOnTouchListener { _, e ->
            val lp = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startParamY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.y = (startParamY + (e.rawY - startY)).toInt().coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(v, lp) }
                    true
                }
                else -> false
            }
        }
    }
}
