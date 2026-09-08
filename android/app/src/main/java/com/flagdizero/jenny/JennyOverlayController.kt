package com.flagdizero.jenny

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Mascotte "desktop pet" che vive sopra le altre app (TYPE_APPLICATION_OVERLAY).
 *
 * Il documento è la stessa Jenny usata dalla chat: il controller sposta la
 * FINESTRA (WindowManager) e comanda solo la posa via evaluateJavascript, in
 * modo che il WebView sia un semplice palcoscenico quadrato trasparente.
 *
 * Fisica (stile "Volo Pegman" della SPA, ma con il pavimento = bordo basso del
 * display invece del dock):
 *  - drag: la finestra segue il dito (posa `hang`, nessun long-press/menu);
 *  - rilascio con velocità: volo con gravità, attrito sull'asse x, rimbalzi su
 *    pareti/soffitto e 1-3 piccoli rimbalzi sul pavimento prima di fermarsi;
 *  - rilascio "debole": caduta verticale e appoggio sul pavimento nel punto di
 *    rilascio;
 *  - appoggio: posa `ground` per un istante, poi `idle` col bob della chat;
 *  - tap corto (senza spostamento): apre MainActivity;
 *  - durante il drag appare in alto al centro un bersaglio "✕": rilasciando la
 *    mascotte sopra si NASCONDE l'overlay (scelta persistente).
 */
class JennyOverlayController(private val context: Context) {

    companion object {
        private const val TAG = "JennyOverlayController"

        private const val DEFAULT_SIZE_DP = 120
        private val SIZE_DP_BY_PREF = mapOf("sm" to 120, "md" to 160, "lg" to 210)
        private const val EDGE_MARGIN_DP = 6
        private const val FLOOR_GAP_DP = 12

        private const val HIDE_TARGET_SIZE_DP = 56
        private const val HIDE_TARGET_TOP_DP = 40
        private const val HIDE_HIT_EXTRA_DP = 18

        private const val TAP_SLOP_DP = 8
        private const val HOLD_COMMIT_MS = 280L
        private const val TAP_TIMEOUT_MS = 340L
        private const val SIT_GROUND_MS = 650L

        private const val GRAVITY_PX_S2 = 2500f
        private const val MAX_FALL_SPEED_PX_S = 3600f
        private const val FLOOR_RESTITUTION = 0.36f
        private const val WALL_RESTITUTION = 0.45f
        private const val CEILING_RESTITUTION = 0.25f
        private const val AIR_DRAG_PER_S = 0.8f
        private const val MIN_BOUNCE_PX_S = 150f
        private const val FLING_MIN_PX_S = 240f
        private const val MAX_BOUNCES = 3

        private const val PREFS_NAME = "overlay"
        private const val PREFS_HIDDEN = "hidden"

        private const val GATEWAY_HOST = "127.0.0.1"
        private const val GATEWAY_PORT = 18790
        private const val OVERLAY_PAGE_PATH = "/html-mobile/assets/jenny-overlay.html"
        private const val MAX_PAGE_ATTEMPTS = 15
        private const val PAGE_RETRY_MS = 900L
        private const val ART_POLL_MS = 120L
        private const val ART_POLL_MAX = 40
    }

    private enum class Phase { NONE, IDLE, DRAG, FLY }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var petView: WebView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var targetView: View? = null

    private var phase = Phase.NONE
    private var facingLeft = false

    // Geometria (px reali del display).
    private var screenW = 0
    private var screenH = 0
    private var floorLineY = 0          // y del display su cui poggiano i piedi
    private var sizePx = 0

    // Frazioni del riquadro disegnato rispetto alla finestra quadrata.
    private var artTopFrac = 0f         // 0 finché non misurato
    private var artFeetFrac = 1f        // 1 = finestra piena finché non misurato

    // Stato del volo.
    private var posX = 0f
    private var posY = 0f
    private var velX = 0f
    private var velY = 0f
    private var bounceCount = 0
    private var hasTouchedFloor = false

    private var pageAttempts = 0
    private var artPollCount = 0
    private var artPollRunnable: Runnable? = null
    private var settleRunnable: Runnable? = null
    private var lastSentPose: Pair<String, Boolean>? = null
    private val frameCallback = Choreographer.FrameCallback { frameTime ->
        if (phase == Phase.FLY) stepPhysics(frameTime)
    }
    private var lastFrameNs = 0L

