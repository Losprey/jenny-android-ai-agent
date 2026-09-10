package com.flagdizero.jenny

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
 *  - drag: la finestra segue il dito (posa `hang`); il drag parte con un
 *    movimento oltre lo slop (niente "hold-to-grab": il long-press è il menu);
 *  - rilascio con velocità: volo con gravità, attrito sull'asse x, rimbalzi su
 *    pareti/soffitto e 1-3 piccoli rimbalzi sul pavimento prima di fermarsi;
 *  - rilascio "debole": caduta verticale e appoggio sul pavimento nel punto di
 *    rilascio;
 *  - appoggio: posa `ground` per un istante, poi `idle` col bob della chat;
 *  - tap breve senza spostamento = "poke" (reazione: hello1/hello2/think);
 *  - doppio tap = apre MainActivity (o voce "Apri la chat" del menu);
 *  - long-press (dito fermo) = menu rapido: dimensione (sm/md/lg), colore,
 *    dormi (sonno esplicito della pagina), nascondi, apri la chat;
 *  - curiosità: ogni tanto un micro-movimento autonomo gentile, solo a riposo
 *    e mai in risparmio energetico (qualsiasi tocco lo annulla);
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
        // Batch 7 — margine minimo di sicurezza dal bordo basso: quando ne gli
        // inset di sistema ne navigation_bar_height sono > 0 (navigazione a
        // gesture: la pillola non e una barra vera) il pavimento resta comunque
        // fuori dalla fascia riconosciuta come gesture di home.
        private const val MIN_SAFE_BOTTOM_DP = 48

        private const val HIDE_TARGET_SIZE_DP = 56
        private const val HIDE_TARGET_TOP_DP = 40
        private const val HIDE_HIT_EXTRA_DP = 18

        private const val TAP_SLOP_DP = 8
        // Gesti: tocco fermo per LONG_PRESS_MS = menu rapido; un tap senza
        // spostamento è "poke" se non arriva un secondo tap entro
        // DOUBLE_TAP_TIMEOUT_MS (che invece apre MainActivity).
        private const val LONG_PRESS_MS = 420L
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        private const val SIT_GROUND_MS = 650L
        private const val POKE_RETURN_MS = 1400L
        /** Pose di reazione del tap (chiavi ART esistenti nella pagina). */
        private val POKE_POSES = arrayOf("hello1", "hello2", "think")

        // Menu rapido nativo (long-press sulla mascotte).
        private const val MENU_WIDTH_DP = 182
        private const val MENU_ROW_HEIGHT_DP = 40
        private const val MENU_PAD_DP = 6
        private const val MENU_GAP_DP = 8
        private const val MENU_AUTO_DISMISS_MS = 6000L
        private const val MENU_CORNER_RADIUS_DP = 18

        // Micro-movimenti autonomi (curiosità): rari, lenti, solo a riposo.
        private const val CURIOUS_MIN_MS = 60_000L
        private const val CURIOUS_MAX_MS = 150_000L
        private const val CURIOUS_GLIDE_STEP_MS = 90L
        private const val CURIOUS_WANDER_MIN_DP = 46
        private const val CURIOUS_WANDER_MAX_DP = 120

        // Edge peek-hide (park mostly off-screen, art-aware: resta sempre
        // visibile >= 1/3 della sprite disegnata e uno sliver minimo toccabile).
        private const val PARK_BAND_DP = 36
        private const val PEEK_MIN_SLIVER_DP_DEFAULT = 44

        // Soglia batteria sotto cui il controller riduce il lavoro non
        // essenziale (pose/spostamenti puramente decorativi).
        private const val LOW_BATTERY_PERCENT = 15
        // Batch 4 — vibrazione sottile nativa (overlay/haptics, default true):
        // durate volutamente brevi, niente vibrazione su doppio tap o drag.
        private const val VIBRATE_POKE_MS = 16L
        private const val VIBRATE_MENU_MS = 24L
        private const val VIBRATE_SIZE_MS = 28L
        private const val VIBRATE_EASTER_MS = 55L
        private const val VIBRATE_EASTER_GAP_MS = 110L

        // Easter egg: N tap singoli ravvicinati (senza drag/long-press/doppio
        // tap) entro una finestra mobile; al raggiungimento il poke di quel tap
        // NON parte e la pagina celebra.
        private const val EASTER_TAP_COUNT = 6
        private const val EASTER_WINDOW_MS = 2500L
        private const val EASTER_RETURN_IDLE_MS = 2600L

        // Saluto quando l'app host torna in primo piano (sostituto delle
        // notifiche, batch 4): mai più spesso di CHEER_MIN_INTERVAL_MS e solo
        // se sono passati almeno CHEER_MIN_QUIET_MS dall'ultimo tocco.
        private const val CHEER_MIN_INTERVAL_MS = 60_000L
        private const val CHEER_MIN_QUIET_MS = 20_000L

        // ---- Batch 6: umore della mascotte ----
        // Modello esplicito su scala intera -2..+2 (la pagina overlay lo
        // riceve con window.__jennySetMood e lo usa solo per orientare pose
        // e ritmi che esistono gia):
        //   +2 EXCITED - gesto deciso appena successo (lancio/volo);
        //   +1 HAPPY   - interazione recente (poke, saluto, carica);
        //    0 NEUTRAL - riposo (deriva naturale);
        //   -1 TIRED   - batteria bassa o risparmio energetico attivo;
        //   -2 SLEEPY  - notte fonda con la mascotte lasciata in pace.
        // Fonti: SOLO segnali gia noti al controller (tocco, gesti, carica,
        // batteria, risparmio energetico, ora locale, idle): nessun listener
        // nuovo. Deriva: senza eventi di rinforzo per MOOD_DECAY_MS l'umore
        // torna a NEUTRAL da solo (timer periodico batch 6).
        private const val MOOD_EXCITED = 2
        private const val MOOD_HAPPY = 1
        private const val MOOD_NEUTRAL = 0
        private const val MOOD_TIRED = -1
        private const val MOOD_SLEEPY = -2
        private const val MOOD_DECAY_MS = 5 * 60_000L
        private const val MOOD_LONG_IDLE_MS = 10 * 60_000L
        private const val MOOD_LATE_NIGHT_START = 22
        private const val MOOD_LATE_NIGHT_END = 6

        // ---- Batch 6: smart hide ----
        // Preferenza overlay/smartHide (default true) + timer periodico
        // condiviso: ogni BATCH6_TICK_MS si ricalcola l'umore; ogni
        // SMART_HIDE_POLL_STEPS tick si chiede al sistema se c'e audio attivo.
        // Perche proprio l'audio: senza permessi di sistema (niente
        // AccessibilityService ne UsageStats, fuori scope) l'unico segnale
        // osservabile che un'app/video a schermo intero e davanti all'overlay
        // e la riproduzione media attiva (AudioManager.isMusicActive non
        // richiede permessi). Quando c'e, la mascotte si ritira; quando
        // smette, torna da sola. Vedi smartHidePoll().
        private const val BATCH6_TICK_MS = 3_000L
        private const val SMART_HIDE_POLL_STEPS = 4
        private const val SMART_HIDE_QUIET_MS = 15_000L

        // Ultimo stato "app host in primo piano" noto (batch 6): seed per
        // controller nati dopo l'ultimo cambio (onResume puo correre prima
        // che il service crei l'overlay). Default true: l'overlay nasce dalla
        // MainActivity in primo piano.
        @Volatile
        var overlayHostForeground = true

        // Ultimo stato "tastiera di sistema visibile" noto (batch 7): seed per
        // controller nati dopo l'ultimo cambio (l'IME puo essere gia su quando
        // il service crea l'overlay). Default false: l'overlay nasce dalla
        // MainActivity senza tastiera a video.
        @Volatile
        var overlayImeVisible = false

        // Saluto in sospeso (batch 8): seed per il caso in cui onResume chiami
        // cheerUp() (startForegroundService e asincrono) prima che il service
        // abbia creato l'overlay. Il controller nato nel frattempo lo consuma
        // appena la sua pagina e pronta, cosi il balzo di saluto non si perde.
        // Stesso schema di overlayHostForeground. Default false: nessun saluto
        // in attesa.
        @Volatile
        var overlayPendingCheer = false

        private const val GRAVITY_PX_S2 = 2500f
        private const val MAX_FALL_SPEED_PX_S = 3600f
        private const val FLOOR_RESTITUTION = 0.36f
        private const val WALL_RESTITUTION = 0.45f
        private const val CEILING_RESTITUTION = 0.25f
        private const val AIR_DRAG_PER_S = 0.8f
        private const val MIN_BOUNCE_PX_S = 150f
        private const val FLING_MIN_PX_S = 240f
        // Batch 5 — gesti decisi: oltre questa velocità al rilascio il drag
        // è un gesto, non un volo: orizzontale dominante = "lancio" verso il
        // bordo (che poi parcheggia), verso l'alto dominante = nascondi.
        private const val FLING_GESTURE_PX_S = 800f
        private const val MAX_BOUNCES = 3

        private const val PREFS_NAME = "overlay"
        private const val PREFS_HIDDEN = "hidden"
        // Posizione e parcheggio persistiti (stesso file "overlay" di
        // MainActivity: "hidden"/"asked"; qui chiavi con prefisso overlay/...).
        private const val PREFS_POS_X = "overlay/posX"
        private const val PREFS_POS_Y = "overlay/posY"
        private const val PREFS_PARKED_SIDE = "overlay/parkedSide"
        // Scelte del menu rapido e delle Impostazioni dell'app: mirror delle
        // localStorage della pagina
        // (ripristino se lo storage della WebView viene cancellato).
        // Dimensione: "sm"|"md"|"lg" (chiave assente = default della pagina);
        // colore: true = sprite -color.
        private const val PREFS_SIZE = "overlay/size"
        private const val PREFS_COLOR = "overlay/color"
        private const val PREFS_HAPTICS = "overlay/haptics"
        // Scritta dalla UI di impostazioni (batch 5, toggle AutoPark in
        // Impostazioni → Personalizzazione → Overlay); default = attivo.
        private const val PREFS_AUTO_PARK = "overlay/autoPark"
        // Smart hide (batch 6): toggle nelle Impostazioni; default attivo.
        private const val PREFS_SMART_HIDE = "overlay/smartHide"
        private const val PREFS_PEEK_MIN_SLIVER_DP = "overlay/peekMinSliverDp"

        private const val GATEWAY_HOST = "127.0.0.1"
        private const val GATEWAY_PORT = 18790
        private const val OVERLAY_PAGE_PATH = "/html-mobile/assets/jenny-overlay.html"
        private const val MAX_PAGE_ATTEMPTS = 15
        private const val PAGE_RETRY_MS = 900L
        private const val ART_POLL_MS = 120L
        private const val ART_POLL_MAX = 40

        // Istanza viva dell'overlay (un solo controller per processo: lo
        // possiede GatewayService). Le Impostazioni dell'app la usano per
        // applicare al volo taglia/colore alla mascotte gia visibile.
        @Volatile
        var live: JennyOverlayController? = null
    }

    private enum class Phase { NONE, IDLE, DRAG, FLY }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var petView: WebView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var targetView: View? = null

    private var phase = Phase.NONE
    private var facingLeft = false
    // Edge peek-hide: -1 = parcheggiato sul bordo sinistro, +1 = bordo destro,
    // 0 = non parcheggiato (finestra del tutto dentro lo schermo).
    private var parkedSide = 0

    // Geometria (px reali del display) e limiti "utili": dentro barre di
    // sistema/gesture e display cutout. Finché gli insets non sono noti i
    // limiti utili coincidono con tutto lo schermo e il pavimento usa il
    // vecchio calcolo via risorse/metriche (comportamento storico).
    private var screenW = 0
    private var screenH = 0
    private var usableLeft = 0
    private var usableTop = 0
    private var usableRight = 0
    private var usableBottom = 0
    private var floorLineY = 0          // y del display su cui poggiano i piedi
    private var sizePx = 0

    // Insets di sistema/gesture + display cutout, catturati dal WebView una
    // volta agganciato (solo R+). insetsKnown=false finché il listener non li
    // consegna: finché sono ignoti si resta sul percorso storico.
    private var insetsLeft = 0
    private var insetsTop = 0
    private var insetsRight = 0
    private var insetsBottom = 0
    private var insetsKnown = false

    // Frazioni del riquadro disegnato rispetto alla finestra quadrata.
    private var artTopFrac = 0f         // 0 finché non misurato
    private var artFeetFrac = 1f        // 1 = finestra piena finché non misurato
    private var artLeftFrac = -1f       // -1 finché non misurato (fallback 0)
    private var artRightFrac = -1f      // -1 finché non misurato (fallback 1)

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

    // Stato schermo/batteria: a schermo spento o in risparmio energetico il
    // controller ferma solo il lavoro non essenziale (il drag e il parcheggio
    // restano sempre attivi).
    private var screenOff = false
    private var powerSaveMode = false
    private var lowBattery = false

    private var screenReceiverRegistered = false
    private var displayListenerRegistered = false

    /** Notifica rotazione/cambio display (la Activity può rinascere, ma il
     *  GatewayService — e questo controller — sopravvivono). */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == defaultDisplayId()) onGeometryChanged()
        }
    }

    /** Schermo spento/acceso + cambi del risparmio energetico. */
    private val powerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> refreshPowerState()
                Intent.ACTION_POWER_CONNECTED -> refreshPowerState()
                Intent.ACTION_POWER_DISCONNECTED -> refreshPowerState()
                Intent.ACTION_BATTERY_CHANGED -> refreshPowerState()
                else -> {}
            }
        }
    }

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
        refreshGeometry()
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

    /** Applica subito i visual scelti dalle Impostazioni dell'app a una
     *  mascotte gia visibile: stesso percorso del menu rapido (persiste la
     *  preferenza, aggiorna la localStorage della pagina, riclampa la
     *  geometria / ricarica la posa corrente). Se l'overlay non e avviato non
     *  fa nulla: la pagina usera le preferenze gia scritte al prossimo avvio. */
    fun applyExternalVisuals(
        size: String? = null,
        color: Boolean? = null,
        haptics: Boolean? = null,
        autoPark: Boolean? = null,
        smartHide: Boolean? = null,
    ) {
        if (petView == null ||
            (size == null && color == null && haptics == null && autoPark == null &&
                smartHide == null)
        ) return
        val s = size
        val c = color
        val h = haptics
        val a = autoPark
        val sh = smartHide
        mainHandler.post {
            if (petView == null) return@post
            // Batch 8 — dimensione scelta dalle Impostazioni della SPA: nessuna
            // vibrazione, il dito e nella pagina, non sulla mascotte.
            if (s != null && s in SIZE_DP_BY_PREF) chooseSize(s, vibrate = false)
            if (c != null) chooseColor(c)
            if (h != null) chooseHaptics(h)
            if (a != null) chooseAutoPark(a)
            if (sh != null) chooseSmartHide(sh)
        }
    }

    // ----------------------------------------------------------- preferenze

    private fun overlayPrefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isHiddenPref() = overlayPrefs().getBoolean(PREFS_HIDDEN, false)

    private fun setHiddenPref(hidden: Boolean) {
        overlayPrefs().edit().putBoolean(PREFS_HIDDEN, hidden).apply()
    }

    /** Preferenza "parcheggio automatico" (overlay/autoPark, default true):
     *  la UI (batch 5) la scrive tramite MainActivity; qui decide il
     *  parcheggio al bordo dopo i riposi e al termine del "lancio"
     *  orizzontale del batch 5. */
    private fun autoParkPref(): Boolean = overlayPrefs().getBoolean(PREFS_AUTO_PARK, true)

    /** Preferenza "smart hide" (overlay/smartHide, default true): quando e
     *  attiva la mascotte si ritira da se se davanti c'e un'app/video a
     *  schermo intero (segnale osservabile senza permessi: audio attivo).
     *  La UI (batch 6) la scrive tramite MainActivity, come le altre scelte
     *  del blocco Overlay. */
    private fun smartHidePref(): Boolean = overlayPrefs().getBoolean(PREFS_SMART_HIDE, true)

    /** Sliver minimo di peek in dp (default 44, futura UI impostazioni). */
    private fun peekMinSliverDp(): Int {
        val v = overlayPrefs().getInt(PREFS_PEEK_MIN_SLIVER_DP, PEEK_MIN_SLIVER_DP_DEFAULT)
        return if (v in 8..200) v else PEEK_MIN_SLIVER_DP_DEFAULT
    }

    /** Dimensione scelta dal menu rapido (overlay/size, assente = default). */
    private fun readSizePref(): String? {
        val v = overlayPrefs().getString(PREFS_SIZE, null) ?: return null
        return if (SIZE_DP_BY_PREF.containsKey(v)) v else null
    }

    /** Preferenza colore (overlay/color; default true = sprite -color). */
    private fun colorPref(): Boolean = overlayPrefs().getBoolean(PREFS_COLOR, true)

    /** Stessa preferenza, ma "assente" distinguibile dal default. */
    private fun readColorPref(): Boolean? =
        if (overlayPrefs().contains(PREFS_COLOR)) colorPref() else null

    /** Preferenza vibrazione (overlay/haptics; default true). */
    private fun hapticsPref(): Boolean = overlayPrefs().getBoolean(PREFS_HAPTICS, true)

    /** Stessa preferenza, ma "assente" distinguibile dal default. */
    private fun readHapticsPref(): Boolean? =
        if (overlayPrefs().contains(PREFS_HAPTICS)) hapticsPref() else null

    /** Salva posizione e parcheggio correnti (niente dati sensibili). Chiamato
     *  a riposo (settle), a fine drag, a fine parcheggio e prima dello stop. */
    private fun persistState() {
        if (petView == null) return
        overlayPrefs().edit()
            .putFloat(PREFS_POS_X, posX)
            .putFloat(PREFS_POS_Y, posY)
            .putInt(PREFS_PARKED_SIDE, parkedSide)
            .apply()
    }

    /** Ripristina posizione/parcheggio salvati (se esistono). Ogni valore viene
     *  riclampato nei limiti utili CORRENTI e rimesso sul pavimento: valori
     *  stantii (schermo cambiato, rotazione) non possono mai finire fuori
     *  schermo o sotto la barra di stato. Con autoPark=false il parcheggio
     *  salvato viene ignorato (resta il clamp al bordo visibile). */
    private fun restoreSavedPosition() {
        val prefs = overlayPrefs()
        val hasX = prefs.contains(PREFS_POS_X)
        val hasY = prefs.contains(PREFS_POS_Y)
        val hasSide = prefs.contains(PREFS_PARKED_SIDE)
        if (!hasX && !hasY && !hasSide) return
        if (autoParkPref() && hasSide) {
            val side = prefs.getInt(PREFS_PARKED_SIDE, 0)
            if (side == -1 || side == 1) {
                // Percorso di parcheggio art-safe (peekOffsetPx), come a riposo.
                parkToSide(side)
                return
            }
        }
        posY = floorTopY()
        if (hasX) posX = prefs.getFloat(PREFS_POS_X, posX)
        posX = posX.coerceIn(minDockX().toFloat(), maxDockX().toFloat())
        applyWindow()
    }

    // -------------------------------------------------------------- display

    private fun displaySizePx(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** Limite sinistro della zona "utile" (in pratica 0; può crescere solo con
     *  un display cutout a sinistra). */
    private fun minDockX(): Int = usableLeft

    /** Limite destro dell'area di ancoraggio (dock) della finestra del tutto
     *  dentro lo schermo: dentro la zona utile (usableRight - sizePx). */
    private fun maxDockX(): Int = (usableRight - sizePx).coerceAtLeast(usableLeft)

    /** Ricalcola la geometria (schermo + limiti utili + pavimento).
     *  Con gli insets noti (R+, consegnati dal WebView) il pavimento e i bordi
     *  utili escludono barre di sistema/gesture e notch; senza insets si
     *  ripiega sul vecchio calcolo via risorse/metriche. Ritorna true se
     *  qualcosa è cambiato rispetto ai valori correnti. */
    private fun refreshGeometry(): Boolean {
        val (w, h) = displaySizePx()
        val sizeChanged = screenW != w || screenH != h
        screenW = w
        screenH = h

        var newLeft = 0
        var newTop = 0
        var newRight = w
        var newBottom = h
        var newFloor = floorLineY
        if (insetsKnown) {
            // Zona utile: dentro barre di sistema/gesture e display cutout.
            newLeft = insetsLeft.coerceIn(0, w)
            newTop = insetsTop.coerceIn(0, h)
            newRight = (w - insetsRight).coerceIn(newLeft, w)
            // Se la barra bassa non arriva come inset (finestra overlay che non
            // riceve insets) si ripiega sulla risorsa di sistema, con un minimo
            // di sicurezza (gesture nav: la pillola non e una barra vera).
            val bottomInset = if (insetsBottom > 0) insetsBottom else safeBottomInsetPx()
            newBottom = (h - bottomInset).coerceIn(newTop, h)
            // Pavimento: sopra la barra bassa con il solito piccolo margine.
            // safeBottomInsetPx() garantisce un bordo > 0 anche in gesture nav.
            newFloor = (newBottom - dp(FLOOR_GAP_DP)).coerceAtLeast(0)
        } else {
            // Fallback (pre-R o insets non ancora consegnati): barre ricavate
            // dalle risorse di sistema, con un minimo prudente. Il tetto utile
            // include status bar/cutout quando noti, cosi la mascotte non ci
            // vola sotto; il pavimento resta fuori dalla fascia di gesture.
            newTop = systemStatusBarHeightPx().coerceIn(0, h)
            val bottomInset = safeBottomInsetPx()
            newBottom = (h - bottomInset).coerceIn(newTop, h)
            newFloor = (newBottom - dp(FLOOR_GAP_DP)).coerceAtLeast(0)
        }

        val changed = sizeChanged ||
            newLeft != usableLeft || newTop != usableTop ||
            newRight != usableRight || newBottom != usableBottom ||
            newFloor != floorLineY
        if (changed) {
            usableLeft = newLeft
            usableTop = newTop
            usableRight = newRight
            usableBottom = newBottom
            floorLineY = newFloor
        }
        return changed
    }

    /** Chiamata quando cambiano insets (listener del WebView) o display
     *  (DisplayListener, rotazione): ricalcola la geometria utile e
     *  riposiziona la mascotte nei nuovi limiti, senza perdere stato né
     *  persistenza. */
    private fun onGeometryChanged() {
        if (!refreshGeometry()) return
        Log.i(
            TAG,
            "geometry: ${screenW}x${screenH} usable=" +
                "[$usableLeft,$usableTop,$usableRight,$usableBottom] floor=$floorLineY"
        )
        if (petView != null && phase == Phase.IDLE) reapplyToUsableBounds()
        // In volo/drag i nuovi muri e limiti valgono dal prossimo applyWindow.
    }

    /** Riclampa la mascotte nei limiti utili appena ricalcolati (rotazione,
     *  cambio insets): piedi sul pavimento, dentro i bordi orizzontali e, se
     *  parcheggiata, peek riapplicato con l'offset art-safe corrente. */
    private fun reapplyToUsableBounds() {
        if (petView == null || petParams == null) return
        posY = floorTopY()
        if (parkedSide != 0) {
            parkToSide(parkedSide)
        } else {
            posX = posX.coerceIn(minDockX().toFloat(), maxDockX().toFloat())
            applyWindow()
        }
        persistState()
    }

    /** Salva gli insets consegnati dal WebView e ricalcola la geometria utile.
     *  Su R+ si leggono direttamente systemBars/displayCutout; sotto R — dove
     *  quei metodi nativi non esistono — WindowInsetsCompat ricostruisce gli
     *  stessi valori dagli inset di sistema (API 21+). Se la finestra overlay
     *  non riceve affatto insets (FLAG_NOT_FOCUSABLE) resta in vigore il
     *  percorso a risorse/metriche di refreshGeometry. */
    private fun onWindowInsets(insets: WindowInsets, view: View) {
        val l: Int
        val t: Int
        val r: Int
        val b: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val cutout = insets.displayCutout
            l = max(bars.left, cutout?.safeInsetLeft ?: 0)
            t = max(bars.top, cutout?.safeInsetTop ?: 0)
            r = max(bars.right, cutout?.safeInsetRight ?: 0)
            b = max(bars.bottom, cutout?.safeInsetBottom ?: 0)
        } else {
            val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, view)
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = compat.displayCutout
            l = max(bars.left, cutout?.safeInsetLeft ?: 0)
            t = max(bars.top, cutout?.safeInsetTop ?: 0)
            r = max(bars.right, cutout?.safeInsetRight ?: 0)
            b = max(bars.bottom, cutout?.safeInsetBottom ?: 0)
        }
        val changed = !insetsKnown ||
            l != insetsLeft || t != insetsTop || r != insetsRight || b != insetsBottom
        if (!changed) return
        insetsLeft = l
        insetsTop = t
        insetsRight = r
        insetsBottom = b
        insetsKnown = true
        Log.i(TAG, "window insets: l=$l t=$t r=$r b=$b")
        onGeometryChanged()
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

    /** Bordo inferiore prudente: l'inset di sistema se noto, altrimenti la
     *  risorsa navigation_bar_height, e — quando anche quella e 0 (navigazione
     *  a gesture: la pillola non e una barra vera) — un minimo di sicurezza.
     *  Cosi il pavimento non finisce mai dentro la fascia di gesture. */
    private fun safeBottomInsetPx(): Int {
        val nav = systemNavBarHeightPx()
        return if (nav > 0) nav else dp(MIN_SAFE_BOTTOM_DP)
    }

    /** Altezza della status bar dalle risorse di sistema (0 se non leggibile).
     *  Usata come fallback quando gli insets non arrivano affatto. */
    private fun systemStatusBarHeightPx(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) {
            try {
                context.resources.getDimensionPixelSize(id)
            } catch (_: Exception) {
                0
            }
        } else 0
    }

    @Suppress("DEPRECATION")
    private fun defaultDisplayId(): Int = wm.defaultDisplay.displayId

    private fun registerDisplayListener() {
        if (displayListenerRegistered) return
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        runCatching { dm.registerDisplayListener(displayListener, mainHandler) }
            .onSuccess { displayListenerRegistered = true }
            .onFailure { Log.w(TAG, "registerDisplayListener failed", it) }
    }

    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        displayListenerRegistered = false
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        runCatching { dm.unregisterDisplayListener(displayListener) }
    }

    // ---------------------------------------------------------- power/schermo

    /** Vero quando conviene ridurre il lavoro non essenziale: schermo spento,
     *  risparmio energetico o batteria sotto soglia. Non tocca MAI il drag e
     *  non tocca il parcheggio (spec: "keep park"). */
    // Batch 4: stato "in carica" (cavo collegato) e livello batteria corrente.
    // Spinti alla pagina perché scelga pose/ritmi — la pagina non vede gli
    // eventi batteria di Android. Aggiornati in refreshPowerState().
    private var charging = false
    private var batteryLevel = 100

    private fun energySaveActive(): Boolean = screenOff || powerSaveMode || lowBattery

    /** Rilegge risparmio energetico + stato batteria e applica il gating.
     *  Batch 4: tiene anche aggiornati charging/batteryLevel e li spinge alla
     *  pagina (pose "in carica"/"stanca"). Le semantiche di risparmio non
     *  cambiano: lowBattery è ancora solo livello <= LOW_BATTERY_PERCENT. */
    private fun refreshPowerState() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        screenOff = !pm.isInteractive
        powerSaveMode = pm.isPowerSaveMode
        val (level, plugged) = readBatteryNow()
        batteryLevel = level
        charging = plugged
        lowBattery = batteryLevel <= LOW_BATTERY_PERCENT
        Log.i(
            TAG,
            "power: screenOff=$screenOff powerSave=$powerSaveMode " +
                "charging=$charging batteryLevel=$batteryLevel lowBattery=$lowBattery"
        )
        pushBatteryToPage()
        // Batch 6 — la carica/batteria/risparmio sono fonti di umore (modello
        // in companion): carica → felice; batteria bassa o risparmio → stanca.
        // Arrivano dagli stessi broadcast gia registrati qui (nessun listener
        // nuovo).
        if (charging) {
            raiseMood(MOOD_HAPPY)
        } else if (lowBattery || powerSaveMode) {
            lowerMood(MOOD_TIRED)
        } else {
            recomputeMood()
        }
        // Se il risparmio finisce mentre l'art non era ancora misurata, riparte
        // il polling (l'unica attività periodica del controller a riposo).
        if (!energySaveActive() && artFeetFrac >= 1f && petView != null) pollArtAndPrefs()
    }

    /** Livello batteria corrente + stato "cavo collegato" dalla sticky
     *  broadcast, o (100, false) se illeggibile. */
    private fun readBatteryNow(): Pair<Int, Boolean> {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return 100 to false
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0) return 100 to plugged
        return (level * 100 / scale).coerceIn(0, 100) to plugged
    }

    /** Spinge alla pagina lo stato batteria corrente perché scelga pose e
     *  ritmi (batch 4). Niente a schermo spento: la pagina non è visibile e lo
     *  stato verrà rimandato allo screen on o al prossimo evento batteria. */
    private fun pushBatteryToPage() {
        if (petView == null || screenOff) return
        evalJs(
            "window.__jennySetBattery && " +
                "window.__jennySetBattery($charging, $batteryLevel);"
        )
    }

    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        runCatching { context.registerReceiver(powerStateReceiver, filter) }
            .onSuccess { screenReceiverRegistered = true }
            .onFailure { Log.w(TAG, "registerReceiver(power) failed", it) }
    }

    private fun unregisterScreenStateReceiver() {
        if (!screenReceiverRegistered) return
        screenReceiverRegistered = false
        runCatching { context.unregisterReceiver(powerStateReceiver) }
    }

    /** Schermo spento. Niente posa "sleep" inventata: la pagina non ha una
     *  posa dedicata (ART = idle/hang/fall/ground + pose extra; il "sonno" è
     *  interno alla pagina, dopo ~30s di idle). Si ferma solo la fisica in
     *  corso e si salva lo stato. */
    private fun onScreenOff() {
        screenOff = true
        Log.i(TAG, "screen off")
        if (phase == Phase.FLY) {
            settleFlight() // ferma il Choreographer; riposiziona e salva
        } else if (petView != null && phase == Phase.IDLE) {
            persistState()
        }
    }

    /** Schermo di nuovo acceso: si rilegge lo stato energetico (che riprende
     *  da solo il polling dell'art se non era ancora misurata e il risparmio
     *  è finito). Nessuna posa da "svegliare": non ne abbiamo mai mandata una
     *  di sonno. */
    private fun onScreenOn() {
        screenOff = false
        Log.i(TAG, "screen on")
        refreshPowerState()
    }

    // ------------------------------------------------------------- teardown

    private fun teardown() {
        persistState()
        dismissMenu()
        phase = Phase.NONE
        if (live === this) live = null
        lastFrameNs = 0L
        mainHandler.removeCallbacksAndMessages(null)
        artPollRunnable = null
        settleRunnable = null
        curiousRunnable = null
        glideRunnable = null
        pendingTapRunnable = null
        longPressRunnable = null
        menuAutoDismiss = null
        touchActive = false
        menuCloseTouch = false
        longPressFired = false
        secondTapCandidate = false
        lastTapUpMs = 0L
        glideToken = 0
        easterTapCount = 0
        easterWindowStartMs = 0L
        lastCheerAtMs = 0L
        batch6TickRunnable = null
        batch6TickCount = 0
        smartHideMediaActive = false
        smartHidden = false
        retreatApplied = false
        mood = MOOD_NEUTRAL
        lastMoodEventMs = 0L
        lastMoodPush = -99
        charging = false
        batteryLevel = 100
        unregisterScreenStateReceiver()
        unregisterDisplayListener()
        removeTarget()
        petView?.let { v ->
            runCatching { wm.removeView(v) }
            v.destroy()
        }
        petView = null
        petParams = null
        pageAttempts = 0
        artPollCount = 0
        screenOff = false
        powerSaveMode = false
        lowBattery = false
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
        parkedSide = 0 // si riparte sempre non parcheggiato
        // Dimensione iniziale: preferenza del menu rapido se presente (stessa
        // scala sm/md/lg della SPA); il poll la riallinea alla pagina.
        sizePx = dp(SIZE_DP_BY_PREF[readSizePref() ?: "sm"] ?: DEFAULT_SIZE_DP)
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
            // Cattura barre di sistema/gesture e notch: su R+ dai tipi nativi,
            // sotto R via WindowInsetsCompat (batch 7). Da lì si ricalcola la
            // geometria "utile" della finestra.
            setOnApplyWindowInsetsListener { v, insets ->
                onWindowInsets(insets, v)
                insets
            }
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
                    seedPagePrefs()
                    pushBatteryToPage()
                    maybePushMood()
                    pollArtAndPrefs()
                    // Batch 8 — saluto rimasto in sospeso (onResume ha chiesto
                    // cheerUp prima che questo controller esistesse): si tenta
                    // ora che la pagina e pronta, cosi il balzo si vede; se non
                    // e il momento il seme resta e verra ritentato.
                    if (overlayPendingCheer) cheerUp()
                }
            }
        }

        // Appoggio iniziale: in basso al centro (dei limiti utili), piedi sul
        // pavimento. Se esistono valori salvati vengono ripristinati dopo
        // l'addView (restoreSavedPosition riclampa nei limiti correnti).
        posX = (minDockX() + maxDockX()) / 2f
        posY = floorTopY()
        lp.x = posX.roundToInt()
        lp.y = posY.roundToInt()

        wm.addView(web, lp)
        petView = web
        live = this // le Impostazioni dell'app possono applicare al volo i visual
        petParams = lp
        pageAttempts = 0
        restoreSavedPosition()
        registerDisplayListener()
        registerScreenStateReceiver()
        refreshPowerState()
        applyWindow()
        loadPage()
        scheduleCuriosity()
        scheduleBatch6Tick()
        // Batch 7: se una ritirata e gia in corso (smart hide attiva o IME gia
        // a video quando l'overlay nasce) la mascotte parte gia ritirata.
        retreatApplied = false
        syncRetreatState()
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

    /** Se la localStorage della pagina è stata cancellata, riallinea
     *  dimensione e colore con le preferenze del menu rapido (overlay/size,
     *  overlay/color). Se il colore effettivo della pagina cambia chiede il
     *  refresh dell'art (life-style: non sveglia se la pagina dorme). */
    private fun seedPagePrefs() {
        val view = petView ?: return
        val size = readSizePref()
        val color = readColorPref()
        val haptics = readHapticsPref()
        val autoPark = if (overlayPrefs().contains(PREFS_AUTO_PARK)) autoParkPref() else null
        val smartHide = if (overlayPrefs().contains(PREFS_SMART_HIDE)) smartHidePref() else null
        if (size == null && color == null && haptics == null && autoPark == null &&
            smartHide == null
        ) return
        var js = "(function(){try{"
        if (size != null) {
            js += "if(localStorage.getItem('jenny-mascotte-size')===null){" +
                "localStorage.setItem('jenny-mascotte-size','$size');}"
        }
        if (color != null) {
            val on = if (color) "1" else "0"
            js += "var b=localStorage.getItem('jenny-mascotte-color');" +
                "if(b===null){localStorage.setItem('jenny-mascotte-color','$on');" +
                "return ('1'!=='$on');}"
        }
        if (haptics != null) {
            val on = if (haptics) "1" else "0"
            js += "if(localStorage.getItem('jenny-mascotte-haptics')===null){" +
                "localStorage.setItem('jenny-mascotte-haptics','$on');}"
        }
        if (autoPark != null) {
            val on = if (autoPark) "1" else "0"
            js += "if(localStorage.getItem('jenny-mascotte-autopark')===null){" +
                "localStorage.setItem('jenny-mascotte-autopark','$on');}"
        }
        if (smartHide != null) {
            val on = if (smartHide) "1" else "0"
            js += "if(localStorage.getItem('jenny-mascotte-smarthide')===null){" +
                "localStorage.setItem('jenny-mascotte-smarthide','$on');}"
        }
        js += "return false;}catch(e){return false;}})()"
        view.evaluateJavascript(js) { raw ->
            if (raw == "true") {
                evalJs("window.__jennyRefreshArt && window.__jennyRefreshArt();")
            }
        }
    }

    /** Legge la posizione del riquadro disegnato (piedi/artFeetFrac, testa/
     *  artTopFrac, bordi orizzontali/artLeftFrac-artRightFrac) e la preferenza
     *  di dimensione scelta nella SPA (stessa localStorage). */
    private fun pollArtAndPrefs() {
        if (petView == null) return
        val js = (
            "(function(){var t=(window.__jennyArtTop?window.__jennyArtTop():-1);" +
                "var b=(window.__jennyArtBottom?window.__jennyArtBottom():-1);" +
                "var l=(window.__jennyArtLeft?window.__jennyArtLeft():-1);" +
                "var r=(window.__jennyArtRight?window.__jennyArtRight():-1);" +
                "var s='sm';try{s=localStorage.getItem('jenny-mascotte-size')||'sm';}catch(e){}" +
                "return {t:t,b:b,l:l,r:r,s:s};})()"
            )
        petView?.evaluateJavascript(js) { raw ->
            runCatching {
                if (raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
                val obj = JSONObject(raw)
                val b = obj.optDouble("b", -1.0)
                val t = obj.optDouble("t", -1.0)
                val l = obj.optDouble("l", -1.0)
                val r = obj.optDouble("r", -1.0)
                val sizePref = obj.optString("s", "sm")
                val newSize = dp(SIZE_DP_BY_PREF[sizePref] ?: DEFAULT_SIZE_DP)
                if (newSize != sizePx) resizeTo(newSize)
                val measured = b >= 0.0 && t >= 0.0
                if (measured) {
                    // Riquadro orizzontale della sprite: accettato solo se valido
                    // (frazioni in [0,1] e l <= r), altrimenti resta ignoto (-1) e
                    // il parcheggio usa il fallback a tutta larghezza (0..1).
                    val lf = l.toFloat()
                    val rf = r.toFloat()
                    if (lf in 0f..1f && rf in 0f..1f && lf <= rf) {
                        artLeftFrac = lf
                        artRightFrac = rf
                    } else {
                        artLeftFrac = -1f
                        artRightFrac = -1f
                    }
                }
                if (measured && artFeetFrac != b.toFloat()) {
                    artFeetFrac = b.toFloat().coerceIn(0.05f, 1f)
                    artTopFrac = t.toFloat().coerceIn(0f, 0.95f)
                    if (phase == Phase.IDLE) {
                        posY = floorTopY()
                        if (parkedSide != 0) {
                            // Misura appena arrivata con la mascotte parcheggiata:
                            // ripete il posizionamento di peek col riquadro reale
                            // (si corregge da solo rispetto al fallback iniziale).
                            parkToSide(parkedSide)
                        } else {
                            applyWindow()
                        }
                    }
                } else if (measured && phase == Phase.IDLE && parkedSide != 0) {
                    // Verticale già nota e invariata, ma si era parcheggiato prima
                    // della misura orizzontale: riallinea comunque il peek.
                    parkToSide(parkedSide)
                }
            }
            if (artFeetFrac >= 1f && artPollCount < ART_POLL_MAX && !energySaveActive()) {
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
            if (parkedSide != 0) {
                // Ridimensionamento mentre è parcheggiato: resta parcheggiato
                // (nuovo offset di peek, piedi sul pavimento).
                parkToSide(parkedSide)
            } else {
                posX = (posX * ratio).coerceIn(minDockX().toFloat(), maxDockX().toFloat())
                posY = floorTopY()
            }
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
    private var lastMoveRawX = 0f
    private var lastMoveRawY = 0f
    private var velocityTracker: VelocityTracker? = null

    // Stato del gesto corrente e del menu rapido (long-press).
    private var touchActive = false
    private var menuCloseTouch = false
    private var longPressRunnable: Runnable? = null
    private var longPressFired = false
    private var pendingTapRunnable: Runnable? = null
    private var secondTapCandidate = false
    private var lastTapUpMs = 0L
    private var menuOpen = false
    private var menuView: View? = null
    private var menuAutoDismiss: Runnable? = null
    private var menuRequestToken = 0

    // Curiosità: micro-movimenti autonomi a riposo (sezione dedicata).
    private var curiousRunnable: Runnable? = null
    private var glideRunnable: Runnable? = null
    private var glideToken = 0
    private var lastInteractionMs = 0L

    // Easter egg (batch 4): conteggio dei tap singoli in una finestra mobile.
    private var easterTapCount = 0
    private var easterWindowStartMs = 0L

    // Ultimo saluto "app in primo piano" (batch 4), per il rate-limit.
    private var lastCheerAtMs = 0L

    // ---- Batch 6: umore corrente (-2..+2, costanti MOOD_* in alto) ----
    // Aggiornato SOLO da eventi gia noti (raiseMood/lowerMood/recomputeMood).
    private var mood = MOOD_NEUTRAL
    private var lastMoodEventMs = 0L
    private var lastMoodPush = -99

    // Timer periodico condiviso di batch 6 (umore/deriva + smart hide).
    private var batch6TickRunnable: Runnable? = null
    private var batch6TickCount = 0

    // Smart hide: mediaActive = c'e audio in riproduzione; smartHidden = si
    // e ritirata da sola (WebView GONE + finestra non toccabile).
    private var smartHideMediaActive = false
    private var smartHidden = false

    // Batch 7 — ritirata unificata: `smartHidden` (smart hide) e
    // `overlayImeVisible` (tastiera a video) sono cause indipendenti; questo
    // flag rispecchia cio che e applicato alla view, cosi le due cause non si
    // annullano a vicenda (prima setHostForeground(true) cancellava anche una
    // ritirata da IME). `retreatApplied` e l'unico stato possibile della view.
    private var retreatApplied = false

    /** Batch 8 — "nascosta per qualunque causa": la stessa ritirata unificata
     *  qui sopra (smart hide *oppure* IME). Un solo predicato per i percorsi di
     *  interazione, cosi non se ne dimentica una delle due cause. */
    private fun isRetreated(): Boolean = retreatApplied

    @SuppressLint("ClickableViewAccessibility")
    private fun onPetTouch(event: MotionEvent): Boolean {
        if (phase == Phase.FLY) return true // in volo non si afferra: si aspetta
        if (petView == null) return true
        val lp = petParams ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Qualsiasi tocco annulla i micro-movimenti di curiosità in
                // corso e chiude un eventuale menu rapido aperto.
                touchActive = true
                lastInteractionMs = System.currentTimeMillis()
                stopGlide()
                if (menuOpen) {
                    // Il tocco serviva solo a chiudere il menu: non deve
                    // contare come tap/poke sulla mascotte.
                    dismissMenu()
                    menuCloseTouch = true
                } else {
                    menuCloseTouch = false
                }
                if (pendingTapRunnable != null) {
                    // Un tap precedente attendeva nella finestra del doppio
                    // tap: questo tocco può completare il doppio tap.
                    cancelPendingPoke()
                    secondTapCandidate = true
                } else {
                    secondTapCandidate = false
                }
                downRawX = event.rawX
                downRawY = event.rawY
                downLpX = lp.x
                downLpY = lp.y
                grabDx = 0f
                grabDy = 0f
                dragCommitted = false
                lastMoveRawX = downRawX
                lastMoveRawY = downRawY
                velocityTracker = VelocityTracker.obtain().apply {
                    addMovement(event)
                }
                // Long-press: se il dito resta fermo si apre il menu rapido.
                val longRun = Runnable { onLongPressFired() }
                longPressRunnable = longRun
                mainHandler.postDelayed(longRun, LONG_PRESS_MS)
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
                touchActive = false
                lastInteractionMs = System.currentTimeMillis()
                longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                longPressRunnable = null
                // Ogni interazione riavvia l'orologio dei micro-movimenti.
                scheduleCuriosity()

                if (!dragCommitted) {
                    val moved = abs(event.rawX - downRawX) > dp(TAP_SLOP_DP) ||
                        abs(event.rawY - downRawY) > dp(TAP_SLOP_DP)
                    if (longPressFired) {
                        // Rilascio dopo un long-press: chiude solo il menu.
                        longPressFired = false
                        secondTapCandidate = false
                        lastTapUpMs = 0L
                        dismissMenu()
                        return true
                    }
                    if (moved) {
                        // Micro-spostamento sotto lo slop: nessun gesto.
                        secondTapCandidate = false
                        lastTapUpMs = 0L
                        return true
                    }
                    if (menuCloseTouch) {
                        // Il tocco serviva solo a chiudere il menu.
                        menuCloseTouch = false
                        secondTapCandidate = false
                        lastTapUpMs = 0L
                        return true
                    }
                    if (parkedSide != 0) {
                        // Tap sullo sliver di un pet parcheggiato: non apre
                        // l'app e non fa "poke": riporta al bordo visibile.
                        secondTapCandidate = false
                        lastTapUpMs = 0L
                        unParkToEdge()
                        return true
                    }
                    handleTapUp()
                    return true
                }

                if (isOverHideTarget(event.rawX, event.rawY, lp)) {
                    hide()
                    return true
                }

                // Batch 5 — swipe deciso verso l'alto: nasconde la mascotte
                // come la voce "Nascondi" del menu rapido (stessa preferenza
                // persistente overlay/hidden, teardown differito da hide())
                // senza passare dal volo. Qui si arriva solo con dragCommitted
                // (tap/tocco doppio/long-press sono già usciti sopra), quindi
                // la disambiguazione dai tocchi è già garantita.
                if (vy <= -FLING_GESTURE_PX_S && abs(vy) >= abs(vx)) {
                    hide()
                    return true
                }

                if (parkedSide != 0) {
                    // Rilascio dopo un drag partito da parcheggiato: niente volo.
                    releaseParkedDrag()
                    return true
                }

                // Rilascio dopo il drag: volo o caduta.
                val feetY = posY + artFeetFrac * sizePx
                val alreadyOnFloor = feetY >= floorLineY - dp(2)
                val fvx = if (max(abs(vx), abs(vy)) < FLING_MIN_PX_S) 0f else vx
                val fvy = if (max(abs(vx), abs(vy)) < FLING_MIN_PX_S) 0f else vy
                if (alreadyOnFloor && abs(fvx) < dp(2) && abs(fvy) < dp(2)) {
                    // L'utente l'ha solo riposata: resta dov'è (in ogni caso
                    // del tutto dentro lo schermo) e si siede.
                    posY = floorTopY()
                    maybeParkAfterSettle()
                    startSit()
                    return true
                }
                // Batch 5 — swipe orizzontale deciso da pet già sul pavimento:
                // "lancio" verso il bordo indicato. La mascotte scivola sul
                // pavimento fino al bordo visibile della zona utile e lì entra
                // nel normale parcheggio (peek con sliver minimo se autoPark,
                // altrimenti resta al clamp di bordo, del tutto visibile). Gli
                // swipe obliqui, deboli o da mezz'aria restano un volo fisico
                // (sotto, invariato).
                if (alreadyOnFloor && abs(vx) >= FLING_GESTURE_PX_S && abs(vx) > abs(vy)) {
                    throwToEdge(if (vx < 0f) -1 else 1)
                    return true
                }
                launchFlight(fvx, fvy)
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseTracker()
                touchActive = false
                lastInteractionMs = System.currentTimeMillis()
                stopGlide()
                longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                longPressRunnable = null
                cancelPendingPoke()
                secondTapCandidate = false
                lastTapUpMs = 0L
                resetEasterTaps()
                menuCloseTouch = false
                if (longPressFired) {
                    longPressFired = false
                    dismissMenu()
                }
                scheduleCuriosity()
                if (dragCommitted) {
                    if (parkedSide != 0) {
                        // Drag interrotto di un pet parcheggiato: torna in peek.
                        showHideTarget(false)
                        parkToSide(parkedSide)
                        persistState()
                        startSit()
                    } else if (screenOff) {
                        // Schermo spento a metà drag (ACTION_CANCEL): niente
                        // volo inutile, la mascotte si appoggia dov'è.
                        phase = Phase.IDLE
                        posY = floorTopY()
                        maybeParkAfterSettle()
                        startSit()
                    } else {
                        launchFlight(0f, 0f)
                    }
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
        // Un drag non è un tap né un doppio tap: menu e "poke" in attesa via.
        dismissMenu()
        cancelPendingPoke()
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
        longPressFired = false
        secondTapCandidate = false
        lastTapUpMs = 0L
        resetEasterTaps()
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
        if (abs(vx) + abs(vy) >= FLING_MIN_PX_S) raiseMood(MOOD_EXCITED)
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

        // Pareti laterali (dentro la zona utile).
        if (posX < minDockX()) {
            posX = minDockX().toFloat()
            if (velX < 0f) velX = -velX * WALL_RESTITUTION
            if (abs(velX) < dp(2)) velX = 0f
        }
        val maxX = maxDockX()
        if (posX > maxX) {
            posX = maxX.toFloat()
            if (velX > 0f) velX = -velX * WALL_RESTITUTION
            if (abs(velX) < dp(2)) velX = 0f
        }

        // Soffitto: mai sopra la zona utile (orologio/notch).
        if (posY < usableTop) {
            posY = usableTop.toFloat()
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
        maybeParkAfterSettle()
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

    // ---------------------------------------------- edge peek-hide (parcheggio)

    /** Offset massimo (px) con cui la finestra può sporgere fuori schermo sul
     *  bordo indicato (side < 0 → sinistra, side > 0 → destra) restando visibile
     *  almeno 1/3 del riquadro DISEGNATO della sprite (non del finestrino
     *  trasparente). Con S = sizePx e aL/aR frazioni orizzontali dell'art nella
     *  finestra (fallback 0/1 se ignote, artW = (aR - aL) * S):
     *   - a sinistra (posX = -offset, a schermo resta la striscia destra della
     *     finestra) l'art visibile è aR*S - offset: serve offset <= S*(aL+2aR)/3
     *     perché resti esattamente artW/3;
     *   - a destra (posX = maxX + offset, a schermo resta la striscia sinistra)
     *     serve offset <= S*(3 - 2aL - aR)/3.
     *  Il risultato è limitato a S - sliver minimo (peekMinSliverDp): lo
     *  sliver a schermo resta comunque un bersaglio toccabile. Con art ignota
     *  (fallback 0..1) il limite è S*2/3: un terzo della finestra resta
     *  visibile (sicuro). */
    private fun peekOffsetPx(side: Int): Int {
        val s = sizePx
        var aL = if (artLeftFrac in 0f..1f) artLeftFrac else 0f
        var aR = if (artRightFrac in 0f..1f) artRightFrac else 1f
        if (aL > aR) { aL = 0f; aR = 1f }
        val artBound = if (side < 0) {
            s * (aL + 2f * aR) / 3f
        } else {
            s * (3f - 2f * aL - aR) / 3f
        }
        val sliverCap = (s - dp(peekMinSliverDp())).toFloat()
        return max(0, min(artBound, sliverCap).roundToInt())
    }

    /** Porta la mascotte in posizione di peek sul bordo indicato (side < 0 →
     *  sinistra, side > 0 → destra): posX = -offset o posX = maxX + offset con
     *  offset = peekOffsetPx(side) (limite art-safe). y resta sul pavimento. */
    private fun parkToSide(side: Int) {
        val minX = minDockX()
        val maxX = maxDockX()
        val offset = peekOffsetPx(side)
        parkedSide = if (side < 0) -1 else 1
        posX = if (side < 0) (minX - offset).toFloat() else (maxX + offset).toFloat()
        posY = floorTopY()
        applyWindow()
    }

    /** Da parcheggiato: tap sullo sliver → si rientra sul bordo visibile della
     *  zona utile (x = minX o maxX), finestra completamente visibile, stato
     *  normale. */
    private fun unParkToEdge() {
        val side = parkedSide
        val maxX = maxDockX()
        parkedSide = 0
        posX = if (side < 0) minDockX().toFloat() else maxX.toFloat()
        posY = floorTopY()
        applyWindow()
        persistState()
    }

    /** Da chiamare SOLO a riposo (settleFlight o rilascio debole sul pavimento),
     *  mai in volo. Se la finestra si è fermata nella fascia di bordo
     *  (PARK_BAND_DP) viene parcheggiata in peek su quel lato (quasi del tutto
     *  fuori schermo, ma con >= 1/3 del riquadro disegnato ancora visibile e uno
     *  sliver minimo toccabile); altrimenti resta dov'è, del tutto dentro la
     *  zona utile (x in [minX, maxX]). */
    private fun maybeParkAfterSettle() {
        if (!autoParkPref()) {
            // autoPark=false: mai parcheggiare; si resta al clamp di bordo.
            parkedSide = 0
            posX = posX.coerceIn(minDockX().toFloat(), maxDockX().toFloat())
            applyWindow()
            persistState()
            return
        }
        val maxX = maxDockX()
        val band = dp(PARK_BAND_DP)
        val side = when {
            posX <= minDockX() + band -> -1
            posX >= maxX - band -> 1
            else -> 0
        }
        if (side != 0) {
            parkToSide(side)
        } else {
            parkedSide = 0
            posX = posX.coerceIn(minDockX().toFloat(), maxDockX().toFloat())
            applyWindow()
        }
        persistState()
    }

    /** Rilascio di un drag partito da parcheggiato: il volo è soppresso. Se la
     *  mascotte è stata sfilata del tutto (x in [minX, maxX]) esce dal
     *  parcheggio e vale il rilascio debole normale (re-park se finisce di
     *  nuovo in fascia di bordo); se è ancora in parte fuori schermo torna
     *  nella posizione di peek. */
    private fun releaseParkedDrag() {
        showHideTarget(false)
        val minX = minDockX()
        val maxX = maxDockX()
        if (posX >= minX && posX <= maxX.toFloat()) {
            parkedSide = 0
            val feetY = posY + artFeetFrac * sizePx
            if (feetY >= floorLineY - dp(2)) {
                posY = floorTopY()
                maybeParkAfterSettle()
                startSit()
            } else {
                // Caduta verticale (zero velocità orizzontale), mai un volo vero.
                launchFlight(0f, 0f)
            }
        } else {
            parkToSide(parkedSide)
            persistState()
            startSit()
        }
    }

    // ------------------------------------------------------------- gesti (tap)

    /** Un tap valido (breve, senza spostamento): se arriva entro la finestra
     *  dal tap precedente è un doppio tap e apre l'app; altrimenti si accoda
     *  un "poke" (reazione) che scatta solo se non arriva un secondo tocco. */
    private fun handleTapUp() {
        val now = System.currentTimeMillis()
        if (secondTapCandidate && lastTapUpMs != 0L) {
            secondTapCandidate = false
            lastTapUpMs = 0L
            resetEasterTaps()
            openMainApp()
            return
        }
        secondTapCandidate = false
        lastTapUpMs = now
        // Easter egg: un tap singolo conta per la sequenza; al raggiungimento
        // della soglia recordEasterTap cancella il poke in attesa e fa partire
        // la celebrazione (nessun poke "strano" dopo il tap che la scatena).
        if (recordEasterTap(now)) return
        cancelPendingPoke()
        val run = Runnable {
            pendingTapRunnable = null
            lastTapUpMs = 0L
            poke()
        }
        pendingTapRunnable = run
        mainHandler.postDelayed(run, DOUBLE_TAP_TIMEOUT_MS)
    }

    private fun cancelPendingPoke() {
        pendingTapRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingTapRunnable = null
    }

    // --------------------------------------------- easter egg + cheer (batch 4)

    /** Conteggia un tap singolo per l'easter egg: EASTER_TAP_COUNT tap
     *  ravvicinati (finestra mobile EASTER_WINDOW_MS) scatenano la
     *  celebrazione. Ritorna true se è scattata: il poke di QUEL tap non deve
     *  partire (il tap è già servito a far festa). */
    private fun recordEasterTap(now: Long): Boolean {
        if (now - easterWindowStartMs > EASTER_WINDOW_MS) {
            easterTapCount = 1
            easterWindowStartMs = now
            return false
        }
        easterTapCount++
        if (easterTapCount >= EASTER_TAP_COUNT) {
            easterTapCount = 0
            easterWindowStartMs = 0L
            cancelPendingPoke()
            triggerEasterEgg()
            return true
        }
        return false
    }

    /** Ogni gesto che non è un tap (drag, long-press, doppio tap, cancel)
     *  azzera la sequenza. La finestra EASTER_WINDOW_MS fa da reset nel tempo. */
    private fun resetEasterTaps() {
        easterTapCount = 0
        easterWindowStartMs = 0L
    }

    /** Celebrazione easter egg: pose rapide nella pagina (window.__jennyCelebrate)
     *  + doppia vibrazione breve; poi ritorno a idle. Cancella il ritorno a
     *  idle di una poke precedente, così la pagina non viene interrotta. */
    private fun triggerEasterEgg() {
        Log.i(TAG, "easter egg: ${EASTER_TAP_COUNT} tap ravvicinati")
        settleRunnable?.let { mainHandler.removeCallbacks(it) }
        settleRunnable = null
        vibrate(VIBRATE_EASTER_MS)
        mainHandler.postDelayed(
            Runnable { vibrate(VIBRATE_EASTER_MS) },
            VIBRATE_EASTER_GAP_MS
        )
        evalJs("window.__jennyCelebrate && window.__jennyCelebrate();")
        val returnRun = Runnable {
            settleRunnable = null
            if (petView != null && phase == Phase.IDLE) applyPose("idle")
        }
        settleRunnable = returnRun
        mainHandler.postDelayed(returnRun, EASTER_RETURN_IDLE_MS)
    }

    /** Saluto giocoso quando l'app host torna in primo piano (batch 4). È
     *  l'equivalente più vicino — senza aggiungere permessi di sistema — a una
     *  reazione alle notifiche in arrivo: in questa app non esiste un hook di
     *  notifica raggiungibile dall'overlay (servirebbe un
     *  NotificationListenerService, permesso speciale), quindi la visibilità
     *  dell'app fa da segnale. Decide da solo se è appropriato: mascotte
     *  visibile, a riposo, pagina sveglia (un pet messo a dormire
     *  esplicitamente non viene svegliato), fuori dal risparmio energetico,
     *  con i rate-limit CHEER_MIN_INTERVAL_MS / CHEER_MIN_QUIET_MS. */
    fun cheerUp() {
        if (petView == null || petParams == null) return
        if (phase != Phase.IDLE || dragCommitted || touchActive) return
        if (settleRunnable != null || menuOpen) return
        if (energySaveActive()) return
        val now = System.currentTimeMillis()
        if (now - lastCheerAtMs < CHEER_MIN_INTERVAL_MS) return
        if (lastInteractionMs != 0L && now - lastInteractionMs < CHEER_MIN_QUIET_MS) return
        // Batch 8 — superati i controlli il saluto parte (o almeno tocca
        // l'umore): il seme lasciato da onResume non e piu pendente.
        overlayPendingCheer = false
        // Batch 8 — ritirata (smart hide o IME): niente posa ne ritorno
        // programmato su una view GONE, che non si vedrebbe. L'umore pero e
        // stato, non pittura: l'evento e reale (host tornato in primo piano) e
        // si aggiorna comunque, cosi la mascotte riappare gia contenta.
        if (isRetreated()) {
            lastCheerAtMs = now
            raiseMood(MOOD_HAPPY)
            return
        }
        val view = petView ?: return
        view.evaluateJavascript("window.__jennySleeping ? window.__jennySleeping() : false") { raw ->
            if (petView == null || petParams == null) return@evaluateJavascript
            // Pagina che dorme (anche sonno esplicito "Dormi"): niente saluto.
            if (raw != null && raw.trim() == "true") return@evaluateJavascript
            lastCheerAtMs = System.currentTimeMillis()
            raiseMood(MOOD_HAPPY)
            val pose = POKE_POSES[(Math.random() * POKE_POSES.size).toInt()]
            applyPose(pose)
            settleRunnable?.let { mainHandler.removeCallbacks(it) }
            settleRunnable = null
            val returnRun = Runnable {
                settleRunnable = null
                if (petView != null && phase == Phase.IDLE) applyPose("idle")
            }
            settleRunnable = returnRun
            mainHandler.postDelayed(returnRun, POKE_RETURN_MS)
        }
    }

    /** Vibrazione sottile (batch 4): nativa perché i gesti (tap singolo,
     *  apertura menu, cambio dimensione) vivono nel controller. Rispetta la
     *  preferenza overlay/haptics (default true) ed è sempre protetta: nessuna
     *  eccezione deve uscire dalla gestione del tocco. */
    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        if (!hapticsPref()) return
        val vibrator = runCatching {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }.getOrNull() ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                vibrator.vibrate(ms)
            }
        }
    }

    /** Preferenza "vibrazione sottile" applicata dalle Impostazioni: persistita
     *  in overlay/haptics e rispecchiata in localStorage (chiave
     *  jenny-mascotte-haptics, stesso schema di size/color). La pagina non
     *  vibra: gli haptics sono nativi (poke, menu, cambio dimensione, easter
     *  egg). */
    private fun chooseHaptics(on: Boolean) {
        overlayPrefs().edit().putBoolean(PREFS_HAPTICS, on).apply()
        val mirror = if (on) "1" else "0"
        evalJs(
            "try{localStorage.setItem('jenny-mascotte-haptics', '$mirror');}catch(e){}"
        )
    }

    /** Preferenza "parcheggio automatico" applicata dalle Impostazioni
     *  (batch 5): persistita in overlay/autoPark e rispecchiata in
     *  localStorage (jenny-mascotte-autopark, stesso schema di haptics).
     *  Effetti al volo sulla mascotte visibile: spegnendola, un pet
     *  parcheggiato esce al bordo visibile della zona utile (stesso effetto
     *  del tap sullo sliver); riaccendendola, un pet fermo in fascia di
     *  bordo si parcheggia. Durante drag/volo/corsa/menu non tocca nulla:
     *  la preferenza vale comunque al prossimo riposo. */
    private fun chooseAutoPark(on: Boolean) {
        overlayPrefs().edit().putBoolean(PREFS_AUTO_PARK, on).apply()
        if (petView == null || petParams == null) return
        val mirror = if (on) "1" else "0"
        evalJs("try{localStorage.setItem('jenny-mascotte-autopark', '$mirror');}catch(e){}")
        if (phase != Phase.IDLE || touchActive || dragCommitted || glideRunnable != null || menuOpen) return
        if (!on) {
            if (parkedSide != 0) {
                unParkToEdge()
                startSit()
            }
        } else {
            if (parkedSide == 0 && settleRunnable == null) {
                maybeParkAfterSettle()
                if (parkedSide != 0) startSit()
            }
        }
    }

    /** Tap singolo: breve reazione "viva" (pose ART già esistenti nella
     *  pagina, mai inventate), poi ritorno a idle. Il ritorno condivide il
     *  timer "settle": un nuovo tocco o un drag lo cancellano. */
    /** Preferenza "smart hide" applicata dalle Impostazioni (batch 6):
     *  persistita in overlay/smartHide e rispecchiata in localStorage
     *  (chiave jenny-mascotte-smarthide, stesso schema di haptics/autoPark).
     *  Nessun effetto geometrico immediato; se la mascotte e in una ritirata
     *  automatica e l'opzione viene spenta, torna subito visibile. */
    private fun chooseSmartHide(on: Boolean) {
        overlayPrefs().edit().putBoolean(PREFS_SMART_HIDE, on).apply()
        val mirror = if (on) "1" else "0"
        evalJs("try{localStorage.setItem('jenny-mascotte-smarthide', '$mirror');}catch(e){}")
        if (!on) {
            smartHidden = false
            syncRetreatState()
        }
    }

    private fun poke() {
        if (petView == null || phase != Phase.IDLE) return
        if (touchActive || dragCommitted) return
        // Batch 8 — ritirata (smart hide o IME): la mascotte e GONE, il
        // brindisi resterebbe invisibile. Nessun effetto e nessun ritorno
        // programmato (il tocco in teoria non arriva, ma la corsa con il
        // ritiro dell'IME e reale).
        if (isRetreated()) return
        raiseMood(MOOD_HAPPY)
        vibrate(VIBRATE_POKE_MS)
        val pose = POKE_POSES[(Math.random() * POKE_POSES.size).toInt()]
        applyPose(pose)
        settleRunnable?.let { mainHandler.removeCallbacks(it) }
        settleRunnable = null
        val returnRun = Runnable {
            settleRunnable = null
            if (petView != null && phase == Phase.IDLE) applyPose("idle")
        }
        settleRunnable = returnRun
        mainHandler.postDelayed(returnRun, POKE_RETURN_MS)
    }

    /** Apre MainActivity (stesso avvio del vecchio tap singolo di batch-1).
     *  Chi possiede SYSTEM_ALERT_WINDOW può partire anche da background. */
    private fun openMainApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            )
        }
    }

    /** Long-press (dito fermo LONG_PRESS_MS): apre il menu rapido. */
    private fun onLongPressFired() {
        longPressRunnable = null
        if (petView == null || petParams == null) return
        if (dragCommitted || phase != Phase.IDLE) return
        if (longPressFired) return
        // Il long-press non è un tap: al rilascio non deve fare poke.
        longPressFired = true
        cancelPendingPoke()
        secondTapCandidate = false
        lastTapUpMs = 0L
        resetEasterTaps()
        stopGlide()
        openMenu()
    }

    // ------------------------------------------------------------- menu rapido

    /** Menu del long-press: dimensioni, colore, dormi, nascondi, apri chat.
     *  Finestra nativa sopra la mascotte (come il bersaglio ✕), non DOM della
     *  pagina: i tocchi restano gestiti dal controller e la pagina resta un
     *  palcoscenico senza logica di UI. */
    private fun openMenu() {
        val view = petView ?: return
        vibrate(VIBRATE_MENU_MS)
        val token = ++menuRequestToken
        // Evidenzia dimensione/colore VERI leggendoli dalla pagina (stessa
        // localStorage della SPA), non da una copia locale potenzialmente
        // vecchia.
        view.evaluateJavascript(
            "(window.__jennyPrefs?JSON.stringify(window.__jennyPrefs()):'null')"
        ) { raw ->
            if (petView == null || petParams == null || menuRequestToken != token) {
                return@evaluateJavascript
            }
            var size = "sm"
            var color = true
            runCatching {
                if (!raw.isNullOrBlank() && raw != "null") {
                    val obj = JSONObject(raw)
                    size = obj.optString("size", "sm")
                    color = obj.optBoolean("color", true)
                }
            }
            if (!SIZE_DP_BY_PREF.containsKey(size)) size = "sm"
            buildMenuWindow(size, color)
        }
    }

    private fun buildMenuWindow(curSize: String, curColor: Boolean) {
        val lp = petParams ?: return
        dismissMenu()
        val pad = dp(MENU_PAD_DP)
        val rowH = dp(MENU_ROW_HEIGHT_DP)
        val menuW = dp(MENU_WIDTH_DP)

        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(MENU_CORNER_RADIUS_DP).toFloat()
                setColor(Color.argb(242, 17, 24, 39))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
        }

        fun addRow(label: String, checked: Boolean?, action: () -> Unit) {
            val prefix = if (checked == true) "\u2713  " else "    "
            val tv = TextView(context).apply {
                text = prefix + label
                setTextColor(if (checked == true) Color.rgb(126, 231, 135) else Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(dp(10), 0, dp(6), 0)
                setOnClickListener {
                    // Rimozione rimandata: non distruggere la finestra mentre
                    // sta distribuendo il tocco che ha generato il click.
                    mainHandler.post { dismissMenu() }
                    runCatching { action() }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowH
                )
            }
            rows.addView(tv)
        }

        addRow("Piccola", curSize == "sm") { chooseSize("sm") }
        addRow("Media", curSize == "md") { chooseSize("md") }
        addRow("Grande", curSize == "lg") { chooseSize("lg") }
        addRow("Colore", curColor) { chooseColor(!curColor) }
        addRow("Dormi", null) {
            evalJs("window.__jennySleep && window.__jennySleep();")
        }
        addRow("Nascondi", null) { hide() }
        addRow("Apri la chat", null) { openMainApp() }

        val rowCount = 7
        val menuH = rowCount * rowH + pad * 2
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val menuLp = WindowManager.LayoutParams(
            menuW,
            menuH,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Sopra la mascotte, centrato sulla finestra; se non c'è spazio
            // sotto. Batch 8 — sempre dentro la zona UTILE, non solo dentro lo
            // schermo: in landscape o con un cutout laterale il menu non può
            // finire sotto la status bar, la nav bar o dietro un ritaglio.
            var mx = lp.x + (sizePx - menuW) / 2
            val minMx = usableLeft + dp(MENU_PAD_DP)
            mx = mx.coerceAtLeast(minMx)
                .coerceAtMost(max(minMx, usableRight - menuW - dp(MENU_PAD_DP)))
            var my = lp.y - menuH - dp(MENU_GAP_DP)
            if (my < usableTop + dp(MENU_GAP_DP)) {
                my = lp.y + sizePx + dp(MENU_GAP_DP)
            }
            val minMy = usableTop + dp(MENU_PAD_DP)
            my = my.coerceAtLeast(minMy)
                .coerceAtMost(max(minMy, usableBottom - menuH - dp(MENU_PAD_DP)))
            x = mx
            y = my
        }
        runCatching { wm.addView(rows, menuLp) }
            .onSuccess {
                menuView = rows
                menuOpen = true
                rows.setOnTouchListener { _, ev ->
                    // Tocco fuori dal menu (FLAG_WATCH_OUTSIDE_TOUCH): si chiude.
                    if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) dismissMenu()
                    false
                }
                val auto = Runnable { dismissMenu() }
                menuAutoDismiss = auto
                mainHandler.postDelayed(auto, MENU_AUTO_DISMISS_MS)
            }
            .onFailure { Log.w(TAG, "menu addView failed", it) }
    }

    private fun dismissMenu() {
        menuOpen = false
        menuAutoDismiss?.let { mainHandler.removeCallbacks(it) }
        menuAutoDismiss = null
        menuView?.let { runCatching { wm.removeView(it) } }
        menuView = null
    }

    /** Voce "dimensione": persistita in overlay/size e nella localStorage
     *  della pagina (stessa chiave della SPA letta dal poll di batch-1).
     *  resizeTo riclampa nella zona utile, rimette sul pavimento, riapplica il
     *  peek se parcheggiata e aggiorna la finestra. */
    private fun chooseSize(pref: String, vibrate: Boolean = true) {
        // Haptics "cambio dimensione" solo se la dimensione cambia davvero e
        // solo se il gesto e nato sulla mascotte: la SPA Impostazioni (batch 8)
        // passa vibrate=false. `this.` e necessario: il parametro omonimo
        // nasconde il metodo vibrate(...) dentro questa funzione.
        if (vibrate && pref != readSizePref()) this.vibrate(VIBRATE_SIZE_MS)
        overlayPrefs().edit().putString(PREFS_SIZE, pref).apply()
        evalJs("try{localStorage.setItem('jenny-mascotte-size', '$pref');}catch(e){}")
        val newPx = dp(SIZE_DP_BY_PREF[pref] ?: DEFAULT_SIZE_DP)
        resizeTo(newPx)
        persistState()
    }

    /** Voce "colore": toggle delle sprite -color; persistito in overlay/color
     *  e nella localStorage della pagina. La posa corrente viene ricaricata
     *  (life-style: se sta dormendo non si sveglia). */
    private fun chooseColor(on: Boolean) {
        overlayPrefs().edit().putBoolean(PREFS_COLOR, on).apply()
        evalJs(
            "try{localStorage.setItem('jenny-mascotte-color', '" +
                (if (on) "1" else "0") + "');}catch(e){}"
        )
        evalJs("window.__jennyRefreshArt && window.__jennyRefreshArt();")
    }

    // ------------------------------------------------------------- umore

    /** Solleva l'umore dopo un evento di rinforzo (gesto, interazione,
     *  carica): il momento dell'evento viene ricordato e la deriva
     *  MOOD_DECAY_MS riportera da sola l'umore a NEUTRAL. */
    private fun raiseMood(level: Int) {
        if (level <= mood) return
        mood = level
        lastMoodEventMs = System.currentTimeMillis()
        maybePushMood()
    }

    /** Abbassa l'umore per una condizione ambientale persistente (batteria
     *  bassa, risparmio energetico). Un rinforzo recente (interazione piu
     *  giovane di MOOD_DECAY_MS) ha la precedenza: un poke durante la carica
     *  bassa resta comunque un attimo di felicita. */
    private fun lowerMood(level: Int) {
        val now = System.currentTimeMillis()
        if (lastMoodEventMs != 0L && now - lastMoodEventMs < MOOD_DECAY_MS) return
        if (mood > level) {
            mood = level
            maybePushMood()
        }
    }

    /** Ricalcola l'umore: applica la deriva verso NEUTRAL e, solo quando non
     *  c'e un rinforzo recente, le condizioni ambientali persistenti (notte
     *  fonda + mascotte lasciata in pace → assonnata). Chiamato dal timer
     *  periodico di batch 6 e dai punti di transizione (batteria, curiosita). */
    private fun recomputeMood() {
        val now = System.currentTimeMillis()
        var m = mood
        if (lastMoodEventMs != 0L && now - lastMoodEventMs >= MOOD_DECAY_MS) {
            m = MOOD_NEUTRAL
            lastMoodEventMs = 0L
        }
        val reinforced = lastMoodEventMs != 0L && now - lastMoodEventMs < MOOD_DECAY_MS
        if (!reinforced) {
            // Condizioni ambientali persistenti (nessun rinforzo recente):
            // batteria bassa/risparmio → stanca; notte fonda lasciata in pace
            // → assonnata (comunque la pagina da sola dorme già); la carica
            // invece è un rinforzo continuo e tiene l'umore su HAPPY finché
            // il cavo è collegato (ma non vince sulla notte).
            if (lowBattery || powerSaveMode) m = min(m, MOOD_TIRED)
            val hour = localHour(now)
            val night = hour >= MOOD_LATE_NIGHT_START || hour < MOOD_LATE_NIGHT_END
            val nightIdle = night && lastInteractionMs != 0L &&
                now - lastInteractionMs >= MOOD_LONG_IDLE_MS
            if (nightIdle) m = min(m, MOOD_SLEEPY)
            if (charging && !nightIdle && !screenOff && m < MOOD_HAPPY) {
                m = MOOD_HAPPY
                lastMoodEventMs = now
            }
        }
        if (m != mood) {
            mood = m
            maybePushMood()
        }
    }

    /** Ora locale 0-23 (per la fascia notturna); 12 se il Calendar fallisce. */
    private fun localHour(now: Long): Int =
        try {
            java.util.Calendar.getInstance().apply { timeInMillis = now }
                .get(java.util.Calendar.HOUR_OF_DAY)
        } catch (_: Exception) {
            12
        }

    /** Invia l'umore alla pagina (window.__jennySetMood) solo se cambiato
     *  dall'ultimo invio; la pagina lo usa per orientare pose e ritmi che
     *  esistono gia. Niente a schermo spento: verra rimandato allo screen on
     *  o al prossimo cambio. */
    private fun maybePushMood() {
        if (mood == lastMoodPush) return
        if (petView == null || screenOff) return
        lastMoodPush = mood
        evalJs("window.__jennySetMood && window.__jennySetMood($mood);")
    }

    /** Timer periodico di batch 6: ricalcolo umore/deriva a ogni tick, poll
     *  smart hide ogni SMART_HIDE_POLL_STEPS tick. Costo trascurabile; si
     *  riprogramma da solo finche l'overlay e vivo (teardown lo rimuove). */
    private fun scheduleBatch6Tick() {
        if (batch6TickRunnable != null) return
        val run = Runnable {
            batch6TickRunnable = null
            if (petView == null) return@Runnable
            recomputeMood()
            maybePushMood()
            batch6TickCount++
            if (batch6TickCount % SMART_HIDE_POLL_STEPS == 0) smartHidePoll()
            scheduleBatch6Tick()
        }
        batch6TickRunnable = run
        mainHandler.postDelayed(run, BATCH6_TICK_MS)
    }

    // -------------------------------------------------- smart hide (batch 6)

    /** Poll dello smart hide (chiamato dal timer periodico). Vedi la nota nel
     *  companion: l'audio attivo e l'unico segnale osservabile SENZA nuovi
     *  permessi per "un'app/video a schermo intero e davanti". Ritirata solo
     *  da riposo e se l'utente non sta giocando con la mascotte; mai quando
     *  l'app host e in primo piano (musica in sottofondo mentre si usa Jenny)
     *  e mai a batteria scarsa / risparmio energetico. */
    private fun smartHidePoll() {
        if (!smartHidePref() || overlayHostForeground) return
        if (petView == null || petParams == null || screenOff) return
        if (energySaveActive()) return
        val audio = runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull() ?: return
        val mediaOn = audio.isMusicActive
        if (mediaOn && !smartHideMediaActive) {
            smartHideMediaActive = true
            Log.i(TAG, "smart hide: media attivo — mascotte in ritiro")
        } else if (!mediaOn && smartHideMediaActive) {
            smartHideMediaActive = false
            Log.i(TAG, "smart hide: media finito — mascotte di nuovo visibile")
            smartHidden = false
            syncRetreatState()
            return
        }
        if (smartHideMediaActive && !smartHidden && phase == Phase.IDLE &&
            !dragCommitted && !touchActive && !menuOpen &&
            glideRunnable == null && settleRunnable == null
        ) {
            val quiet = System.currentTimeMillis() - lastInteractionMs
            if (lastInteractionMs != 0L && quiet < SMART_HIDE_QUIET_MS) return
            smartHidden = true
            syncRetreatState()
        }
    }

    /** Ritirata unificata (batch 7): la mascotte si ritira quando la smart
     *  hide la vuole ritirata (`smartHidden`) **oppure** quando la tastiera di
     *  sistema e a video (`overlayImeVisible`, spinta dalla MainActivity). Le
     *  due cause sono indipendenti: alzare l'app host non annulla piu una
     *  ritirata da IME, e viceversa.
     *
     *  Un solo stato della view, `retreatApplied`, tiene traccia di cio che e
     *  applicato: WebView GONE + finestra non toccabile (FLAG_NOT_TOUCHABLE).
     *  Niente teardown ne preferenza "hidden" persistente: quando entrambe le
     *  cause cadono la mascotte riappare senza ricaricare nulla, e il ritorno
     *  a riposo avviene solo su una vera transizione (non a ogni chiamata). */
    private fun syncRetreatState() {
        val view = petView ?: return
        val lp = petParams ?: return
        val hidden = smartHidden || overlayImeVisible
        if (hidden == retreatApplied) return
        val wasHidden = retreatApplied
        retreatApplied = hidden
        if (hidden) {
            stopGlide()
            settleRunnable?.let { mainHandler.removeCallbacks(it) }
            settleRunnable = null
            cancelPendingPoke()
            view.visibility = View.GONE
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            view.visibility = View.VISIBLE
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { wm.updateViewLayout(view, lp) }
        if (!hidden && wasHidden && phase == Phase.IDLE) {
            startSit()
            scheduleCuriosity()
        }
    }

    /** L'app host e passata in primo piano / in background. In primo piano la
     *  mascotte non si ritira (l'utente sta usando Jenny, magari con la
     *  musica in sottofondo) e un'eventuale ritirata della smart hide si
     *  annulla. Il valore vive nel companion: onResume puo correre prima che
     *  il controller esista (il service crea l'overlay dopo). */
    fun setHostForeground(foreground: Boolean) {
        overlayHostForeground = foreground
        if (foreground && petView != null) {
            // Batch 7: si annulla solo la ritirata della smart hide; una
            // ritirata dovuta alla tastiera (overlayImeVisible) resta valida.
            smartHidden = false
            syncRetreatState()
        }
    }

    /** La tastiera di sistema (IME) e visibile. Stesso schema di
     *  setHostForeground: il valore vive nel companion, cosi un controller nato
     *  mentre l'IME e gia su parte gia ritirato. La mascotte si ritira durante
     *  la digitazione (non deve coprire tasti ne barra di composizione) e torna
     *  quando l'IME sparisce; la MainActivity lo riporta a false anche in
     *  onPause. */
    fun setImeVisible(visible: Boolean) {
        overlayImeVisible = visible
        syncRetreatState()
    }

    // ------------------------------------------------------------- curiosità

    /** Pianifica il prossimo controllo dei micro-movimenti autonomi. Chiamata
     *  a ogni interazione e a fine movimento; mai più di un timer attivo. */
    private fun scheduleCuriosity() {
        if (petView == null) return
        if (curiousRunnable != null) return
        val span = (CURIOUS_MAX_MS - CURIOUS_MIN_MS).toDouble()
        val delay = CURIOUS_MIN_MS + (Math.random() * span).toLong()
        val run = Runnable {
            curiousRunnable = null
            maybeCuriousMove()
        }
        curiousRunnable = run
        mainHandler.postDelayed(run, delay)
    }

    /** Decide se fare un micro-movimento adesso. Requisiti: a riposo (idle,
     *  nessuna transizione di posa in corso), nessun dito sullo schermo,
     *  niente risparmio energetico, pagina non addormentata. */
    private fun maybeCuriousMove() {
        val view = petView ?: return
        if (petParams == null) { scheduleCuriosity(); return }
        // Batch 8 — ritirata (smart hide o IME): niente giretti invisibili; il
        // timer resta vivo e riprova quando la mascotte torna visibile.
        if (isRetreated()) { scheduleCuriosity(); return }
        if (phase != Phase.IDLE || settleRunnable != null || dragCommitted || touchActive) {
            scheduleCuriosity()
            return
        }
        if (energySaveActive()) { scheduleCuriosity(); return }
        // Batch 6 — una mascotte stanca/assonnata non fa giretti autonomi
        // (lo specchia la pagina: pose e ritmi piu quieti).
        recomputeMood()
        if (mood < MOOD_NEUTRAL) { scheduleCuriosity(); return }
        // Mai a ridosso di un'interazione dell'utente: requisito minimo di
        // quiete (CURIOUS_MIN_MS) prima di un movimento autonomo.
        val quiet = System.currentTimeMillis() - lastInteractionMs
        if (quiet < CURIOUS_MIN_MS) { scheduleCuriosity(); return }
        view.evaluateJavascript(
            "(window.__jennySleeping?window.__jennySleeping():false)"
        ) { raw ->
            if (petView != null && petParams != null && raw == "false") {
                startCuriousMove()
            }
            scheduleCuriosity()
        }
    }

    /** Avvia il micro-movimento: se parcheggiata un piccolo "sguardo" fuori e
     *  subito indietro (resta in peek, art-safe); altrimenti una breve
     *  camminata sul pavimento dentro i limiti utili, che non finisce mai
     *  parcheggiata (con autoPark=false nessun moto che si conclude in park). */
    private fun startCuriousMove() {
        if (petView == null || petParams == null || phase != Phase.IDLE) return
        if (settleRunnable != null || dragCommitted || touchActive || energySaveActive()) return
        val path = ArrayList<Float>()
        var walk = false
        if (parkedSide != 0) {
            if (!autoParkPref()) return // parcheggio disabilitato: non succede
            val edgeX = if (parkedSide < 0) minDockX().toFloat() else maxDockX().toFloat()
            val mid = (posX + edgeX) / 2f
            if (abs(mid - posX) < dp(4)) return
            addGlideSteps(path, posX, mid)
            addGlideSteps(path, mid, posX)
        } else {
            val lo = (minDockX() + dp(4)).toFloat()
            val hi = (maxDockX() - dp(4)).toFloat()
            if (hi <= lo) return
            val spanDp = CURIOUS_WANDER_MAX_DP - CURIOUS_WANDER_MIN_DP
            val dist = dp(CURIOUS_WANDER_MIN_DP + (Math.random() * spanDp).toInt())
            val dir = if (Math.random() < 0.5f) -1f else 1f
            var target = (posX + dir * dist).coerceIn(lo, hi)
            if (abs(target - posX) < dp(12)) {
                target = (posX - dir * dist).coerceIn(lo, hi)
            }
            if (abs(target - posX) < dp(12)) return
            walk = true
            facingLeft = target < posX
            addGlideSteps(path, posX, target)
        }
        if (path.isEmpty()) return
        launchGlide(path, walk)
    }

    private fun addGlideSteps(out: MutableList<Float>, from: Float, to: Float) {
        val steps = 6
        for (i in 1..steps) out.add(from + (to - from) * i / steps)
    }

    /** Muove la finestra lungo il percorso x con passi piccoli e frequenti.
     *  Ogni passo verifica che l'utente non abbia ripreso il controllo e che
     *  il risparmio energetico non sia attivo: al primo "no" si ferma. */
    private fun launchGlide(path: List<Float>, walk: Boolean) {
        val token = ++glideToken
        var index = 0
        val step = object : Runnable {
            override fun run() {
                if (petView == null || glideToken != token) return
                if (phase != Phase.IDLE || touchActive || energySaveActive()) {
                    finishGlide(token)
                    return
                }
                if (walk) applyPose(if (index % 2 == 0) "walk1" else "walk2")
                posX = path[index]
                applyWindow()
                index++
                if (index < path.size) {
                    mainHandler.postDelayed(this, CURIOUS_GLIDE_STEP_MS)
                } else {
                    finishGlide(token)
                }
            }
        }
        glideRunnable = step
        mainHandler.postDelayed(step, CURIOUS_GLIDE_STEP_MS)
    }

    private fun finishGlide(token: Int) {
        if (glideToken != token) return
        glideRunnable?.let { mainHandler.removeCallbacks(it) }
        glideRunnable = null
        if (petView == null) return
        if (phase != Phase.IDLE) { scheduleCuriosity(); return }
        if (parkedSide != 0) {
            // Anche un'interruzione a metà "sguardo" riporta in peek.
            parkToSide(parkedSide)
        } else {
            applyPose("idle")
            posY = floorTopY()
        }
        applyWindow()
        persistState()
        scheduleCuriosity()
    }

    /** Ferma subito un micro-movimento (un tocco ha preso il controllo).
     *  Ripristina la posa di riposo e, se era in corso uno "sguardo" da
     *  parcheggiata, riporta la finestra esattamente in peek. */
    private fun stopGlide() {
        val r = glideRunnable ?: return
        mainHandler.removeCallbacks(r)
        glideRunnable = null
        glideToken++
        if (petView != null && phase == Phase.IDLE) {
            if (parkedSide != 0) {
                parkToSide(parkedSide)
            } else {
                applyPose("idle")
            }
        }
    }

    // --------------------------------------------------------- lancio (batch 5)

    /** "Lancio" orizzontale (batch 5): la mascotte, già sul pavimento, scivola
     *  rapidamente fino al bordo visibile della zona utile lato `side`
     *  (< 0 → sinistra) e lì entra nel normale meccanismo di parcheggio
     *  (peek con sliver minimo se autoPark; altrimenti si ferma al clamp di
     *  bordo, del tutto visibile). Riutilizza lo schema a passi della
     *  curiosità (stessi campi glideRunnable/glideToken: un tocco la ferma,
     *  il risparmio energetico la ferma) con coda dedicata che termina col
     *  parcheggio. Non sposta la finestra fuori dai limiti orizzontali: il
     *  parcheggio lo fa solo applyWindow quando parkedSide != 0. */
    private fun throwToEdge(side: Int) {
        if (petView == null || petParams == null) return
        raiseMood(MOOD_EXCITED)
        showHideTarget(false)
        // Il drag è finito: la mascotte torna a riposo e la corsa al bordo è
        // un movimento volontario da IDLE (come un riposo lento, ma deciso).
        phase = Phase.IDLE
        val target = if (side < 0) minDockX().toFloat() else maxDockX().toFloat()
        posY = floorTopY()
        val distance = abs(target - posX)
        if (distance < dp(1)) {
            // Già sul bordo visibile: entra direttamente nel parcheggio.
            maybeParkAfterSettle()
            startSit()
            return
        }
        facingLeft = target < posX
        val token = ++glideToken
        val steps = 10
        var index = 0
        val step = object : Runnable {
            override fun run() {
                if (petView == null || glideToken != token) return
                if (phase != Phase.IDLE || touchActive || energySaveActive()) {
                    stopThrow(token)
                    return
                }
                index++
                applyPose(if (index % 2 == 0) "walk1" else "walk2")
                posX += (target - posX) / (steps - index + 1)
                applyWindow()
                if (index < steps) {
                    mainHandler.postDelayed(this, CURIOUS_GLIDE_STEP_MS)
                } else {
                    finishThrow(token)
                }
            }
        }
        glideRunnable = step
        mainHandler.postDelayed(step, CURIOUS_GLIDE_STEP_MS)
    }

    /** Ferma una corsa di lancio (tocco o risparmio energetico): la mascotte
     *  resta dov'è, posa di riposo; niente parcheggio forzato. */
    private fun stopThrow(token: Int) {
        if (glideToken != token) return
        val r = glideRunnable
        if (r != null) mainHandler.removeCallbacks(r)
        glideRunnable = null
        glideToken++
        if (petView != null && phase == Phase.IDLE && parkedSide == 0) applyPose("idle")
    }

    /** Fine corsa: la mascotte è sul bordo visibile; entra nel parcheggio
     *  (o resta al clamp se autoPark=false) e si siede. */
    private fun finishThrow(token: Int) {
        if (glideToken != token) return
        val r = glideRunnable
        if (r != null) mainHandler.removeCallbacks(r)
        glideRunnable = null
        glideToken++
        if (petView == null) return
        if (phase != Phase.IDLE) return
        posY = floorTopY()
        maybeParkAfterSettle()
        startSit()
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
            // Batch 8 — ancorato SOTTO la zona utile alta: con un notch/cutout
            // piu alto la ✕ non finisce sotto il ritaglio (intoccabile).
            y = usableTop + dp(HIDE_TARGET_TOP_DP)
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
        // Batch 8 — stessa ancora di showHideTarget(), cosi il tocco combacia.
        val top = usableTop + dp(HIDE_TARGET_TOP_DP)
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
        val minX = minDockX()
        val maxX = maxDockX()
        // Invariante: la finestra resta del tutto dentro la zona utile in
        // orizzontale, per ogni movimento (drag, volo, riposo) — tranne quando
        // è parcheggiata su un bordo: in quel caso può uscire SOLO fino al
        // limite art-safe di peekOffsetPx (>= 1/3 della sprite ancora visibile e
        // sliver minimo toccabile). Nessun altro percorso la porta più fuori.
        val allowed = if (parkedSide != 0) peekOffsetPx(parkedSide) else 0
        val x = posX.roundToInt().coerceIn(minX - allowed, maxX + allowed)
        // In verticale la finestra resta tra la zona utile alta (sotto
        // orologio/notch) e la posizione di riposo sul pavimento (piedi su
        // floorLineY): la mascotte è sempre visibile, senza cambiare la fisica
        // del pavimento.
        val top = usableTop
        val y = posY.roundToInt().coerceIn(top, max(top, floorTopY().toInt().coerceAtLeast(top)))
        if (lp.x != x || lp.y != y) {
            lp.x = x
            lp.y = y
            runCatching { wm.updateViewLayout(petView, lp) }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
