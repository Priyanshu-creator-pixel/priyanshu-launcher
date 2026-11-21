package com.priyanshu.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.LayoutAnimationController
import android.view.animation.TranslateAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SectionIndexer
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.hypot

/** One app that can be opened from the launcher. */
data class AppEntry(val label: String, val pkg: String, val cls: String) {
    val key: String get() = "$pkg/$cls"
    fun component(): ComponentName = ComponentName(pkg, cls)
}

/** First letter used for the A-Z index ("#" for numbers and symbols). */
fun letterOf(label: String): String {
    val c = label.trim().firstOrNull()?.uppercaseChar() ?: '#'
    return if (c in 'A'..'Z') c.toString() else "#"
}

/**
 * MULTIVERSE X home screen.
 * Layers, back to front: GPU 3D environment (or 2D effect / phone wallpaper), holographic HUD,
 * 3D orbit of favourite apps, command dock, A-Z bar, app drawer, launch effects.
 */
class MainActivity : Activity() {

    private val maxOrbit = 8
    private val maxDock = 5

    private lateinit var prefs: SharedPreferences
    private var apps: List<AppEntry> = emptyList()
    private val iconCache = HashMap<String, Drawable>()

    private lateinit var look: LauncherTheme
    private var motionOn = true
    private var use3d = true
    private var gfx = GraphicsMode.BALANCED

    private lateinit var bgLayer: FrameLayout
    private var env: EnvView? = null
    private var video: VideoEnvView? = null
    private var fx: FxView? = null
    private lateinit var homeLayer: FrameLayout
    private lateinit var hud: HudView
    private lateinit var orbit: OrbitView
    private lateinit var dock: DockView
    private lateinit var hint: TextView
    private lateinit var letterBar: LinearLayout
    private lateinit var drawerView: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var allList: ListView
    private lateinit var adapter: AppAdapter
    private lateinit var launchFx: LaunchFx
    private lateinit var gestures: GestureDetector

    private var drawerOpen = false
    private var touchOnLetters = false
    private var activeLetter = -1
    private var returnPoint: FloatArray? = null
    private var resumed = false

    private val letters = listOf("#") + ('A'..'Z').map { it.toString() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            iconCache.clear()
            refresh()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val frac = if (level >= 0 && scale > 0) level.toFloat() / scale else 1f
            val wasCharging = hud.charging
            hud.battery = frac
            hud.charging = charging
            env?.renderer?.battery = frac
            env?.renderer?.charging = if (charging) 1f else 0f
            video?.renderer?.charging = if (charging) 1f else 0f
            if (charging && !wasCharging && resumed && motionOn) {
                // charging surge effect from the bottom of the screen
                launchFx.burst(homeLayer.width / 2f, homeLayer.height.toFloat() - dp(60), dp(70).toFloat(), Color.rgb(90, 255, 150))
            }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun dur(ms: Long): Long = if (motionOn) ms else 0L

    // ================= lifecycle =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        motionOn = prefs.getBoolean("motion", true)
        use3d = prefs.getBoolean("use3d", true)
        gfx = GraphicsMode.values().getOrElse(prefs.getInt("gfx", GraphicsMode.BALANCED.ordinal)) { GraphicsMode.BALANCED }
        look = Themes.all[prefs.getInt("theme", 1).coerceIn(0, Themes.all.size - 1)]

        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }

