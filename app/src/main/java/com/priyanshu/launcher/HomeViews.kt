package com.priyanshu.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private fun Context.dpf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
private fun Context.spf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** One app shown on the home screen with its real icon. */
class HomeItem(val app: AppEntry, val icon: Drawable)

/** Draws an app icon inside a holographic glass emitter: glow, glass disc, rotating segmented ring, specular arc. */
object HoloIcon {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val dst = RectF()
    private const val CACHE_R = 96f
    private val cache = HashMap<Int, Bitmap>()

    /** Glow and glass disc are drawn once per accent colour into a bitmap, then reused every frame. */
    private fun base(accent: Int): Bitmap = cache.getOrPut(accent) {
        val size = (CACHE_R * 1.7f * 2f).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val r = CACHE_R
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.shader = RadialGradient(cx, cx, r * 1.7f, intArrayOf(withAlpha(accent, 0.38f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, r * 1.7f, q)
        q.shader = RadialGradient(cx - r * 0.3f, cx - r * 0.45f, r * 1.5f,
            intArrayOf(withAlpha(Color.WHITE, 0.20f), withAlpha(accent, 0.14f), withAlpha(Color.BLACK, 0.70f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, r, q)
        bmp
    }

    fun draw(c: Canvas, icon: Drawable?, cx: Float, cy: Float, r: Float, accent: Int, alpha: Float, glow: Float, time: Float) {
        if (r <= 1f) return
        val e = r * 1.7f
        dst.set(cx - e, cy - e, cx + e, cy + e)
        bp.alpha = (255 * alpha * (0.6f + 0.4f * glow)).toInt().coerceIn(0, 255)
        c.drawBitmap(base(accent), null, dst, bp)

        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = r * 0.07f
        p.color = withAlpha(accent, 0.95f * alpha)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        for (k in 0 until 6) c.drawArc(rect, time * 38f + k * 60f, 38f, false, p)
        p.strokeWidth = r * 0.025f
        p.color = withAlpha(accent, 0.45f * alpha)
        c.drawCircle(cx, cy, r * 0.83f, p)
        rect.set(cx - r * 1.18f, cy - r * 1.18f, cx + r * 1.18f, cy + r * 1.18f)
        p.color = withAlpha(accent, 0.35f * alpha)
        for (k in 0 until 3) c.drawArc(rect, -time * 22f + k * 120f, 18f, false, p)

        if (icon != null) {
            val s = r * 1.15f
            icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            icon.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            icon.draw(c)
        }
        p.strokeWidth = r * 0.05f
        p.color = withAlpha(Color.WHITE, 0.32f * alpha)
        rect.set(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f)
        c.drawArc(rect, 205f, 55f, false, p)
        p.style = Paint.Style.FILL
    }
}

/**
 * Favourite apps floating on a tilted 3D orbit around the reactor.
 * Drag sideways to spin the orbit (with momentum), tap to open, long-press for options.
 */
@SuppressLint("ViewConstructor")
class OrbitView(context: Context) : View(context) {

    var items: List<HomeItem> = emptyList()
        set(v) { field = v; invalidate() }
    var accent = Color.CYAN
    var textColor = Color.WHITE
    var face: Typeface = Typeface.DEFAULT
    var motion = true
    var running = false
        set(v) { field = v; lastNs = 0L; invalidate() }

    var onItemClick: ((HomeItem, Float, Float, Float) -> Unit)? = null
    var onItemLongClick: ((HomeItem) -> Unit)? = null
    var onEmptyLongPress: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dash = DashPathEffect(floatArrayOf(context.dpf(6f), context.dpf(9f)), 0f)
    private val oval = RectF()

    private var spin = 0f
    private var vel = 0f
    private var t = 0f
    private var lastNs = 0L

    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var ps = FloatArray(0)
    private var pd = FloatArray(0)
    private var order = IntArray(0)

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastMoveMs = 0L
    private var dragging = false
    private var pressed = -1
    private var longFired = false
    private var pressScale = 1f
    private val handler = Handler(Looper.getMainLooper())
    private val longPress = Runnable {
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val i = pressed
        if (i >= 0 && i < items.size) onItemLongClick?.invoke(items[i]) else onEmptyLongPress?.invoke()
        pressed = -1
    }

    private fun centerX() = width / 2f
    private fun centerY() = height * 0.50f
    private fun radiusX() = width * 0.40f
    private fun radiusY() = min(height * 0.16f, width * 0.22f)
    private fun baseR() = context.dpf(29f)

    private fun layoutItems() {
        val n = items.size
        if (px.size != n) {
            px = FloatArray(n); py = FloatArray(n); ps = FloatArray(n); pd = FloatArray(n); order = IntArray(n)
        }
        for (i in 0 until n) {
            val th = spin + i * 2f * PI.toFloat() / n
            val depth = (cos(th) + 1f) / 2f          // 1 = front (closest)
            px[i] = centerX() + radiusX() * sin(th)
            py[i] = centerY() + radiusY() * cos(th)
            ps[i] = 0.62f + 0.48f * depth
            pd[i] = depth
        }
        val idx = (0 until n).sortedBy { pd[it] }
        for (k in 0 until n) order[k] = idx[k]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now
        t += dt
        if (!dragging) {
            spin += vel * dt + (if (motion) 0.045f * dt else 0f)
            vel *= exp(-2.2f * dt)
        }
        layoutItems()

        val cx = centerX()
        val cy = centerY()
        // orbit rails
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dpf(1.2f)
        paint.pathEffect = dash
        paint.color = withAlpha(accent, 0.35f)
        oval.set(cx - radiusX(), cy - radiusY(), cx + radiusX(), cy + radiusY())
        canvas.drawOval(oval, paint)
        paint.pathEffect = null
        paint.color = withAlpha(accent, 0.14f)
        oval.set(cx - radiusX() * 1.1f, cy - radiusY() * 1.12f, cx + radiusX() * 1.1f, cy + radiusY() * 1.12f)
        canvas.drawOval(oval, paint)
        // travelling markers on the rail
        paint.style = Paint.Style.FILL
        for (k in 0 until 3) {
            val th = -t * 0.6f + k * 2.094f
            val d = (cos(th) + 1f) / 2f
            paint.color = withAlpha(accent, 0.25f + 0.6f * d)
            canvas.drawCircle(cx + radiusX() * sin(th), cy + radiusY() * cos(th), context.dpf(2f + 2f * d), paint)
        }

        if (items.isEmpty()) {
            textPaint.typeface = face
            textPaint.textSize = context.spf(14f)
            textPaint.color = withAlpha(textColor, 0.75f)
            canvas.drawText("Swipe up, long-press an app,", cx, cy + radiusY() + context.dpf(40f), textPaint)
            canvas.drawText("then tap \"Add to orbit\"", cx, cy + radiusY() + context.dpf(60f), textPaint)
        }

        for (k in order.indices) {
            val i = order[k]
            if (i >= items.size) continue
            val d = pd[i]
            var r = baseR() * ps[i]
            if (i == pressed) r *= pressScale
            val alpha = 0.45f + 0.55f * d
            if (d > 0.8f) {
                // energy tether from the front icon to the core
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = context.dpf(1f)
                paint.shader = LinearGradient(px[i], py[i], cx, cy, withAlpha(accent, 0.5f * (d - 0.8f) * 5f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawLine(px[i], py[i], cx, cy, paint)
                paint.shader = null
            }
            HoloIcon.draw(canvas, items[i].icon, px[i], py[i], r, accent, alpha, 0.4f + 0.6f * d, t + i)
            if (d > 0.42f) {
                textPaint.typeface = face
                textPaint.textSize = context.spf(11f + 2f * d)
                textPaint.color = withAlpha(textColor, ((d - 0.42f) / 0.58f).coerceIn(0f, 1f))
                textPaint.setShadowLayer(context.dpf(4f), 0f, 0f, Color.BLACK)
                val label = items[i].app.label.let { if (it.length > 14) it.take(13) + "…" else it }
                canvas.drawText(label, px[i], py[i] + r * 1.35f + textPaint.textSize, textPaint)
            }
        }
        if (running) postInvalidateDelayed(if (dragging || abs(vel) > 0.05f || pressed >= 0) 16L else 33L)
    }

    private fun hit(x: Float, y: Float): Int {
        for (k in order.indices.reversed()) {
            val i = order[k]
            if (i >= items.size) continue
            if (hypot(x - px[i], y - py[i]) < baseR() * ps[i] * 1.25f) return i
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastMoveMs = e.eventTime
                dragging = false; longFired = false
                pressed = hit(e.x, e.y)
                vel = 0f
                if (pressed >= 0 && motion) animatePress(0.86f)
                handler.postDelayed(longPress, 480)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && abs(dx) > slop && abs(dx) > abs(dy)) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (pressed >= 0) { pressed = -1; pressScale = 1f }
                }
                if (!dragging && abs(dy) > slop) {
                    handler.removeCallbacks(longPress)
                    if (pressed >= 0) { pressed = -1; pressScale = 1f }
                }
                if (dragging) {
                    val step = (e.x - lastX) / radiusX()
                    spin += step
                    val dtMs = (e.eventTime - lastMoveMs).coerceAtLeast(1L)
                    vel = step / (dtMs / 1000f)
                    lastX = e.x
                    lastMoveMs = e.eventTime
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val i = pressed
                if (!dragging && !longFired && i >= 0 && i < items.size) {
                    pressScale = 1f
                    onItemClick?.invoke(items[i], px[i], py[i], baseR() * ps[i] * 2f)
                }
                dragging = false
                pressed = -1
                pressScale = 1f
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                dragging = false; pressed = -1; pressScale = 1f
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun animatePress(target: Float) {
        pressScale = target
        invalidate()
    }
}

/** Glass command dock with up to five apps. */
@SuppressLint("ViewConstructor")
class DockView(context: Context) : View(context) {

    var items: List<HomeItem?> = List(5) { null }
        set(v) { field = v; invalidate() }
    var accent = Color.CYAN
    var running = false
        set(v) { field = v; lastNs = 0L; invalidate() }
    var onItemClick: ((HomeItem, Float, Float, Float) -> Unit)? = null
    var onItemLongClick: ((HomeItem) -> Unit)? = null
    var onEmptyClick: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private var t = 0f
    private var lastNs = 0L
    private var pressed = -1
    private var longFired = false
    private val handler = Handler(Looper.getMainLooper())
    private val longPress = Runnable {
        longFired = true
        val i = pressed
        val item = items.getOrNull(i)
        if (item != null) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onItemLongClick?.invoke(item)
        }
        pressed = -1
        invalidate()
    }

    private fun slotX(i: Int): Float {
        val pad = context.dpf(22f)
        val w = width - 2 * pad
        return pad + w * (i + 0.5f) / 5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now
        t += dt

        val m = context.dpf(12f)
        rect.set(m, context.dpf(8f), width - m, height - context.dpf(6f))
        val rr = context.dpf(22f)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(withAlpha(accent, 0.16f), withAlpha(Color.BLACK, 0.72f)), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, rr, rr, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dpf(1.2f)
        paint.color = withAlpha(accent, 0.55f)
        canvas.drawRoundRect(rect, rr, rr, paint)
        // scanning light along the top edge
        val sx = rect.left + (rect.width()) * ((t * 0.25f) % 1f)
        paint.shader = LinearGradient(sx - context.dpf(60f), 0f, sx + context.dpf(60f), 0f,
            intArrayOf(Color.TRANSPARENT, withAlpha(accent, 0.95f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        paint.strokeWidth = context.dpf(2.2f)
        canvas.drawLine(rect.left + rr, rect.top, rect.right - rr, rect.top, paint)
        paint.shader = null
        // corner brackets
        paint.strokeWidth = context.dpf(2f)
        paint.color = withAlpha(accent, 0.9f)
        val b = context.dpf(14f)
        path.reset()
        path.moveTo(rect.left - context.dpf(4f), rect.top + b); path.lineTo(rect.left - context.dpf(4f), rect.top - context.dpf(4f)); path.lineTo(rect.left + b, rect.top - context.dpf(4f))
        path.moveTo(rect.right + context.dpf(4f), rect.bottom - b); path.lineTo(rect.right + context.dpf(4f), rect.bottom + context.dpf(4f)); path.lineTo(rect.right - b, rect.bottom + context.dpf(4f))
        canvas.drawPath(path, paint)

        val cy = rect.centerY()
        val r = min(context.dpf(24f), rect.height() * 0.36f)
        for (i in 0 until 5) {
            val x = slotX(i)
            val item = items.getOrNull(i)
            if (item != null) {
                val rr2 = if (i == pressed) r * 0.86f else r
                HoloIcon.draw(canvas, item.icon, x, cy, rr2, accent, 1f, 0.7f, t * 0.6f + i)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = context.dpf(1f)
                paint.color = withAlpha(accent, 0.3f)
                paint.pathEffect = DashPathEffect(floatArrayOf(context.dpf(3f), context.dpf(4f)), t * 10f)
                canvas.drawCircle(x, cy, r * 0.8f, paint)
                paint.pathEffect = null
                paint.strokeWidth = context.dpf(1.5f)
                canvas.drawLine(x - r * 0.25f, cy, x + r * 0.25f, cy, paint)
                canvas.drawLine(x, cy - r * 0.25f, x, cy + r * 0.25f, paint)
            }
        }
        paint.style = Paint.Style.FILL
        if (running) postInvalidateDelayed(33)
    }

    private fun slotAt(x: Float): Int {
        for (i in 0 until 5) if (abs(x - slotX(i)) < (width - context.dpf(44f)) / 10f) return i
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = slotAt(e.x); longFired = false
                handler.postDelayed(longPress, 480)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (slotAt(e.x) != pressed) { handler.removeCallbacks(longPress); pressed = -1; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val i = pressed
                if (!longFired && i >= 0) {
                    val item = items.getOrNull(i)
                    if (item != null) onItemClick?.invoke(item, slotX(i), height / 2f, context.dpf(48f))
                    else onEmptyClick?.invoke()
                }
                pressed = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longPress); pressed = -1; invalidate(); return true }
        }
        return super.onTouchEvent(e)
    }
}

/** Holographic clock, date, seconds track, status line and live battery gauge. */
@SuppressLint("ViewConstructor")
class HudView(context: Context) : View(context) {

    var accent = Color.CYAN
    var accent2 = Color.WHITE
    var textColor = Color.WHITE
    var face: Typeface = Typeface.DEFAULT
    var status = ""
    var battery = 1f
    var charging = false
    var running = false
        set(v) { field = v; invalidate() }
    var onClock: (() -> Unit)? = null
    var onDate: (() -> Unit)? = null
    var onBattery: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private val clockRect = RectF()
    private val dateRect = RectF()
    private var batX = 0f
    private var batY = 0f
    private var batR = 0f
    private val start = System.nanoTime()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (System.nanoTime() - start) / 1e9f
        val cal = Calendar.getInstance()
        val is24 = DateFormat.is24HourFormat(context)
        val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", cal).toString()
        val date = DateFormat.format("EEEE  ·  d MMMM", cal).toString().uppercase(Locale.getDefault())
        val cx = width / 2f
        val d = context.dpf(1f)

        // status line + battery gauge (top row)
        batR = 17f * d
        batX = width - 34f * d
        batY = 34f * d
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.MONOSPACE
        text.textSize = context.spf(10f)
        text.color = withAlpha(accent, 0.85f)
        text.setShadowLayer(6f * d, 0f, 0f, withAlpha(accent, 0.6f))
        canvas.drawText(status, 22f * d, 38f * d, text)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(accent, 0.5f + 0.5f * sin(t * 3f))
        canvas.drawCircle(16f * d, 34.5f * d, 2.5f * d, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * d
        paint.color = withAlpha(accent, 0.18f)
        rect.set(batX - batR, batY - batR, batX + batR, batY + batR)
        canvas.drawArc(rect, 135f, 270f, false, paint)
        val batColor = if (battery < 0.15f && !charging) Color.rgb(255, 80, 80) else accent
        paint.color = batColor
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(rect, 135f, 270f * battery.coerceIn(0f, 1f), false, paint)
        if (charging) {
            paint.strokeWidth = 1.5f * d
            paint.color = withAlpha(batColor, 0.8f)
            rect.set(batX - batR - 5 * d, batY - batR - 5 * d, batX + batR + 5 * d, batY + batR + 5 * d)
            for (k in 0 until 4) canvas.drawArc(rect, t * 160f + k * 90f, 30f, false, paint)
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = context.spf(10f)
        text.color = textColor
        canvas.drawText("${(battery * 100).toInt()}${if (charging) "+" else ""}", batX, batY + 3.5f * d, text)

        // holographic clock panel
        val top = 62f * d
        val panelH = 118f * d
        rect.set(cx - width * 0.4f, top, cx + width * 0.4f, top + panelH)
        clockRect.set(rect)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, intArrayOf(withAlpha(accent, 0.10f), withAlpha(accent, 0.02f)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        paint.shader = null
        // scan lines
        paint.color = withAlpha(accent, 0.05f)
        var yy = rect.top
        while (yy < rect.bottom) { canvas.drawRect(rect.left, yy, rect.right, yy + d, paint); yy += 4f * d }
        // moving scan beam
        val beamY = rect.top + rect.height() * ((t * 0.35f) % 1f)
        paint.shader = LinearGradient(0f, beamY - 14 * d, 0f, beamY + 2 * d, intArrayOf(Color.TRANSPARENT, withAlpha(accent, 0.22f)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(rect.left, beamY - 14 * d, rect.right, beamY + 2 * d, paint)
        paint.shader = null
        // corner brackets
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * d
        paint.color = withAlpha(accent, 0.9f)
        val b = 16f * d
        path.reset()
        path.moveTo(rect.left, rect.top + b); path.lineTo(rect.left, rect.top); path.lineTo(rect.left + b, rect.top)
        path.moveTo(rect.right - b, rect.top); path.lineTo(rect.right, rect.top); path.lineTo(rect.right, rect.top + b)
        path.moveTo(rect.left, rect.bottom - b); path.lineTo(rect.left, rect.bottom); path.lineTo(rect.left + b, rect.bottom)
        path.moveTo(rect.right - b, rect.bottom); path.lineTo(rect.right, rect.bottom); path.lineTo(rect.right, rect.bottom - b)
        canvas.drawPath(path, paint)

        // time
        text.typeface = face
        text.textSize = context.spf(66f)
        text.shader = LinearGradient(0f, top + 14 * d, 0f, top + 80 * d, intArrayOf(Color.WHITE, accent), null, Shader.TileMode.CLAMP)
        text.setShadowLayer((14f + 6f * sin(t * 2f)) * d, 0f, 0f, withAlpha(accent, 0.9f))
        canvas.drawText(time, cx, top + 72f * d, text)
        text.shader = null

        // seconds track: 60 ticks, current one lit
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
        val trackL = rect.left + 18 * d
        val trackW = rect.width() - 36 * d
        paint.strokeWidth = 1.5f * d
        for (k in 0 until 60) {
            val x = trackL + trackW * k / 59f
            val lit = k <= sec.toInt()
            paint.color = withAlpha(accent, if (lit) 0.85f else 0.18f)
            val h = if (k % 5 == 0) 7f * d else 4f * d
            canvas.drawLine(x, top + 86 * d, x, top + 86 * d + h, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(trackL + trackW * (sec / 60f), top + 89 * d, 2.2f * d, paint)

        // date
        text.typeface = face
        text.textSize = context.spf(12f)
        text.letterSpacing = 0.22f
        text.color = withAlpha(accent2, 0.95f)
        text.setShadowLayer(6f * d, 0f, 0f, withAlpha(accent, 0.6f))
        canvas.drawText(date, cx, top + panelH - 10 * d, text)
        text.letterSpacing = 0f
        dateRect.set(rect.left, top + panelH - 30 * d, rect.right, top + panelH + 6 * d)

        if (running) postInvalidateDelayed(33)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val inBattery = hypot(e.x - batX, e.y - batY) < batR * 1.8f
        val inDate = dateRect.contains(e.x, e.y)
        val inClock = clockRect.contains(e.x, e.y)
        if (!inBattery && !inDate && !inClock) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when {
                inBattery -> onBattery?.invoke()
                inDate -> onDate?.invoke()
                else -> onClock?.invoke()
            }
        }
        return true
    }
}

/** Short app-launch bursts and return implosions drawn over everything. */
@SuppressLint("ViewConstructor")
class LaunchFx(context: Context) : View(context) {
    private class Burst(val x: Float, val y: Float, val size: Float, val color: Int, val start: Long, val implode: Boolean)
    private val bursts = ArrayList<Burst>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val duration = 480f

    init { isClickable = false; isFocusable = false }

    fun burst(x: Float, y: Float, size: Float, color: Int) {
        bursts.add(Burst(x, y, size, color, System.currentTimeMillis(), false)); invalidate()
    }

    fun implode(x: Float, y: Float, size: Float, color: Int) {
        bursts.add(Burst(x, y, size, color, System.currentTimeMillis(), true)); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val it = bursts.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val raw = (now - b.start) / duration
            if (raw >= 1f) { it.remove(); continue }
            val p = if (b.implode) 1f - raw else raw
            val ease = 1f - (1f - p) * (1f - p) * (1f - p)
            val fade = if (b.implode) raw * (1f - raw) * 4f else 1f - raw
            val r = b.size * (0.5f + 2.6f * ease)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = b.size * 0.08f * (1f - raw * 0.6f)
            paint.color = withAlpha(b.color, 0.9f * fade)
            canvas.drawCircle(b.x, b.y, r, paint)
            paint.strokeWidth = b.size * 0.03f
            paint.color = withAlpha(Color.WHITE, 0.7f * fade)
            canvas.drawCircle(b.x, b.y, r * 0.72f, paint)
            // radial energy streaks
            paint.strokeWidth = b.size * 0.04f
            paint.color = withAlpha(b.color, 0.8f * fade)
            for (k in 0 until 14) {
                val a = k * (2f * PI.toFloat() / 14f) + raw * 0.6f
                val r0 = r * 0.8f
                val r1 = r * (1.05f + 0.35f * ((k * 37) % 5) / 5f)
                canvas.drawLine(b.x + cos(a) * r0, b.y + sin(a) * r0, b.x + cos(a) * r1, b.y + sin(a) * r1, paint)
            }
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(b.x, b.y, b.size * 1.4f, intArrayOf(withAlpha(Color.WHITE, 0.85f * fade * fade), withAlpha(b.color, 0.4f * fade), Color.TRANSPARENT), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(b.x, b.y, b.size * 1.4f, paint)
            paint.shader = null
        }
        if (bursts.isNotEmpty()) postInvalidateOnAnimation()
    }
}