    // ---------------------------------------------------------------- API

    /** Avvia l'overlay se permesso e se l'utente non l'ha nascosto. */
    fun startIfAllowed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.i(TAG, "startIfAllowed: overlay permission not granted")
            return
        }
        if (isHiddenPref()) {
            Log.i(TAG, "startIfAllowed: mascot hidden by the user, not starting")
            return
        }
        if (petView != null) return
        readDisplay()
        showPet()
    }

    /** Nasconde la mascotte e ricorda la scelta (per riaccenderla serve il
     *  toggle nella SPA / ACTION_SHOW_OVERLAY). */
    fun hide() {
        setHiddenPref(true)
        // Posticipato: hide() può arrivare dal touch handler in corso, e
        // distruggere il WebView durante la dispatch di un evento è pericoloso.
        mainHandler.post { teardown() }
    }

    /** Ferma l'overlay alla morte del service, senza toccare la preferenza. */
    fun stop() {
        teardown()
    }

    // ----------------------------------------------------------- preferenze

    private fun overlayPrefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isHiddenPref() = overlayPrefs().getBoolean(PREFS_HIDDEN, false)

    private fun setHiddenPref(hidden: Boolean) {
        overlayPrefs().edit().putBoolean(PREFS_HIDDEN, hidden).apply()
    }

    // -------------------------------------------------------------- display

    private fun readDisplay() {
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
        screenW = w
        screenH = h
        val navBarPx = systemNavBarHeightPx()
        // Il pavimento sta sopra barra di navigazione/gesture, con un margine.
        val bottomGap = if (navBarPx > 0) navBarPx + dp(FLOOR_GAP_DP) else dp(FLOOR_GAP_DP + 16)
        floorLineY = h - bottomGap
    }

    private fun systemNavBarHeightPx(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) {
            try {
                context.resources.getDimensionPixelSize(id)
            } catch (_: Exception) {
                0
            }
        } else 0
    }

    // ------------------------------------------------------------- teardown

    private fun teardown() {
        phase = Phase.NONE
        lastFrameNs = 0L
        mainHandler.removeCallbacksAndMessages(null)
        artPollRunnable = null
        settleRunnable = null
        removeTarget()
        petView?.let { v ->
            runCatching { wm.removeView(v) }
            v.destroy()
        }
        petView = null
        petParams = null
        pageAttempts = 0
        artPollCount = 0
    }

    private fun removeTarget() {
        targetView?.let { runCatching { wm.removeView(it) } }
        targetView = null
    }

    // ----------------------------------------------------------------- show

    private fun overlayUrl() = "http://$GATEWAY_HOST:$GATEWAY_PORT$OVERLAY_PAGE_PATH"

    @SuppressLint("SetJavaScriptEnabled")
    private fun showPet() {
        phase = Phase.IDLE
        sizePx = dp(DEFAULT_SIZE_DP)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val web = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isLongClickable = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = false
            settings.mediaPlaybackRequiresUserGesture = false
            setOnLongClickListener { true }
            setOnTouchListener { _, event -> onPetTouch(event) }
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    // Il gateway può essere ancora in salita al primo avvio:
                    // si ricarica la pagina finché non risponde.
                    val url = request?.url?.toString().orEmpty()
                    if ((url.isEmpty() || url.contains(OVERLAY_PAGE_PATH)) &&
                        pageAttempts < MAX_PAGE_ATTEMPTS
                    ) {
                        schedulePageRetry()
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageAttempts = 0
                    pollArtAndPrefs()
                }
            }
        }

        // Appoggio iniziale: in basso al centro, piedi sul pavimento.
        posX = ((screenW - sizePx) / 2).toFloat()
        posY = floorTopY()
        lp.x = posX.roundToInt()
        lp.y = posY.roundToInt()

        wm.addView(web, lp)
        petView = web
        petParams = lp
        pageAttempts = 0
        applyWindow()
        loadPage()
    }

    private fun loadPage() {
        val view = petView ?: return
        pageAttempts++
        view.loadUrl(overlayUrl())
    }

    private fun schedulePageRetry() {
        if (pageAttempts >= MAX_PAGE_ATTEMPTS) return
        val r = Runnable {
            if (petView != null) loadPage()
        }
        mainHandler.postDelayed(r, PAGE_RETRY_MS)
    }

    private fun floorTopY(): Float = floorLineY - artFeetFrac * sizePx

    // ---------------------------------------------------- JS <-> Kotlin

    private fun evalJs(js: String) {
        val v = petView ?: return
        runCatching { v.evaluateJavascript(js, null) }
    }

    private fun applyPose(name: String) {
        val key = name to facingLeft
        if (lastSentPose == key) return
        lastSentPose = key
        evalJs(
            "window.__jennySetPose && " +
                "window.__jennySetPose('$name', ${if (facingLeft) "true" else "false"});"
        )
    }

    /** Legge posizione dei piedi (artFeetFrac), testa (artTopFrac) e la
     *  preferenza di dimensione scelta nella SPA (stessa localStorage). */
    private fun pollArtAndPrefs() {
        if (petView == null) return
        val js = (
            "(function(){var t=(window.__jennyArtTop?window.__jennyArtTop():-1);" +
                "var b=(window.__jennyArtBottom?window.__jennyArtBottom():-1);" +
                "var s='sm';try{s=localStorage.getItem('jenny-mascotte-size')||'sm';}catch(e){}" +
                "return {t:t,b:b,s:s};})()"
            )
        petView?.evaluateJavascript(js) { raw ->
            runCatching {
                if (raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
                val obj = JSONObject(raw)
                val b = obj.optDouble("b", -1.0)
                val t = obj.optDouble("t", -1.0)
                val sizePref = obj.optString("s", "sm")
                val newSize = dp(SIZE_DP_BY_PREF[sizePref] ?: DEFAULT_SIZE_DP)
                if (newSize != sizePx) resizeTo(newSize)
                if (b >= 0.0 && t >= 0.0 && artFeetFrac != b.toFloat()) {
                    artFeetFrac = b.toFloat().coerceIn(0.05f, 1f)
                    artTopFrac = t.toFloat().coerceIn(0f, 0.95f)
                    if (phase == Phase.IDLE) {
                        posY = floorTopY()
                        applyWindow()
                    }
                }
            }
            if (artFeetFrac >= 1f && artPollCount < ART_POLL_MAX) {
                artPollCount++
                val pollRunnable = Runnable { pollArtAndPrefs() }
                artPollRunnable = pollRunnable
                mainHandler.postDelayed(pollRunnable, ART_POLL_MS)
            } else {
                artPollCount = 0
            }
        }
    }

    private fun resizeTo(newSizePx: Int) {
        val lp = petParams ?: return
        val old = sizePx
        if (newSizePx == old) return
        sizePx = newSizePx
        val ratio = newSizePx.toFloat() / old
        lp.width = newSizePx
        lp.height = newSizePx
        if (phase == Phase.IDLE) {
            posX = (posX * ratio).coerceIn(0f, (screenW - sizePx).toFloat())
            posY = floorTopY()
        } else {
            posX *= ratio
            posY *= ratio
        }
        applyWindow()
    }

    // ---------------------------------------------------------------- touch

    private var downRawX = 0f
    private var downRawY = 0f
    private var downLpX = 0
    private var downLpY = 0
    private var grabDx = 0f
    private var grabDy = 0f
    private var dragCommitted = false
    private var downTimeMs = 0L
    private var lastMoveRawX = 0f
    private var lastMoveRawY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var commitRunnable: Runnable? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun onPetTouch(event: MotionEvent): Boolean {
        if (phase == Phase.FLY) return true // in volo non si afferra: si aspetta
        if (petView == null) return true
        val lp = petParams ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downLpX = lp.x
                downLpY = lp.y
                grabDx = 0f
                grabDy = 0f
                dragCommitted = false
                downTimeMs = System.currentTimeMillis()
                lastMoveRawX = downRawX
                lastMoveRawY = downRawY
                velocityTracker = VelocityTracker.obtain().apply {
                    addMovement(event)
                }
                val commitRun = Runnable {
                    commitDrag(lp)
                }
                commitRunnable = commitRun
                mainHandler.postDelayed(commitRun, HOLD_COMMIT_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragCommitted) {
                    if (abs(dx) > dp(TAP_SLOP_DP) || abs(dy) > dp(TAP_SLOP_DP)) {
                        commitDrag(lp)
                    }
                }
                if (dragCommitted) {
                    // La finestra segue il dito mantenendo il punto di presa.
                    posX = event.rawX - grabDx
                    posY = event.rawY - grabDy
                    applyWindow()
                    val mx = event.rawX - lastMoveRawX
                    if (abs(mx) > dp(1)) {
                        val newLeft = mx < 0f
                        if (newLeft != facingLeft) {
                            facingLeft = newLeft
                            applyPose("hang")
                        }
                    }
                    lastMoveRawX = event.rawX
                    lastMoveRawY = event.rawY
                }
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.apply {
                    addMovement(event)
                    computeCurrentVelocity(1000)
                }
                val vx = velocityTracker?.xVelocity ?: 0f
                val vy = velocityTracker?.yVelocity ?: 0f
                releaseTracker()
                commitRunnable?.let { mainHandler.removeCallbacks(it) }
                commitRunnable = null

                if (!dragCommitted) {
                    val duration = System.currentTimeMillis() - downTimeMs
                    val moved = abs(event.rawX - downRawX) > dp(TAP_SLOP_DP) ||
                        abs(event.rawY - downRawY) > dp(TAP_SLOP_DP)
                    if (duration < TAP_TIMEOUT_MS && !moved) {
                        // Tap: apre l'app. Nessun long-press, nessun menu.
                        context.startActivity(
                            Intent(context, MainActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                            }
                        )
                    }
                    return true
                }

                if (isOverHideTarget(event.rawX, event.rawY, lp)) {
                    hide()
                    return true
                }

                // Rilascio dopo il drag: volo o caduta.
                val feetY = posY + artFeetFrac * sizePx
                val alreadyOnFloor = feetY >= floorLineY - dp(2)
                val fvx = if (max(abs(vx), abs(vy)) < FLING_MIN_PX_S) 0f else vx
                val fvy = if (max(abs(vx), abs(vy)) < FLING_MIN_PX_S) 0f else vy
                if (alreadyOnFloor && abs(fvx) < dp(2) && abs(fvy) < dp(2)) {
                    // L'utente l'ha solo riposata: resta dov'è e si siede.
                    posY = floorTopY()
                    startSit()
                    return true
                }
                launchFlight(fvx, fvy)
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseTracker()
                commitRunnable?.let { mainHandler.removeCallbacks(it) }
                commitRunnable = null
                if (dragCommitted) {
                    launchFlight(0f, 0f)
                }
            }

            else -> return true
        }
        return true
    }

    private fun releaseTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun commitDrag(lp: WindowManager.LayoutParams) {
        if (dragCommitted) return
        dragCommitted = true
        commitRunnable?.let { mainHandler.removeCallbacks(it) }
        commitRunnable = null
        // Punto di presa = punto del tocco dentro la finestra.
        grabDx = downRawX - downLpX
        grabDy = downRawY - downLpY
        // Un eventuale timer "seduta -> idle" non deve scattare sotto il dito.
        settleRunnable?.let { mainHandler.removeCallbacks(it) }
        settleRunnable = null
        // La mascotte "pende" dalla mano, come nella chat.
        phase = Phase.DRAG
        applyPose("hang")
        showHideTarget(true)
    }

    private fun launchFlight(vx: Float, vy: Float) {
        phase = Phase.FLY
        velX = vx
        velY = vy
        bounceCount = 0
        hasTouchedFloor = false
        lastFrameNs = 0L
        applyPose("fall")
        showHideTarget(false)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    // ----------------------------------------------------------------- volo

    private fun stepPhysics(frameTimeNanos: Long) {
        if (phase != Phase.FLY) return
        if (lastFrameNs == 0L) lastFrameNs = frameTimeNanos
        var dt = (frameTimeNanos - lastFrameNs) / 1_000_000_000f
        lastFrameNs = frameTimeNanos
        dt = dt.coerceIn(0f, 1f / 30f)
        if (dt <= 0f) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
            return
        }

        velY = (velY + GRAVITY_PX_S2 * dt).coerceAtMost(MAX_FALL_SPEED_PX_S)
        velX *= max(0f, 1f - AIR_DRAG_PER_S * dt)
        posX += velX * dt
        posY += velY * dt

        // Pareti laterali.
        if (posX < 0f) {
            posX = 0f
            if (velX < 0f) velX = -velX * WALL_RESTITUTION
            if (abs(velX) < dp(2)) velX = 0f
        }
        val maxX = (screenW - sizePx).coerceAtLeast(0)
        if (posX > maxX) {
            posX = maxX.toFloat()
            if (velX > 0f) velX = -velX * WALL_RESTITUTION
            if (abs(velX) < dp(2)) velX = 0f
        }

        // Soffitto.
        if (posY < 0f) {
            posY = 0f
            if (velY < 0f) velY = -velY * CEILING_RESTITUTION
        }

        // Pavimento: rimbalzano i piedi (non il bordo trasparente della tela).
        val feetY = posY + artFeetFrac * sizePx
        if (feetY >= floorLineY && velY >= 0f) {
            posY = floorLineY - artFeetFrac * sizePx
            hasTouchedFloor = true
            applyPose("ground")
            if (bounceCount < MAX_BOUNCES && velY >= MIN_BOUNCE_PX_S) {
                velY = -velY * FLOOR_RESTITUTION
                velX *= 0.55f
                bounceCount++
            } else {
                velY = 0f
                velX = 0f
                settleFlight()
            }
        } else if (hasTouchedFloor) {
            applyPose("ground")
        } else {
            if (velX < -dp(1)) {
                if (!facingLeft) {
                    facingLeft = true
                    applyPose("fall")
                }
            } else if (velX > dp(1)) {
                if (facingLeft) {
                    facingLeft = false
                    applyPose("fall")
                }
            }
        }

        applyWindow()
        if (phase == Phase.FLY) Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun settleFlight() {
        if (phase != Phase.FLY) return
        phase = Phase.IDLE
        posY = floorTopY()
        applyWindow()
        startSit()
    }

    /** Posa `ground` per un istante (si risiede dopo il tonfo), poi idle. */
    private fun startSit() {
        phase = Phase.IDLE
        applyPose("ground")
        settleRunnable?.let { mainHandler.removeCallbacks(it) }
        val sitRunnable = Runnable {
            settleRunnable = null
            if (phase == Phase.IDLE && petView != null) applyPose("idle")
        }
        settleRunnable = sitRunnable
        mainHandler.postDelayed(sitRunnable, SIT_GROUND_MS)
    }

    // ------------------------------------------------------------- bersaglio

    private fun showHideTarget(visible: Boolean) {
        if (!visible) {
            removeTarget()
            return
        }
        if (targetView != null) return
        val size = dp(HIDE_TARGET_SIZE_DP)
        val tv = TextView(context).apply {
            text = "\u2715"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(204, 17, 24, 39))
                setStroke(dp(2), Color.argb(204, 239, 68, 68))
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val targetLp = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - size) / 2
            y = dp(HIDE_TARGET_TOP_DP)
        }
        runCatching { wm.addView(tv, targetLp) }
        targetView = tv
    }

    private fun isOverHideTarget(
        rawX: Float,
        rawY: Float,
        lp: WindowManager.LayoutParams
    ): Boolean {
        if (targetView == null) return false
        val size = dp(HIDE_TARGET_SIZE_DP)
        val left = (screenW - size) / 2
        val top = dp(HIDE_TARGET_TOP_DP)
        val extra = dp(HIDE_HIT_EXTRA_DP)
        val inTarget = rawX >= left - extra && rawX <= left + size + extra &&
            rawY >= top - extra && rawY <= top + size + extra
        if (inTarget) return true
        // In alternativa: la finestra della mascotte copre il bersaglio.
        val cx = lp.x + sizePx / 2f
        val cy = lp.y + sizePx / 2f
        return cx >= left && cx <= left + size && cy >= top && cy <= top + size
    }

    private fun applyWindow() {
        val lp = petParams ?: return
        val x = posX.roundToInt().coerceIn(0, max(0, screenW - sizePx))
        val y = posY.roundToInt().coerceIn(0, max(0, floorTopY().toInt().coerceAtLeast(0)))
        if (lp.x != x || lp.y != y) {
            lp.x = x
            lp.y = y
            runCatching { wm.updateViewLayout(petView, lp) }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
