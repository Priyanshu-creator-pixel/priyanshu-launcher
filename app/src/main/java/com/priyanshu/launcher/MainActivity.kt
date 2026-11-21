package com.priyanshu.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
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
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
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
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

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

class MainActivity : Activity() {

    // ---------- settings you can change ----------
    private val maxFavourites = 8
    private val favouriteTextSp = 30f
    // ----------------------------------------------

    private lateinit var prefs: SharedPreferences
    private var apps: List<AppEntry> = emptyList()
    private val iconCache = HashMap<String, Drawable>()

    private lateinit var look: LauncherTheme
    private var motionOn = true
    private var showIcons = true

    private lateinit var bgLayer: FrameLayout
    private var fx: FxView? = null
    private lateinit var homeView: LinearLayout
    private lateinit var clock: TextClock
    private lateinit var date: TextClock
    private lateinit var favList: LinearLayout
    private lateinit var hint: TextView
    private lateinit var letterBar: LinearLayout
    private lateinit var drawerView: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var allList: ListView
    private lateinit var adapter: AppAdapter
    private lateinit var gestures: GestureDetector

    private var drawerOpen = false
    private var touchOnLetters = false
    private var activeLetter = -1
    private var clockAnim: ValueAnimator? = null
    private var clockShader: LinearGradient? = null
    private var clockShaderWidth = 0
    private val shaderMatrix = Matrix()

    private val letters = listOf("#") + ('A'..'Z').map { it.toString() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            iconCache.clear()
            refresh(animate = false)
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** Animation length, or 0 when motion is switched off. */
    private fun dur(ms: Long): Long = if (motionOn) ms else 0L

    // ================= lifecycle =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        motionOn = prefs.getBoolean("motion", true)
        showIcons = prefs.getBoolean("icons", true)
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

        setBackgroundEffect(animated = false, cx = 0, cy = 0)
        styleUi()
        refresh(animate = true)
    }

    override fun onResume() {
        super.onResume()
        fx?.running = motionOn
        startClockAnimation()
        if (!drawerOpen) refresh(animate = true) else refresh(animate = false)
    }

    override fun onPause() {
        fx?.running = false
        clockAnim?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    /** Pressing Home while the launcher is open returns to the home view. */
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
        }
        if (!drawerOpen && !touchOnLetters) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun refresh(animate: Boolean) {
        loadApps()
        renderFavourites(animate)
        if (::adapter.isInitialized) adapter.filter(searchBox.text.toString())
    }

    // ================= UI =================

