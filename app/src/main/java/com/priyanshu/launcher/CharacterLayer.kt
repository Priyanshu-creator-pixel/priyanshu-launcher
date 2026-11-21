package com.priyanshu.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Shows the user's imported character as the centrepiece of the home screen.
 * A transparent cutout stands in front of the reactor; a normal rectangular picture is shown as a
 * floating holographic portrait. A 2D image is animated only with depth parallax, glow, lighting
 * pulses and particle effects around it — it is never presented as a moving 3D model.
 * Optionally draws the character's wallpaper full-screen behind everything.
 */
@SuppressLint("ViewConstructor")
class CharacterLayer(context: Context) : View(context), SensorEventListener {

    var accent = Color.CYAN
        set(v) { field = v; glow = null }
    var effect = CharEffect.ENERGY
    var size = 1f
    var lift = 0f
    var motion = true
    var running = false
        set(v) {
            field = v
            if (v) registerSensors() else sensors.unregisterListener(this)
            lastNs = 0L
            invalidate()
        }

    private var character: Bitmap? = null
    private var cutout = true
    private var glow: Bitmap? = null
    private var wallpaper: Bitmap? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fx = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val src = Rect()
    private val path = Path()
    private val rnd = Random()
    private var t = 0f
    private var lastNs = 0L
    private var reveal = 1f

    // particles
    private val n = 48
    private val px = FloatArray(n); private val py = FloatArray(n)
    private val pv = FloatArray(n); private val ps = FloatArray(n); private val pp = FloatArray(n)

    // lightning
    private var boltTimer = 1f
    private var boltLife = 0f
    private val bolt = Path()

    // glitch (timeline)
    private var glitchTimer = 2f
    private var glitchLife = 0f

    // parallax
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotM = FloatArray(9)
    private val orient = FloatArray(3)
    private var baseRoll = Float.NaN
    private var basePitch = 0f
    private var tx = 0f; private var ty = 0f
    private var parX = 0f; private var parY = 0f

    fun hasCharacter() = character != null
    fun hasWallpaper() = wallpaper != null

    fun setCharacter(bmp: Bitmap?, isCutout: Boolean, animateIn: Boolean) {
        character = bmp
        cutout = isCutout
        glow = null
        reveal = if (animateIn && motion) 0f else 1f
        for (i in 0 until n) spawn(i, true)
        invalidate()
    }

    fun setWallpaper(bmp: Bitmap?) {
        wallpaper = bmp
        invalidate()
    }

