package com.priyanshu.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SectionIndexer
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

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
    private val showFavouriteIcons = true
    // ----------------------------------------------

    private lateinit var prefs: SharedPreferences
    private var apps: List<AppEntry> = emptyList()
    private val iconCache = HashMap<String, Drawable>()

    private lateinit var homeView: LinearLayout
    private lateinit var favList: LinearLayout
    private lateinit var hint: TextView
    private lateinit var letterBar: LinearLayout
    private lateinit var drawerView: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var allList: ListView
    private lateinit var adapter: AppAdapter
    private lateinit var gestures: GestureDetector

    private val letters = listOf("#") + ('A'..'Z').map { it.toString() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ================= lifecycle =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)

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
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        if (drawerView.visibility == View.VISIBLE) closeDrawer()
        // On the home view, Back does nothing (a launcher should not exit).
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (homeView.visibility == View.VISIBLE) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun refresh() {
        loadApps()
        renderFavourites()
        if (::adapter.isInitialized) adapter.filter(searchBox.text.toString())
    }

    // ================= UI =================

    private fun styledText(size: Float, alpha: Float = 1f): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = size
        this.alpha = alpha
        setShadowLayer(8f, 0f, 2f, Color.argb(170, 0, 0, 0))
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        // ---------- home view ----------
        homeView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(48), dp(24))
        }

        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            textSize = 60f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, Color.argb(170, 0, 0, 0))
            setOnClickListener { openAlarms() }
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            setTextColor(Color.WHITE)
            textSize = 16f
            alpha = 0.85f
            setShadowLayer(8f, 0f, 2f, Color.argb(170, 0, 0, 0))
            setOnClickListener { openCalendar() }
        }
        homeView.addView(clock)
        homeView.addView(date)

        homeView.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        favList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homeView.addView(favList)

        homeView.addView(View(this), LinearLayout.LayoutParams(0, 0, 0.35f))

        hint = styledText(13f, 0.6f)
        homeView.addView(hint)

        root.addView(homeView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---------- A-Z bar on the right ----------
        letterBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        for (l in letters) {
            val t = styledText(11f, 0.65f).apply {
                text = l
                gravity = Gravity.CENTER
            }
            letterBar.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        letterBar.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val idx = ((e.y / v.height) * letters.size).toInt().coerceIn(0, letters.size - 1)
                openDrawer(focusSearch = false, letter = letters[idx])
            }
            true
        }
        root.addView(letterBar, FrameLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            topMargin = dp(170)
            bottomMargin = dp(90)
        })

        // ---------- all-apps drawer ----------
        drawerView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 12, 12, 14))
            setPadding(dp(16), dp(12), dp(16), 0)
            visibility = View.GONE
        }
        searchBox = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(Color.argb(140, 255, 255, 255))
            setTextColor(Color.WHITE)
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
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> launch(this@MainActivity.adapter.getItem(pos)) }
            setOnItemLongClickListener { _, _, pos, _ ->
                showAppMenu(this@MainActivity.adapter.getItem(pos)); true
            }
        }
        drawerView.addView(allList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(drawerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
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
        renderFavourites()
    }

    private fun renderFavourites() {
        favList.removeAllViews()
        val favs = favourites()
        var shown = 0
        for (key in favs) {
            val app = apps.find { it.key == key } ?: continue
            val tv = styledText(favouriteTextSp).apply {
                text = app.label
                setPadding(0, dp(9), 0, dp(9))
                isSingleLine = true
                setOnClickListener { launch(app) }
                setOnLongClickListener { showAppMenu(app); true }
            }
            if (showFavouriteIcons) {
                val base = icon(app)
                val d = base.constantState?.newDrawable()?.mutate() ?: base
                d.setBounds(0, 0, dp(34), dp(34))
                tv.setCompoundDrawables(d, null, null, null)
                tv.compoundDrawablePadding = dp(16)
            }
            favList.addView(tv)
            shown++
        }
        hint.text = if (shown == 0)
            "Swipe up for all apps, then long-press an app to add it here"
        else
            "Swipe up: search  •  Swipe down: notifications  •  A–Z: jump"
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
        searchBox.setText("")
        adapter.filter("")
        homeView.visibility = View.GONE
        letterBar.visibility = View.GONE
        drawerView.visibility = View.VISIBLE
        if (letter != null) {
            val pos = adapter.items.indexOfFirst { letterOf(it.label) == letter }
            allList.setSelection(if (pos >= 0) pos else 0)
        } else {
            allList.setSelection(0)
        }
        if (focusSearch) {
            searchBox.requestFocus()
            searchBox.postDelayed({
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        }
    }

    private fun closeDrawer() {
        if (!::drawerView.isInitialized) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
        searchBox.clearFocus()
        drawerView.visibility = View.GONE
        homeView.visibility = View.VISIBLE
        letterBar.visibility = View.VISIBLE
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
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                }
                iv = ImageView(this@MainActivity)
                tv = TextView(this@MainActivity).apply {
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    isSingleLine = true
                    setPadding(dp(16), 0, 0, 0)
                }
                row.addView(iv, LinearLayout.LayoutParams(dp(40), dp(40)))
                row.addView(tv)
                row.tag = Pair(iv, tv)
            }
            val app = items[position]
            iv.setImageDrawable(icon(app))
            tv.text = app.label
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