    private fun buildUi(): View {
        val root = FrameLayout(this)

        bgLayer = FrameLayout(this)
        root.addView(bgLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = FrameLayout(this).apply { clipChildren = false }
        content.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---------- home view ----------
        homeView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(56), dp(24))
            isLongClickable = true
            setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showHomeMenu(); true
            }
        }

        clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 68f
            setOnClickListener { pulse(this); openAlarms() }
        }
        date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            textSize = 16f
            letterSpacing = 0.08f
            setOnClickListener { pulse(this); openCalendar() }
        }
        homeView.addView(clock)
        homeView.addView(date)
        homeView.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        favList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homeView.addView(favList)
        homeView.addView(View(this), LinearLayout.LayoutParams(0, 0, 0.35f))

        hint = TextView(this).apply { textSize = 12f }
        homeView.addView(hint)

        content.addView(homeView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---------- A-Z bar on the right ----------
        letterBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        for (l in letters) {
            val tv = TextView(this).apply {
                text = l
                textSize = 11f
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
        content.addView(letterBar, FrameLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            topMargin = dp(180)
            bottomMargin = dp(90)
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
                if (adapter.count > 0) launch(adapter.getItem(0))
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
                pulse(view)
                launch(this@MainActivity.adapter.getItem(pos))
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

        content.addView(drawerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    // ================= theme =================

    private fun font(style: Int = Typeface.NORMAL): Typeface = Typeface.create(look.font, style)

    /** Colours and fonts from the current theme applied to every part of the screen. */
    private fun styleUi() {
        val shadow = if (look.wallpaper) Color.argb(170, 0, 0, 0) else withAlpha(look.accent, 0.7f)

        clock.typeface = font()
        clock.setTextColor(look.text)
        clockShader = null
        clock.paint.shader = null
        clock.setShadowLayer(18f, 0f, 0f, shadow)

        date.typeface = font()
        date.setTextColor(withAlpha(look.accent2, 0.9f))
        date.setShadowLayer(8f, 0f, 2f, Color.argb(150, 0, 0, 0))

        hint.setTextColor(withAlpha(look.text, 0.55f))
        hint.typeface = font()

        for (i in 0 until letterBar.childCount) {
            val tv = letterBar.getChildAt(i) as TextView
            tv.setTextColor(withAlpha(look.text, 0.6f))
            tv.setShadowLayer(6f, 0f, 1f, Color.argb(160, 0, 0, 0))
        }

        val base = if (look.wallpaper) Color.rgb(12, 12, 14) else look.bg[0]
        drawerView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(base, 0.80f), withAlpha(base, 0.95f))
        )
        searchBox.setTextColor(look.text)
        searchBox.setHintTextColor(withAlpha(look.text, 0.5f))
        searchBox.backgroundTintList = ColorStateList.valueOf(look.accent)
        searchBox.typeface = font()
        searchBox.highlightColor = withAlpha(look.accent, 0.4f)

        allList.adapter = adapter   // rebuild rows with the new colours
        renderFavourites(animate = false)
        startClockAnimation()
    }

    /** Shimmering gradient + breathing glow on the clock. */
    private fun startClockAnimation() {
        clockAnim?.cancel()
        if (!::clock.isInitialized) return
        val glowColour = if (look.wallpaper) Color.argb(170, 0, 0, 0) else withAlpha(look.accent, 0.75f)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                val wdt = clock.width
                if (wdt <= 0) return@addUpdateListener
                if (clockShader == null || clockShaderWidth != wdt) {
                    clockShaderWidth = wdt
                    clockShader = LinearGradient(
                        0f, 0f, wdt.toFloat(), 0f,
                        intArrayOf(look.text, look.accent, look.accent2, look.text),
                        null, Shader.TileMode.MIRROR
                    )
                }
                shaderMatrix.setTranslate(v * wdt * 2f, 0f)
                clockShader?.setLocalMatrix(shaderMatrix)
                clock.paint.shader = clockShader
                val glow = 14f + 14f * (0.5f + 0.5f * sin(v * 4f * PI.toFloat()))
                clock.setShadowLayer(glow, 0f, 0f, glowColour)
                clock.invalidate()
            }
        }
        clockAnim = anim
        if (motionOn) {
            anim.start()
        } else {
            clock.post {
                if (clock.width > 0) {
                    clock.paint.shader = LinearGradient(
                        0f, 0f, clock.width.toFloat(), 0f,
                        look.text, look.accent, Shader.TileMode.CLAMP
                    )
                    clock.invalidate()
                }
            }
        }
    }

    /** Swap the animated background. New theme spreads out in a circle from (cx, cy). */
    private fun setBackgroundEffect(animated: Boolean, cx: Int, cy: Int) {
        val old = fx
        val next = FxView(this, look).apply { running = motionOn }
        bgLayer.addView(next, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fx = next

        if (old == null) return
        if (!animated || !motionOn) { bgLayer.removeView(old); return }

        if (look.wallpaper) {
            // fade the old effect away to reveal the wallpaper
            old.animate().alpha(0f).setDuration(600).withEndAction { bgLayer.removeView(old) }.start()
            return
        }
        next.visibility = View.INVISIBLE
        next.post {
            if (!next.isAttachedToWindow) { bgLayer.removeView(old); return@post }
            val radius = hypot(bgLayer.width.toDouble(), bgLayer.height.toDouble()).toFloat()
            val reveal = ViewAnimationUtils.createCircularReveal(next, cx, cy, 0f, radius)
            reveal.duration = 750
            reveal.interpolator = DecelerateInterpolator(1.5f)
            reveal.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { bgLayer.removeView(old) }
            })
            next.visibility = View.VISIBLE
            reveal.start()
        }
    }

    private fun applyTheme(index: Int) {
        look = Themes.all[index]
        prefs.edit().putInt("theme", index).apply()
        val cx = bgLayer.width / 2
        val cy = (bgLayer.height * 0.35f).toInt()
        setBackgroundEffect(animated = true, cx = cx, cy = cy)
        styleUi()
        // little entrance for the clock
        clock.scaleX = 0.85f; clock.scaleY = 0.85f; clock.alpha = 0f
        clock.pivotX = 0f
        clock.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(dur(650)).setInterpolator(OvershootInterpolator(1.4f)).start()
        renderFavourites(animate = true)
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
                setPadding(dp(20), dp(18), dp(20), dp(18))
                val colours = if (th.wallpaper) intArrayOf(Color.rgb(40, 40, 46), Color.rgb(80, 80, 90)) else th.bg
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colours).apply {
                    cornerRadius = dp(18).toFloat()
                    if (i == current) setStroke(dp(3), th.accent) else setStroke(dp(1), withAlpha(th.accent, 0.35f))
                }
                foreground = RippleDrawable(
                    ColorStateList.valueOf(withAlpha(th.accent, 0.35f)), null,
                    GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.WHITE) }
                )
                isClickable = true
            }
            val title = TextView(this).apply {
                text = if (i == current) "${th.name}  ✓" else th.name
                textSize = 22f
                setTextColor(th.accent)
                typeface = Typeface.create(th.font, Typeface.BOLD)
                setShadowLayer(12f, 0f, 0f, withAlpha(th.accent, 0.6f))
            }
            val sub = TextView(this).apply {
                text = th.tagline
                textSize = 13f
                setTextColor(withAlpha(th.text, 0.8f))
                typeface = Typeface.create(th.font, Typeface.NORMAL)
            }
            val dots = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            for (col in intArrayOf(th.accent, th.accent2, th.text)) {
                val dot = View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) }
                }
                dots.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(8) })
            }
            card.addView(title)
            card.addView(sub)
            card.addView(dots)
            card.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                dialog?.dismiss()
                if (i != current) applyTheme(i)
            }
            column.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

            // cards slide in one after another
            card.alpha = 0f
            card.translationY = dp(40).toFloat()
            card.animate().alpha(1f).translationY(0f)
                .setStartDelay(dur(60L * i)).setDuration(dur(380))
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }

        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Choose a theme")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHomeMenu() {
        val options = arrayOf(
            "Themes",
            if (showIcons) "Hide app icons on home" else "Show app icons on home",
            if (motionOn) "Turn animations off (saves battery)" else "Turn animations on",
            "Change wallpaper (Classic theme)"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Priyanshu Launcher")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showThemePicker()
                    1 -> {
                        showIcons = !showIcons
                        prefs.edit().putBoolean("icons", showIcons).apply()
                        renderFavourites(animate = true)
                    }
                    2 -> {
                        motionOn = !motionOn
                        prefs.edit().putBoolean("motion", motionOn).apply()
                        fx?.running = motionOn
                        styleUi()
                        Toast.makeText(this, if (motionOn) "Animations on" else "Animations off", Toast.LENGTH_SHORT).show()
                    }
                    3 -> startSafely(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))
                }
            }
            .show()
    }

    // ================= small animations =================

    /** Quick press-and-bounce on a view. */
    private fun pulse(v: View) {
        if (!motionOn) return
        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    /** Makes a view shrink while pressed and spring back when released. */
    private fun addPressEffect(v: View) {
        v.setOnTouchListener { view, e ->
            if (motionOn) when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.93f).scaleY(0.93f)
                    .setDuration(110).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                    .setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    private fun highlightLetter(idx: Int) {
        for (i in 0 until letterBar.childCount) {
            val tv = letterBar.getChildAt(i) as TextView
            val dist = abs(i - idx)
            val scale = when (dist) { 0 -> 2.3f; 1 -> 1.6f; 2 -> 1.25f; else -> 1f }
            val shift = when (dist) { 0 -> -dp(34); 1 -> -dp(20); 2 -> -dp(8); else -> 0 }
            tv.setTextColor(if (dist == 0) look.accent else withAlpha(look.text, if (dist <= 2) 0.9f else 0.6f))
            if (dist == 0) tv.setShadowLayer(14f, 0f, 0f, look.accent) else tv.setShadowLayer(6f, 0f, 1f, Color.argb(160, 0, 0, 0))
            tv.animate().scaleX(scale).scaleY(scale).translationX(shift.toFloat())
                .setDuration(dur(140)).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun resetLetters() {
        for (i in 0 until letterBar.childCount) {
            val tv = letterBar.getChildAt(i) as TextView
            tv.setTextColor(withAlpha(look.text, 0.6f))
            tv.setShadowLayer(6f, 0f, 1f, Color.argb(160, 0, 0, 0))
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

    private fun launch(app: AppEntry) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open ${app.label}", Toast.LENGTH_SHORT).show()
        }
        closeDrawer()
    }

    // ================= favourites =================

    private fun favourites(): MutableList<String> =
        (prefs.getString("favs", "") ?: "").split("|").filter { it.isNotBlank() }.toMutableList()

    private fun saveFavourites(list: List<String>) {
        prefs.edit().putString("favs", list.joinToString("|")).apply()
        renderFavourites(animate = true)
    }

    private fun renderFavourites(animate: Boolean) {
        if (!::favList.isInitialized || !::look.isInitialized) return
        favList.removeAllViews()
        val favs = favourites()
        var shown = 0
        for (key in favs) {
            val app = apps.find { it.key == key } ?: continue
            val tv = TextView(this).apply {
                text = app.label
                textSize = favouriteTextSp
                setTextColor(look.text)
                typeface = font()
                setShadowLayer(10f, 0f, 2f, Color.argb(170, 0, 0, 0))
                setPadding(0, dp(9), 0, dp(9))
                isSingleLine = true
                pivotX = 0f
                setOnClickListener { launch(app) }
                setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showAppMenu(app); true
                }
            }
            addPressEffect(tv)
            if (showIcons) {
                val base = icon(app)
                val dr = base.constantState?.newDrawable()?.mutate() ?: base
                dr.setBounds(0, 0, dp(36), dp(36))
                tv.setCompoundDrawables(dr, null, null, null)
                tv.compoundDrawablePadding = dp(16)
            }
            favList.addView(tv)

            if (animate && motionOn) {
                tv.alpha = 0f
                tv.translationX = -dp(60).toFloat()
                tv.animate().alpha(1f).translationX(0f)
                    .setStartDelay(55L * shown).setDuration(480)
                    .setInterpolator(OvershootInterpolator(1.1f)).start()
            }
            shown++
        }
        hint.text = if (shown == 0)
            "Swipe up for all apps, long-press an app to add it here\nLong-press empty space for themes"
        else
            "Swipe up: search  •  Swipe down: notifications\nLong-press empty space: themes & settings"
    }

    private fun showAppMenu(app: AppEntry) {
        val favs = favourites()
        val isFav = favs.contains(app.key)
        val options = mutableListOf<String>()
        if (isFav) {
            options += "Remove from home"
            options += "Move up"
            options += "Move down"
        } else {
            options += "Add to home"
        }
        options += "App info"
        options += "Uninstall"

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Add to home" -> {
                        if (favs.size >= maxFavourites) {
                            Toast.makeText(this, "Home is full ($maxFavourites apps). Remove one first.", Toast.LENGTH_SHORT).show()
                        } else {
                            favs.add(app.key); saveFavourites(favs)
                            Toast.makeText(this, "${app.label} added to home", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "Remove from home" -> { favs.remove(app.key); saveFavourites(favs) }
                    "Move up" -> {
                        val i = favs.indexOf(app.key)
                        if (i > 0) { favs.removeAt(i); favs.add(i - 1, app.key); saveFavourites(favs) }
                    }
                    "Move down" -> {
                        val i = favs.indexOf(app.key)
                        if (i >= 0 && i < favs.size - 1) { favs.removeAt(i); favs.add(i + 1, app.key); saveFavourites(favs) }
                    }
                    "App info" -> startSafely(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                    "Uninstall" -> startSafely(
                        Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
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

        // home zooms back and fades
        homeView.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(dur(220))
            .withEndAction { if (drawerOpen) homeView.visibility = View.INVISIBLE }.start()
        letterBar.animate().alpha(0f).translationX(dp(30).toFloat()).setDuration(dur(200))
            .withEndAction { if (drawerOpen) letterBar.visibility = View.GONE }.start()

        // drawer slides up
        drawerView.visibility = View.VISIBLE
        drawerView.alpha = 0f
        drawerView.translationY = dp(140).toFloat()
        drawerView.animate().alpha(1f).translationY(0f).setDuration(dur(340))
            .setInterpolator(DecelerateInterpolator(2f)).start()

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

        drawerView.animate().alpha(0f).translationY(dp(100).toFloat()).setDuration(dur(240))
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!drawerOpen) drawerView.visibility = View.GONE }.start()

        homeView.visibility = View.VISIBLE
        homeView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(dur(380))
            .setInterpolator(DecelerateInterpolator(2f)).start()
        letterBar.visibility = View.VISIBLE
        letterBar.animate().alpha(1f).translationX(0f).setDuration(dur(380)).start()
        renderFavourites(animate = true)
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

    private fun openCalendar() =
        startSafely(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))

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
                    background = RippleDrawable(
                        ColorStateList.valueOf(withAlpha(look.accent, 0.30f)), null,
                        GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) }
                    )
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

        override fun getPositionForSection(sectionIndex: Int): Int =
            sectionStarts.getOrElse(sectionIndex) { 0 }

        override fun getSectionForPosition(position: Int): Int {
            var s = 0
            for (i in sectionStarts.indices) if (sectionStarts[i] <= position) s = i
            return s
        }
    }
}