    private fun registerSensors() {
        baseRoll = Float.NaN
        val s = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (s != null && motion) sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotM, e.values)
        SensorManager.getOrientation(rotM, orient)
        if (baseRoll.isNaN()) { baseRoll = orient[2]; basePitch = orient[1] }
        baseRoll += (orient[2] - baseRoll) * 0.01f
        basePitch += (orient[1] - basePitch) * 0.01f
        tx = ((orient[2] - baseRoll) / 0.35f).coerceIn(-1f, 1f)
        ty = ((orient[1] - basePitch) / 0.35f).coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Accent-coloured, softly blurred silhouette used as the aura behind a cutout. */
    private fun glowBitmap(): Bitmap? {
        glow?.let { return it }
        val c = character ?: return null
        return try {
            val small = Bitmap.createScaledBitmap(c, max(1, c.width / 10), max(1, c.height / 10), true)
            val out = Bitmap.createBitmap(small.width + 8, small.height + 8, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
            // cheap blur: stamp the silhouette a few times with small offsets
            for (dx in -2..2) for (dy in -2..2) {
                p.alpha = 70
                cv.drawBitmap(small, 4f + dx, 4f + dy, p)
            }
            glow = out
            out
        } catch (_: Throwable) { null }
    }

    private fun spawn(i: Int, initial: Boolean) {
        val w = max(width, 1).toFloat(); val h = max(height, 1).toFloat()
        px[i] = w * (0.2f + 0.6f * rnd.nextFloat())
        py[i] = if (initial) h * (0.25f + 0.6f * rnd.nextFloat()) else h * (0.72f + 0.1f * rnd.nextFloat())
        pv[i] = h * (0.03f + 0.08f * rnd.nextFloat())
        ps[i] = 1.5f + 3f * rnd.nextFloat()
        pp[i] = rnd.nextFloat() * 6.28f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now
        t += dt
        parX += (tx - parX) * 0.1f
        parY += (ty - parY) * 0.1f
        if (reveal < 1f) reveal = min(1f, reveal + dt / 0.9f)
        val w = width.toFloat(); val h = height.toFloat()

        // wallpaper: full screen, slight parallax, darkened at the edges for the HUD
        wallpaper?.let { wp ->
            val scale = max(w / wp.width, h / wp.height) * 1.06f
            val ww = wp.width * scale; val hh = wp.height * scale
            val ox = (w - ww) / 2f - parX * w * 0.02f
            val oy = (h - hh) / 2f - parY * h * 0.015f
            dst.set(ox, oy, ox + ww, oy + hh)
            paint.alpha = 255
            paint.colorFilter = null
            canvas.drawBitmap(wp, null, dst, paint)
            fx.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.argb(150, 0, 0, 0), Color.TRANSPARENT, Color.argb(170, 0, 0, 0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, fx)
            fx.shader = null
        }

        val ch = character
        if (ch != null) {
            // placement: standing on the reactor floor, above the dock
            val targetH = h * (if (cutout) 0.56f else 0.40f) * size
            val scale = targetH / ch.height
            val cw = ch.width * scale
            val cx = w / 2f + parX * w * 0.035f
            val bottom = h * (if (cutout) 0.80f else 0.70f) - lift * h + parY * h * 0.01f
            val breathe = if (motion) 1f + 0.008f * sin(t * 1.6f) else 1f
            val ease = 1f - (1f - reveal) * (1f - reveal) * (1f - reveal)
            val rs = (1.08f - 0.08f * ease) * breathe
            val left = cx - cw * rs / 2f
            val top = bottom - targetH * rs
            dst.set(left, top, left + cw * rs, bottom)
            val centerY = (top + bottom) / 2f

            drawBackEffects(canvas, dst, cx, centerY, dt)

            // aura behind the character
            if (cutout) {
                glowBitmap()?.let { g ->
                    val pulse = if (effect == CharEffect.ENERGY) 1.04f + 0.04f * sin(t * 3f) else 1.03f
                    val gw = dst.width() * pulse; val gh = dst.height() * pulse
                    paint.alpha = (200 * ease).toInt()
                    canvas.drawBitmap(g, null, RectF(cx - gw / 2, bottom - gh + dst.height() * 0.015f, cx + gw / 2, bottom + dst.height() * 0.015f), paint)
                }
                // ground light pool
                fx.shader = RadialGradient(cx, bottom, dst.width() * 0.7f, intArrayOf(withAlpha(accent, 0.45f * ease), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                canvas.drawOval(cx - dst.width() * 0.7f, bottom - dst.width() * 0.12f, cx + dst.width() * 0.7f, bottom + dst.width() * 0.12f, fx)
                fx.shader = null
            } else {
                // holographic portrait frame
                fx.style = Paint.Style.FILL
                fx.shader = RadialGradient(cx, centerY, dst.width() * 0.9f, intArrayOf(withAlpha(accent, 0.35f * ease), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, centerY, dst.width() * 0.9f, fx)
                fx.shader = null
            }

            // the character itself, revealed bottom-up with a scan line
            canvas.save()
            val revealTop = bottom - (bottom - top) * ease
            canvas.clipRect(0f, revealTop, w, h)
            paint.alpha = (255 * min(1f, ease * 1.5f)).toInt()
            paint.colorFilter = null
            if (cutout) {
                drawGlitched(canvas, ch, dst)
            } else {
                val r = dst.width() * 0.06f
                paint.shader = BitmapShader(ch, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setScale(dst.width() / ch.width, dst.height() / ch.height); postTranslate(dst.left, dst.top) })
                }
                canvas.drawRoundRect(dst, r, r, paint)
                paint.shader = null
                fx.style = Paint.Style.STROKE
                fx.strokeWidth = 2.5f * resources.displayMetrics.density
                fx.color = withAlpha(accent, 0.9f)
                canvas.drawRoundRect(dst, r, r, fx)
                // scan lines over the portrait
                fx.style = Paint.Style.FILL
                fx.color = withAlpha(accent, 0.07f)
                var yy = dst.top
                while (yy < dst.bottom) { canvas.drawRect(dst.left, yy, dst.right, yy + 1.5f, fx); yy += 5f }
            }
            // accent light washing down from above
            fx.style = Paint.Style.FILL
            fx.shader = LinearGradient(0f, top, 0f, bottom, intArrayOf(withAlpha(accent, 0.10f + 0.05f * sin(t * 2f)), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.restore()
            fx.shader = null
            if (reveal < 1f) {
                fx.style = Paint.Style.FILL
                fx.color = withAlpha(Color.WHITE, 0.9f * (1f - ease))
                canvas.drawRect(0f, revealTop - 3f, w, revealTop + 3f, fx)
                fx.color = withAlpha(accent, 0.6f * (1f - ease))
                canvas.drawRect(0f, revealTop - 14f, w, revealTop + 14f, fx)
            }

            drawFrontEffects(canvas, dst, cx, centerY, dt)
        }

        if (running && (ch != null || wallpaper != null)) postInvalidateDelayed(33)
    }

    /** Draws the cutout; in timeline mode it occasionally splits into offset time slices. */
    private fun drawGlitched(canvas: Canvas, ch: Bitmap, d: RectF) {
        if (effect != CharEffect.TIMELINE || glitchLife <= 0f || !motion) {
            canvas.drawBitmap(ch, null, d, paint)
            return
        }
        val slices = 7
        for (k in 0 until slices) {
            val sy0 = ch.height * k / slices
            val sy1 = ch.height * (k + 1) / slices
            src.set(0, sy0, ch.width, sy1)
            val off = if (k % 2 == 0) 1f else -1f
            val shift = d.width() * 0.04f * off * sin(t * 40f + k) * (glitchLife / 0.35f)
            val dy0 = d.top + d.height() * k / slices
            val dy1 = d.top + d.height() * (k + 1) / slices
            canvas.drawBitmap(ch, src, RectF(d.left + shift, dy0, d.right + shift, dy1), paint)
        }
    }

    private fun drawBackEffects(c: Canvas, d: RectF, cx: Float, cy: Float, dt: Float) {
        if (!motion) return
        val dens = resources.displayMetrics.density
        when (effect) {
            CharEffect.PORTAL -> {
                // two counter-rotating rings of sparks and arcs behind the character
                for (ring in 0 until 2) {
                    val r = d.width() * (0.72f + 0.22f * ring)
                    val dir = if (ring == 0) 1f else -1f
                    fx.style = Paint.Style.STROKE
                    fx.strokeWidth = (3f - ring) * dens
                    fx.color = withAlpha(accent, 0.75f - 0.25f * ring)
                    val o = RectF(cx - r, cy - r, cx + r, cy + r)
                    for (k in 0 until 8) c.drawArc(o, dir * t * 50f + k * 45f, 28f, false, fx)
                    fx.style = Paint.Style.FILL
                    for (k in 0 until 24) {
                        val a = dir * t * 0.9f + k * (2f * PI.toFloat() / 24f)
                        val flick = 0.5f + 0.5f * sin(t * 9f + k * 1.7f)
                        fx.color = withAlpha(if (k % 3 == 0) Color.WHITE else accent, 0.8f * flick)
                        c.drawCircle(cx + cos(a) * r, cy + sin(a) * r, (1.5f + 2f * flick) * dens, fx)
                    }
                }
            }
            CharEffect.TIMELINE -> {
                fx.style = Paint.Style.STROKE
                for (k in 0 until 3) {
                    val r = d.width() * (0.6f + 0.25f * k)
                    fx.strokeWidth = 1.5f * dens
                    fx.color = withAlpha(accent, 0.55f - 0.12f * k)
                    val o = RectF(cx - r, cy - r * 0.35f, cx + r, cy + r * 0.35f)
                    c.save()
                    c.rotate((if (k % 2 == 0) 1f else -1f) * (15f + t * 8f * (k + 1)), cx, cy)
                    for (s in 0 until 12) c.drawArc(o, s * 30f, 18f, false, fx)
                    c.restore()
                }
                glitchTimer -= dt
                if (glitchTimer <= 0f) { glitchLife = 0.35f; glitchTimer = 2f + rnd.nextFloat() * 3f }
                if (glitchLife > 0f) glitchLife -= dt
            }
            else -> {}
        }
    }

    private fun drawFrontEffects(c: Canvas, d: RectF, cx: Float, cy: Float, dt: Float) {
        if (!motion) return
        val dens = resources.displayMetrics.density
        when (effect) {
            CharEffect.ENERGY, CharEffect.PARTICLES, CharEffect.PORTAL -> {
                fx.style = Paint.Style.FILL
                val count = if (effect == CharEffect.PARTICLES) n else n / 2
                for (i in 0 until count) {
                    py[i] -= pv[i] * dt
                    px[i] += sin(t * 1.5f + pp[i]) * 12f * dt
                    if (py[i] < d.top - d.height() * 0.1f) spawn(i, false)
                    val life = ((py[i] - d.top) / d.height()).coerceIn(0f, 1f)
                    val tw = 0.6f + 0.4f * sin(t * 6f + pp[i])
                    fx.color = withAlpha(if (i % 4 == 0) Color.WHITE else accent, 0.85f * life * tw)
                    c.drawCircle(px[i], py[i], ps[i] * dens * 0.6f, fx)
                }
            }
            CharEffect.LIGHTNING -> {
                boltTimer -= dt
                if (boltTimer <= 0f) {
                    makeBolt(d, cx)
                    boltLife = 0.45f
                    boltTimer = 0.9f + rnd.nextFloat() * 2.2f
                }
                if (boltLife > 0f) {
                    boltLife -= dt
                    val a = (boltLife / 0.45f).coerceIn(0f, 1f)
                    val flick = if (boltLife > 0.3f && ((t * 30f).toInt() % 2 == 0)) 0.45f else 1f
                    fx.style = Paint.Style.FILL
                    fx.color = withAlpha(accent, 0.18f * a * flick)
                    c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fx)
                    fx.style = Paint.Style.STROKE
                    fx.strokeJoin = Paint.Join.ROUND
                    fx.strokeCap = Paint.Cap.ROUND
                    fx.strokeWidth = 8f * dens
                    fx.color = withAlpha(accent, 0.35f * a * flick)
                    c.drawPath(bolt, fx)
                    fx.strokeWidth = 2.2f * dens
                    fx.color = withAlpha(Color.WHITE, a * flick)
                    c.drawPath(bolt, fx)
                }
                // static crackle around the character edges
                fx.style = Paint.Style.STROKE
                fx.strokeWidth = 1.2f * dens
                for (k in 0 until 4) {
                    val ang = t * 2f + k * 1.57f
                    val r = d.width() * 0.55f
                    val x0 = cx + cos(ang) * r; val y0 = cy + sin(ang) * d.height() * 0.4f
                    path.reset(); path.moveTo(x0, y0)
                    for (s in 1..4) path.lineTo(x0 + (rnd.nextFloat() - 0.5f) * 30f * dens, y0 + s * 8f * dens)
                    fx.color = withAlpha(accent, 0.5f + 0.5f * rnd.nextFloat())
                    c.drawPath(path, fx)
                }
            }
            else -> {}
        }
        fx.style = Paint.Style.FILL
    }

    private fun makeBolt(d: RectF, cx: Float) {
        bolt.reset()
        val dens = resources.displayMetrics.density
        val left = rnd.nextBoolean()
        var x = if (left) d.left - d.width() * 0.3f * rnd.nextFloat() else d.right + d.width() * 0.3f * rnd.nextFloat()
        var y = 0f
        val endY = d.top + d.height() * (0.2f + 0.5f * rnd.nextFloat())
        val endX = cx + (if (left) -1f else 1f) * d.width() * 0.3f
        bolt.moveTo(x, y)
        while (y < endY) {
            y += (18f + 26f * rnd.nextFloat()) * dens
            x += (endX - x) * 0.18f + (rnd.nextFloat() - 0.5f) * 40f * dens
            bolt.lineTo(x, y)
        }
    }
}

/** Renders a circular app icon from character artwork with zoom, position and a material ring. */
object CharacterIcon {
    fun render(src: Bitmap, sizePx: Int, zoom: Float, offX: Float, offY: Float, ring: Int, style: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = sizePx / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // background so transparent cutouts still read as an icon
        p.shader = RadialGradient(r, r * 0.8f, r * 1.2f, intArrayOf(withAlpha(ring, 0.55f), Color.argb(255, 8, 10, 16)), null, Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r * 0.94f, p)
        // the artwork, cover-fitted then zoomed and moved
        val base = max(sizePx.toFloat() / src.width, sizePx.toFloat() / src.height) * zoom
        val m = Matrix()
        m.setScale(base, base)
        m.postTranslate((sizePx - src.width * base) / 2f + offX * sizePx, (sizePx - src.height * base) / 2f + offY * sizePx)
        p.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        c.drawCircle(r, r, r * 0.94f, p)
        p.shader = null
        // material ring: 0 = holographic, 1 = metallic, 2 = energy
        p.style = Paint.Style.STROKE
        when (style) {
            1 -> {
                p.strokeWidth = r * 0.12f
                p.shader = LinearGradient(0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
                    intArrayOf(Color.rgb(240, 240, 245), Color.rgb(90, 95, 105), Color.rgb(220, 222, 230), Color.rgb(70, 72, 80)),
                    null, Shader.TileMode.CLAMP)
                c.drawCircle(r, r, r * 0.92f, p)
                p.shader = null
            }
            2 -> {
                p.strokeWidth = r * 0.10f
                p.color = ring
                c.drawCircle(r, r, r * 0.92f, p)
                p.strokeWidth = r * 0.03f
                p.color = Color.WHITE
                c.drawCircle(r, r, r * 0.92f, p)
            }
            else -> {
                p.strokeWidth = r * 0.06f
                p.color = withAlpha(ring, 0.95f)
                c.drawCircle(r, r, r * 0.93f, p)
                p.strokeWidth = r * 0.02f
                p.color = withAlpha(Color.WHITE, 0.6f)
                c.drawCircle(r, r, r * 0.84f, p)
            }
        }
        // glossy highlight
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, r, intArrayOf(withAlpha(Color.WHITE, 0.22f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawOval(r * 0.25f, r * 0.12f, r * 1.75f, r * 0.9f, p)
        return out
    }
}
