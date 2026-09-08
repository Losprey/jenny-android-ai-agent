package com.flagdizero.jenny

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.math.abs
import kotlin.math.roundToInt

class JennyOverlayController(private val context: Context) {

    companion object {
        private const val SIZE_DP = 96
        private const val MARGIN_DP = 12
        private const val START_X_DP = 12
        private const val START_Y_DP = 180
        private const val GATEWAY_HOST = "127.0.0.1"
        private const val GATEWAY_PORT = 18790
        private const val IMAGE_PATH = "/html-mobile/assets/jenny-idle.webp"
        private const val MAX_ATTEMPTS = 20
        private const val RETRY_DELAY_MS = 800L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: WebView? = null
    private var loadAttempts = 0
    private var retryRunnable: Runnable? = null

    fun startIfAllowed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return
        if (view != null) return
        loadAttempts = 0

        val size = dp(SIZE_DP)
        val web = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(START_X_DP)
            y = dp(START_Y_DP)
        }

        var downX = 0f
        var downY = 0f
        var startX = lp.x
        var startY = lp.y
        var moved = false

        web.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = lp.x; startY = lp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    clamp(lp, size)
                    try { wm.updateViewLayout(web, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                        )
                    }
                    true
                }
                else -> true
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                val url = request?.url?.toString().orEmpty()
                if ((url.isEmpty() || url.contains("jenny-idle")) && loadAttempts < MAX_ATTEMPTS) {
                    scheduleRetry(view ?: web)
                }
            }
        }

        wm.addView(web, lp)
        view = web
        load(web)
    }

    private fun load(web: WebView) {
        loadAttempts++
        val imageUrl = "http://$GATEWAY_HOST:$GATEWAY_PORT$IMAGE_PATH"
        web.loadDataWithBaseURL(
            "http://$GATEWAY_HOST:$GATEWAY_PORT/",
            """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
            <style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}img{width:100%;height:100%;object-fit:contain;display:block}</style>
            </head><body><img src="$imageUrl"></body></html>
            """.trimIndent(),
            "text/html", "UTF-8", null
        )
    }

    private fun scheduleRetry(web: WebView) {
        if (loadAttempts >= MAX_ATTEMPTS) return
        val r = Runnable { if (view === web) load(web) }
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = r
        mainHandler.postDelayed(r, RETRY_DELAY_MS)
    }

    fun stop() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        view?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            it.destroy()
        }
        view = null
    }

    private fun clamp(lp: WindowManager.LayoutParams, size: Int) {
        val dm = context.resources.displayMetrics
        lp.x = lp.x.coerceIn(dp(MARGIN_DP), dm.widthPixels - size - dp(MARGIN_DP))
        lp.y = lp.y.coerceIn(dp(MARGIN_DP), dm.heightPixels - size - dp(MARGIN_DP))
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()
}