        setContentView(buildUi())
        gestures = GestureDetector(this, SwipeListener())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        setBackground()
        styleUi()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startRendering()
        refresh()
        returnPoint?.let { p ->
            if (motionOn) launchFx.implode(p[0], p[1], p[2], look.accent)
            returnPoint = null
        }
    }

    override fun onPause() {
        resumed = false
        stopRendering()
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) { }
        video?.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeDrawer()
    }

    @Deprecated("Back is handled manually so the launcher never closes itself")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = Rect()
            touchOnLetters = letterBar.visibility == View.VISIBLE &&
                    letterBar.getGlobalVisibleRect(r) &&
                    r.contains(ev.rawX.toInt(), ev.rawY.toInt())
            if (!drawerOpen && motionOn) { env?.ripple(ev.x, ev.y); video?.ripple(ev.x, ev.y) }
        }
        if (!drawerOpen && !touchOnLetters) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun startRendering() {
        env?.start()
        video?.start()
        fx?.running = motionOn
        hud.running = true
        orbit.running = true
        dock.running = motionOn
    }

    private fun stopRendering() {
        env?.stop()
        video?.stop()
        fx?.running = false
        hud.running = false
        orbit.running = false
        dock.running = false
    }

    private fun refresh() {
        loadApps()
        renderHome()
        if (::adapter.isInitialized) adapter.filter(searchBox.text.toString())
    }

    // ================= UI =================

    private fun buildUi(): View {
        val root = FrameLayout(this)
        bgLayer = FrameLayout(this)
        root.addView(bgLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = FrameLayout(this).apply { clipChildren = false }
        content.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---------- home ----------
        homeLayer = FrameLayout(this).apply { clipChildren = false }
        orbit = OrbitView(this).apply {
            onItemClick = { item, x, y, size -> launch(item.app, this, x, y, size) }
            onItemLongClick = { item -> showAppMenu(item.app) }
            onEmptyLongPress = { showHomeMenu() }
        }
        homeLayer.addView(orbit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(190); bottomMargin = dp(120)
        })
        hud = HudView(this).apply {
            onClock = { openAlarms() }
            onDate = { openCalendar() }
            onBattery = { startSafely(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }
        }
        homeLayer.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190), Gravity.TOP))
        hint = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        homeLayer.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dp(98)
        })
        dock = DockView(this).apply {
            onItemClick = { item, x, y, size -> launch(item.app, this, x, y, size) }
            onItemLongClick = { item -> showAppMenu(item.app) }
            onEmptyClick = {
                Toast.makeText(this@MainActivity, "Open all apps, long-press an app, then \"Add to dock\"", Toast.LENGTH_SHORT).show()
            }
        }
        homeLayer.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88), Gravity.BOTTOM).apply {
            bottomMargin = dp(6)
        })
        content.addView(homeLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---------- A-Z bar ----------
        letterBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        for (l in letters) {
            val tv = TextView(this).apply {
                text = l
                textSize = 10f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            letterBar.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        letterBar.setOnTouchListener { v, e ->
            val idx = ((e.y / v.height) * letters.size).toInt().coerceIn(0, letters.size - 1)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (idx != activeLetter) {
                        activeLetter = idx
                        highlightLetter(idx)
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    resetLetters()
                    activeLetter = -1
                    openDrawer(focusSearch = false, letter = letters[idx])
                }
                MotionEvent.ACTION_CANCEL -> { resetLetters(); activeLetter = -1 }
            }
            true
        }
        content.addView(letterBar, FrameLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            topMargin = dp(200)
            bottomMargin = dp(130)
        })

        // ---------- all-apps drawer ----------
        drawerView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
            visibility = View.GONE
        }
        searchBox = EditText(this).apply {
            hint = "Search apps"
            textSize = 18f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    adapter.filter(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener { _, _, _ ->
                if (adapter.count > 0) launch(adapter.getItem(0), null, 0f, 0f, 0f)
                true
            }
        }
        drawerView.addView(searchBox)
        adapter = AppAdapter()
        allList = ListView(this).apply {
            divider = null
            isFastScrollEnabled = true
            selector = ColorDrawable(Color.TRANSPARENT)
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, view, pos, _ ->
                val loc = IntArray(2)
                view.getLocationInWindow(loc)
                launch(this@MainActivity.adapter.getItem(pos), view, view.width * 0.12f, view.height / 2f, dp(42).toFloat())
            }
            setOnItemLongClickListener { _, view, pos, _ ->
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showAppMenu(this@MainActivity.adapter.getItem(pos)); true
            }
        }
        val rowAnim = AnimationSet(true).apply {
            addAnimation(AlphaAnimation(0f, 1f))
            addAnimation(TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0.8f, Animation.RELATIVE_TO_SELF, 0f))
            duration = 280
            interpolator = DecelerateInterpolator(1.6f)
        }
        allList.layoutAnimation = LayoutAnimationController(rowAnim, 0.07f)
        drawerView.addView(allList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(drawerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        launchFx = LaunchFx(this)
        root.addView(launchFx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    // ================= background =================

    private fun accentFloats(c: Int) = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)

    /** 3D GPU environment, the 2D animated effect, or the phone wallpaper (Classic theme). */
    private fun setBackground() {
        env?.stop()
        video?.stop()
        video?.release()
        bgLayer.removeAllViews()
        env = null
        video = null
        fx = null
        if (look.wallpaper) return
        if (use3d && gfx.video) {
            val v = VideoEnvView(this, "video/reactor_core.mp4")
            v.fps = gfx.fps
            v.useSensors = gfx.sensors
            v.renderer.renderScale = gfx.scale
            v.renderer.accent = accentFloats(look.accent)
            bgLayer.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            video = v
            if (resumed) v.start()
        } else if (use3d) {
            val v = EnvView(this)
            v.mode = gfx
            v.renderer.accent = accentFloats(look.accent)
            v.renderer.day = Calendar.getInstance().let { (it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60f) / 24f }
            bgLayer.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            env = v
            if (resumed) v.start()
        } else {
            val v = FxView(this, look).apply { running = motionOn && resumed }
            bgLayer.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fx = v
        }
    }

    private fun font(style: Int = Typeface.NORMAL): Typeface = Typeface.create(look.font, style)

    private fun styleUi() {
        hud.accent = look.accent
        hud.accent2 = look.accent2
        hud.textColor = look.text
        hud.face = font()
        hud.status = statusLine()
        orbit.accent = look.accent
        orbit.textColor = look.text
        orbit.face = font()
        orbit.motion = motionOn
        dock.accent = look.accent
        hint.setTextColor(withAlpha(look.text, 0.55f))
        hint.typeface = font()
        hint.setShadowLayer(8f, 0f, 0f, Color.BLACK)

        for (i in 0 until letterBar.childCount) {
            val tv = letterBar.getChildAt(i) as TextView
            tv.setTextColor(withAlpha(look.text, 0.55f))
            tv.setShadowLayer(6f, 0f, 1f, Color.argb(200, 0, 0, 0))
        }
        val base = if (look.wallpaper) Color.rgb(12, 12, 14) else look.bg[0]
        drawerView.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(base, 0.82f), withAlpha(base, 0.96f)))
        searchBox.setTextColor(look.text)
        searchBox.setHintTextColor(withAlpha(look.text, 0.5f))
        searchBox.backgroundTintList = ColorStateList.valueOf(look.accent)
        searchBox.typeface = font()
        allList.adapter = adapter
        renderHome()
    }

    private fun statusLine(): String {
        val engine = when {
            look.wallpaper -> "WALLPAPER"
            use3d -> gfx.label.uppercase()
            else -> "2D EFFECTS"
        }
        return "${look.name.uppercase()} · $engine"
    }

    private fun applyTheme(index: Int) {
        look = Themes.all[index]
        prefs.edit().putInt("theme", index).apply()
        setBackground()
        styleUi()
        if (motionOn) launchFx.burst(homeLayer.width / 2f, homeLayer.height * 0.5f, dp(90).toFloat(), look.accent)
    }

    private fun showThemePicker() {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        scroll.addView(column)
        var dialog: AlertDialog? = null
        val current = Themes.all.indexOf(look)
        Themes.all.forEachIndexed { i, th ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                val colours = if (th.wallpaper) intArrayOf(Color.rgb(40, 40, 46), Color.rgb(80, 80, 90)) else th.bg
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colours).apply {
                    cornerRadius = dp(16).toFloat()
                    if (i == current) setStroke(dp(3), th.accent) else setStroke(dp(1), withAlpha(th.accent, 0.35f))
                }
                foreground = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 0.35f)), null,
                    GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.WHITE) })
                isClickable = true
            }
            card.addView(TextView(this).apply {
                text = if (i == current) "${th.name}  ✓" else th.name
                textSize = 21f
                setTextColor(th.accent)
                typeface = Typeface.create(th.font, Typeface.BOLD)
                setShadowLayer(12f, 0f, 0f, withAlpha(th.accent, 0.6f))
            })
            card.addView(TextView(this).apply {
                text = if (th.wallpaper) th.tagline else "${th.tagline}  ·  3D reactor in ${th.name} colours"
                textSize = 12f
                setTextColor(withAlpha(th.text, 0.8f))
            })
            card.setOnClickListener {
                dialog?.dismiss()
                if (i != current) applyTheme(i)
            }
            column.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Choose a universe")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showGraphicsPicker() {
        val modes = GraphicsMode.values()
        val names = modes.map {
            val detail = if (it.video) "smooth video engine, ${it.fps} fps" else "live GPU ray-marching, may lag on mid-range phones"
            if (it == gfx) "${it.label} ($detail) ✓" else "${it.label} ($detail)"
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("3D graphics quality")
            .setItems(names) { _, which ->
                gfx = modes[which]
                prefs.edit().putInt("gfx", gfx.ordinal).apply()
                setBackground()
                if (resumed) startRendering()
                hud.status = statusLine()
                Toast.makeText(this, "Graphics: ${gfx.label}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showHomeMenu() {
        val options = arrayOf(
            "Universes (themes)",
            "3D graphics quality: ${gfx.label}",
            if (use3d) "Switch to 2D effects (lighter)" else "Switch to 3D environment",
            if (motionOn) "Turn animations off (saves battery)" else "Turn animations on",
            "Change wallpaper (Classic universe)"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("MULTIVERSE X")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showThemePicker()
                    1 -> showGraphicsPicker()
                    2 -> {
                        use3d = !use3d
                        prefs.edit().putBoolean("use3d", use3d).apply()
                        setBackground()
                        if (resumed) startRendering()
                        hud.status = statusLine()
                    }
                    3 -> {
                        motionOn = !motionOn
                        prefs.edit().putBoolean("motion", motionOn).apply()
                        styleUi()
                        if (resumed) startRendering()
                    }
                    4 -> startSafely(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))
                }
            }
            .show()
    }

    private fun highlightLetter(idx: Int) {
        for (i in 0 until letterBar.childCount) {
            val tv = letterBar.getChildAt(i) as TextView
            val dist = abs(i - idx)
            val scale = when (dist) { 0 -> 2.3f; 1 -> 1.6f; 2 -> 1.25f; else -> 1f }
            val shift = when (dist) { 0 -> -dp(34); 1 -> -dp(20); 2 -> -dp(8); else -> 0 }
            tv.setTextColor(if (dist == 0) look.accent else withAlpha(look.text, if (dist <= 2) 0.9f else 0.55f))
            tv.animate().scaleX(scale).scaleY(scale).translationX(shift.toFloat()).setDuration(dur(140)).start()
        }
    }

    private fun resetLetters() {
        for (i in 0 until letterBar.childCount) {
            val tv = letterBar.getChildAt(i) as TextView
            tv.setTextColor(withAlpha(look.text, 0.55f))
            tv.animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(dur(220)).start()
        }
    }

    // ================= apps =================

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val found = pm.queryIntentActivities(intent, 0)
        apps = found
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.pkg != packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun icon(app: AppEntry): Drawable = iconCache.getOrPut(app.key) {
        try { packageManager.getActivityIcon(app.component()) } catch (_: Exception) { packageManager.defaultActivityIcon }
    }

    /** A separate copy of an icon, so home-screen fading never changes the drawer's icons. */
    private fun iconCopy(app: AppEntry): Drawable {
        val base = icon(app)
        return base.constantState?.newDrawable(resources)?.mutate() ?: base
    }

    /** Opens the app at once; the energy burst plays at the same time and never delays it. */
    private fun launch(app: AppEntry, source: View?, x: Float, y: Float, size: Float) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        var screenX = x
        var screenY = y
        if (source != null) {
            val loc = IntArray(2)
            source.getLocationInWindow(loc)
            screenX = loc[0] + x
            screenY = loc[1] + y
        }
        val opts = if (source != null && size > 0f) {
            ActivityOptions.makeScaleUpAnimation(source, (x - size / 2).toInt(), (y - size / 2).toInt(), size.toInt(), size.toInt()).toBundle()
        } else null
        try {
            startActivity(i, opts)
            if (motionOn && size > 0f) {
                launchFx.burst(screenX, screenY, size, look.accent)
                returnPoint = floatArrayOf(screenX, screenY, size)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open ${app.label}", Toast.LENGTH_SHORT).show()
        }
        if (drawerOpen) closeDrawer()
    }

    // ================= home: orbit + dock =================

    private fun keys(pref: String): MutableList<String> =
        (prefs.getString(pref, "") ?: "").split("|").filter { it.isNotBlank() }.toMutableList()

    private fun saveKeys(pref: String, list: List<String>) {
        prefs.edit().putString(pref, list.joinToString("|")).apply()
        renderHome()
    }

    private fun renderHome() {
        if (!::orbit.isInitialized || !::look.isInitialized) return
        val orbitApps = keys("favs").mapNotNull { k -> apps.find { it.key == k } }.take(maxOrbit)
        orbit.items = orbitApps.map { HomeItem(it, iconCopy(it)) }
        val dockKeys = keys("dock")
        dock.items = List(maxDock) { i ->
            dockKeys.getOrNull(i)?.let { k -> apps.find { it.key == k } }?.let { HomeItem(it, iconCopy(it)) }
        }
        hint.text = if (orbitApps.isEmpty())
            "SWIPE UP: ALL APPS  ·  LONG-PRESS SPACE: UNIVERSES & SETTINGS"
        else
            "DRAG THE ORBIT  ·  SWIPE UP: ALL APPS  ·  LONG-PRESS SPACE: SETTINGS"
    }

    private fun showAppMenu(app: AppEntry) {
        val favs = keys("favs")
        val dockList = keys("dock")
        val inOrbit = favs.contains(app.key)
        val inDock = dockList.contains(app.key)
        val options = mutableListOf<String>()
        options += if (inOrbit) "Remove from orbit" else "Add to orbit"
        if (inOrbit) { options += "Move earlier in orbit"; options += "Move later in orbit" }
        options += if (inDock) "Remove from dock" else "Add to dock"
        options += "App info"
        options += "Uninstall"

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Add to orbit" -> {
                        if (favs.size >= maxOrbit) {
                            Toast.makeText(this, "The orbit holds $maxOrbit apps. Remove one first.", Toast.LENGTH_SHORT).show()
                        } else {
                            favs.add(app.key); saveKeys("favs", favs)
                            Toast.makeText(this, "${app.label} added to orbit", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "Remove from orbit" -> { favs.remove(app.key); saveKeys("favs", favs) }
                    "Move earlier in orbit" -> {
                        val i = favs.indexOf(app.key)
                        if (i > 0) { favs.removeAt(i); favs.add(i - 1, app.key); saveKeys("favs", favs) }
                    }
                    "Move later in orbit" -> {
                        val i = favs.indexOf(app.key)
                        if (i >= 0 && i < favs.size - 1) { favs.removeAt(i); favs.add(i + 1, app.key); saveKeys("favs", favs) }
                    }
                    "Add to dock" -> {
                        if (dockList.size >= maxDock) {
                            Toast.makeText(this, "The dock holds $maxDock apps. Remove one first.", Toast.LENGTH_SHORT).show()
                        } else {
                            dockList.add(app.key); saveKeys("dock", dockList)
                            Toast.makeText(this, "${app.label} added to dock", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "Remove from dock" -> { dockList.remove(app.key); saveKeys("dock", dockList) }
                    "App info" -> startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                    "Uninstall" -> startSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                }
            }
            .show()
    }

    // ================= drawer =================

    private fun openDrawer(focusSearch: Boolean, letter: String? = null) {
        if (drawerOpen) return
        drawerOpen = true
        searchBox.setText("")
        adapter.filter("")

        homeLayer.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(dur(220))
            .withEndAction { if (drawerOpen) homeLayer.visibility = View.INVISIBLE }.start()
        letterBar.animate().alpha(0f).setDuration(dur(180))
            .withEndAction { if (drawerOpen) letterBar.visibility = View.GONE }.start()

        drawerView.visibility = View.VISIBLE
        drawerView.alpha = 1f
        drawerView.translationY = 0f
        if (motionOn && drawerView.isAttachedToWindow && drawerView.width > 0) {
            // portal: the drawer opens as an expanding circle from the bottom centre
            val cx = drawerView.width / 2
            val cy = drawerView.height
            val reveal = ViewAnimationUtils.createCircularReveal(drawerView, cx, cy, 0f,
                hypot(drawerView.width.toDouble(), drawerView.height.toDouble()).toFloat())
            reveal.duration = 420
            reveal.interpolator = DecelerateInterpolator(1.8f)
            reveal.start()
            env?.ripple(drawerView.width / 2f, drawerView.height.toFloat())
            video?.ripple(drawerView.width / 2f, drawerView.height.toFloat())
        }

        if (letter != null) {
            val pos = adapter.items.indexOfFirst { letterOf(it.label) == letter }
            allList.setSelection(if (pos >= 0) pos else 0)
        } else {
            allList.setSelection(0)
        }
        if (motionOn) allList.scheduleLayoutAnimation()

        if (focusSearch) {
            searchBox.requestFocus()
            searchBox.postDelayed({
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }
    }

    private fun closeDrawer() {
        if (!::drawerView.isInitialized || !drawerOpen) return
        drawerOpen = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
        searchBox.clearFocus()

        drawerView.animate().alpha(0f).translationY(dp(80).toFloat()).setDuration(dur(240))
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!drawerOpen) drawerView.visibility = View.GONE }.start()
        homeLayer.visibility = View.VISIBLE
        homeLayer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(dur(380))
            .setInterpolator(DecelerateInterpolator(2f)).start()
        letterBar.visibility = View.VISIBLE
        letterBar.animate().alpha(1f).setDuration(dur(380)).start()
    }

    // ================= system actions =================

    private fun startSafely(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Not available on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAlarms() = startSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    private fun openCalendar() = startSafely(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))

    private fun expandNotifications() {
        try {
            val service = getSystemService("statusbar")
            val cls = Class.forName("android.app.StatusBarManager")
            cls.getMethod("expandNotificationsPanel").invoke(service)
        } catch (_: Exception) {
            // Some phones block this; nothing else to do.
        }
    }

    // ================= gestures =================

    private inner class SwipeListener : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val dy = e2.y - start.y
            val dx = e2.x - start.x
            if (abs(dy) > abs(dx) && abs(dy) > dp(80)) {
                if (dy < 0) openDrawer(focusSearch = true) else expandNotifications()
                return true
            }
            return false
        }
    }

    // ================= all-apps list =================

    inner class AppAdapter : BaseAdapter(), SectionIndexer {
        var items: List<AppEntry> = emptyList()
            private set
        private var sectionNames: List<String> = emptyList()
        private var sectionStarts: List<Int> = emptyList()

        fun filter(query: String) {
            val q = query.trim()
            items = if (q.isEmpty()) apps else apps.filter { it.label.contains(q, ignoreCase = true) }
            val names = ArrayList<String>()
            val starts = ArrayList<Int>()
            items.forEachIndexed { i, a ->
                val l = letterOf(a.label)
                if (names.isEmpty() || names.last() != l) { names.add(l); starts.add(i) }
            }
            sectionNames = names
            sectionStarts = starts
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): AppEntry = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val iv: ImageView
            val tv: TextView
            if (convertView is LinearLayout && convertView.tag is Pair<*, *>) {
                row = convertView
                @Suppress("UNCHECKED_CAST")
                val holder = convertView.tag as Pair<ImageView, TextView>
                iv = holder.first; tv = holder.second
            } else {
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = RippleDrawable(ColorStateList.valueOf(withAlpha(look.accent, 0.30f)), null,
                        GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) })
                }
                iv = ImageView(this@MainActivity)
                tv = TextView(this@MainActivity).apply {
                    textSize = 18f
                    isSingleLine = true
                    setPadding(dp(16), 0, 0, 0)
                }
                row.addView(iv, LinearLayout.LayoutParams(dp(42), dp(42)))
                row.addView(tv)
                row.tag = Pair(iv, tv)
            }
            val app = items[position]
            iv.setImageDrawable(icon(app))
            tv.text = app.label
            tv.setTextColor(look.text)
            tv.typeface = font()
            return row
        }

        override fun getSections(): Array<Any> = sectionNames.map { it as Any }.toTypedArray()
        override fun getPositionForSection(sectionIndex: Int): Int = sectionStarts.getOrElse(sectionIndex) { 0 }
        override fun getSectionForPosition(position: Int): Int {
            var s = 0
            for (i in sectionStarts.indices) if (sectionStarts[i] <= position) s = i
            return s
        }
    }
}
