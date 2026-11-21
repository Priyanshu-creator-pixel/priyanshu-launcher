package com.priyanshu.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Colour [c] with opacity [a] (0..1). */
fun withAlpha(c: Int, a: Float): Int =
    Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

/**
 * Full-screen animated background. Draws the theme's gradient and its moving effect
 * (stars, embers, aurora, neon grid, code rain, bubbles, lightning, petals, gold dust).
 */
class FxView(context: Context, val theme: LauncherTheme) : View(context) {

    /** When false the view draws one still frame and stops (saves battery). */
    var running = true
        set(v) {
            field = v
            lastNs = 0L
            postInvalidateOnAnimation()
        }

    private val d = resources.displayMetrics.density
    private val rnd = Random()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val glowPaint = Paint()
    private val rayPaint = Paint()
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    private var w = 0f
    private var h = 0f
    private var t = 0f
    private var lastNs = 0L

    // particles
    private val n = 90
    private val px = FloatArray(n)
    private val py = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val sz = FloatArray(n)
    private val ph = FloatArray(n)
    private val rot = FloatArray(n)

    // aurora
    private val auroraShaders = arrayOfNulls<Shader>(3)

    // code rain
    private val cell get() = 16f * d
    private var cols = 0
    private var rows = 0
    private var colY = FloatArray(0)
    private var colSpeed = FloatArray(0)
    private var colLen = IntArray(0)
    private var rainChars = CharArray(0)
    private val glyphs = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<>*+=#$%&".toCharArray()

    // lightning
    private var boltTimer = 1.5f
    private var boltLife = 0f
    private val bolt = Path()

    // shooting star
    private var shootTimer = 2f
    private var shootLife = 0f
    private var sx = 0f
    private var sy = 0f

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(nw, nh, ow, oh)
        w = nw.toFloat()
        h = nh.toFloat()
        if (w <= 0f || h <= 0f) return

        bgPaint.shader = LinearGradient(0f, 0f, 0f, h, theme.bg, null, Shader.TileMode.CLAMP)
        glowPaint.shader = RadialGradient(
            w / 2f, h * 1.05f, h * 0.65f,
            withAlpha(theme.accent, 0.35f), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        rayPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.8f,
            withAlpha(theme.accent2, 0.13f), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        val auroraColours = intArrayOf(theme.accent, theme.accent2, theme.accent)
        for (b in 0 until 3) {
            val baseY = h * (0.16f + 0.11f * b)
            auroraShaders[b] = LinearGradient(
                0f, baseY - h * 0.06f, 0f, baseY + h * 0.32f,
                withAlpha(auroraColours[b], 0.30f), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        val horizon = h * 0.62f
        val r = w * 0.28f
        sunPaint.shader = LinearGradient(
            0f, horizon - 2 * r, 0f, horizon,
            Color.parseColor("#FFD319"), theme.accent, Shader.TileMode.CLAMP
        )

        for (i in 0 until n) spawn(i, initial = true)

        cols = (w / cell).toInt() + 1
        rows = (h / cell).toInt() + 2
        colY = FloatArray(cols) { -rnd.nextFloat() * h }
        colSpeed = FloatArray(cols) { (90f + rnd.nextFloat() * 170f) * d }
        colLen = IntArray(cols) { 8 + rnd.nextInt(18) }
        rainChars = CharArray(cols * rows) { glyphs[rnd.nextInt(glyphs.size)] }
    }

    private fun rf(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    /** Place particle [i] at its starting point for the current effect. */
    private fun spawn(i: Int, initial: Boolean) {
        px[i] = rnd.nextFloat() * w
        ph[i] = rnd.nextFloat() * 2f * PI.toFloat()
        rot[i] = rnd.nextFloat() * 360f
        when (theme.fx) {
            Fx.EMBERS -> {
                py[i] = if (initial) rnd.nextFloat() * h else h + rf(0f, 20f) * d
                sz[i] = rf(1f, 3.5f) * d
                vy[i] = -rf(25f, 80f) * d
                vx[i] = rf(10f, 35f) * d
            }
            Fx.BUBBLES -> {
                py[i] = if (initial) rnd.nextFloat() * h else h + rf(0f, 30f) * d
                sz[i] = rf(3f, 13f) * d
                vy[i] = -rf(18f, 50f) * d
                vx[i] = 0f
            }
            Fx.PETALS -> {
                py[i] = if (initial) rnd.nextFloat() * h else -rf(10f, 40f) * d
                sz[i] = rf(4f, 8f) * d
                vy[i] = rf(22f, 55f) * d
                vx[i] = rf(-90f, 90f)   // spin speed, degrees per second
            }
            Fx.GOLD -> {
                py[i] = rnd.nextFloat() * h
                sz[i] = rf(0.8f, 2.2f) * d
                vy[i] = -rf(3f, 9f) * d
                vx[i] = 0f
            }
            else -> {  // stars / dust
                py[i] = if (initial) rnd.nextFloat() * h else 0f
                sz[i] = rf(0.5f, 2.2f) * d
                vy[i] = rf(2f, 10f) * d
                vx[i] = 0f
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (w <= 0f || h <= 0f) return
        val now = System.nanoTime()
        var dt = if (lastNs == 0L || !running) 0f else (now - lastNs) / 1_000_000_000f
        lastNs = now
        dt = min(dt, 0.05f)
        t += dt

        if (!theme.wallpaper) canvas.drawRect(0f, 0f, w, h, bgPaint)
        paint.shader = null
        paint.style = Paint.Style.FILL

        when (theme.fx) {
            Fx.NONE -> {}
            Fx.STARS -> { drawStars(canvas, dt, n, 1f); drawShootingStar(canvas, dt) }
            Fx.EMBERS -> drawEmbers(canvas, dt)
            Fx.AURORA -> { drawStars(canvas, dt, 45, 0.6f); drawAurora(canvas) }
            Fx.GRID -> drawGrid(canvas, dt)
            Fx.RAIN -> drawRain(canvas, dt)
            Fx.BUBBLES -> drawBubbles(canvas, dt)
            Fx.VOLT -> { drawStars(canvas, dt, 50, 0.5f); drawVolt(canvas, dt) }
            Fx.PETALS -> drawPetals(canvas, dt)
            Fx.GOLD -> drawGold(canvas, dt)
        }

        if (running && theme.fx != Fx.NONE) postInvalidateOnAnimation()
    }

    // ---------------- effects ----------------

    private fun drawStars(canvas: Canvas, dt: Float, count: Int, strength: Float) {
        for (i in 0 until count) {
            py[i] += vy[i] * dt
            if (py[i] > h) spawn(i, initial = false)
            val tw = 0.5f + 0.5f * sin(t * 2.2f + ph[i])
            val a = (0.25f + 0.75f * tw) * strength
            paint.color = withAlpha(if (i % 4 == 0) theme.accent else Color.WHITE, a)
            canvas.drawCircle(px[i], py[i], sz[i], paint)
        }
    }

    private fun drawShootingStar(canvas: Canvas, dt: Float) {
        shootTimer -= dt
        if (shootTimer <= 0f && shootLife <= 0f) {
            sx = w * rf(0.4f, 1.0f)
            sy = h * rf(0.02f, 0.3f)
            shootLife = 0.9f
            shootTimer = rf(3f, 7f)
        }
        if (shootLife > 0f) {
            shootLife -= dt
            sx -= 650f * d * dt
            sy += 320f * d * dt
            val a = (shootLife / 0.9f).coerceIn(0f, 1f)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 2.2f * d
            paint.shader = LinearGradient(
                sx, sy, sx + 90f * d, sy - 45f * d,
                withAlpha(Color.WHITE, a), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawLine(sx, sy, sx + 90f * d, sy - 45f * d, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawEmbers(canvas: Canvas, dt: Float) {
        canvas.drawRect(0f, 0f, w, h, glowPaint)
        for (i in 0 until n) {
            py[i] += vy[i] * dt
            px[i] += sin(t * 1.6f + ph[i]) * vx[i] * dt
            if (py[i] < -10f * d) spawn(i, initial = false)
            val life = (py[i] / h).coerceIn(0f, 1f)
            val flicker = 0.7f + 0.3f * sin(t * 12f + ph[i] * 3f)
            val a = (0.15f + 0.85f * life) * flicker
            val col = if (life > 0.55f) theme.accent2 else theme.accent
            paint.color = withAlpha(col, a * 0.18f)
            canvas.drawCircle(px[i], py[i], sz[i] * 3.2f, paint)
            paint.color = withAlpha(col, a)
            canvas.drawCircle(px[i], py[i], sz[i], paint)
        }
    }

    private fun drawAurora(canvas: Canvas) {
        val step = 10f * d
        for (b in 0 until 3) {
            val baseY = h * (0.16f + 0.11f * b)
            val amp = h * 0.055f
            path.reset()
            path.moveTo(0f, baseY)
            var x = 0f
            while (x <= w + step) {
                val k = x / w * 2f * PI.toFloat()
                val y = baseY +
                        sin(k * 1.2f + t * (0.45f + 0.18f * b) + b) * amp +
                        sin(k * 2.7f - t * 0.7f) * amp * 0.4f
                path.lineTo(x, y)
                x += step
            }
            path.lineTo(w, baseY + h * 0.4f)
            path.lineTo(0f, baseY + h * 0.4f)
            path.close()
            paint.shader = auroraShaders[b]
            canvas.drawPath(path, paint)
        }
        paint.shader = null
    }

    private fun drawGrid(canvas: Canvas, dt: Float) {
        val horizon = h * 0.62f
        drawStars(canvas, dt, 40, 0.5f)

        // sun with stripes
        val r = w * 0.28f
        val cx = w / 2f
        val cy = horizon - r * 0.15f
        canvas.save()
        canvas.clipRect(0f, 0f, w, horizon)
        canvas.drawCircle(cx, cy, r, sunPaint)
        for (k in 0 until 6) {
            val gap = (1.5f + k * 1.6f) * d
            val y = cy + r * 0.05f + k * r * 0.14f
            canvas.drawRect(cx - r, y, cx + r, y + gap, bgPaint)
        }
        canvas.restore()

        // floor
        paint.color = withAlpha(Color.parseColor("#0D0221"), 0.92f)
        canvas.drawRect(0f, horizon, w, h, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * d
        // moving horizontal lines
        val lines = 14
        val shift = (t * 0.55f) % 1f
        for (k in 0 until lines) {
            val z = (k + shift) / lines
            val y = horizon + (h - horizon) * z * z
            paint.color = withAlpha(theme.accent, 0.15f + 0.85f * z)
            canvas.drawLine(0f, y, w, y, paint)
        }
        // lines to the vanishing point
        for (k in -12..12) {
            paint.color = withAlpha(theme.accent, 0.55f)
            canvas.drawLine(cx + k * w * 0.02f, horizon, cx + k * w * 0.17f, h, paint)
        }
        // glowing horizon
        paint.strokeWidth = 3f * d
        paint.color = withAlpha(theme.accent2, 0.9f)
        canvas.drawLine(0f, horizon, w, horizon, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawRain(canvas: Canvas, dt: Float) {
        paint.textSize = 14f * d
        paint.typeface = Typeface.MONOSPACE
        val size = rainChars.size
        if (size == 0) return
        // a few characters change every frame
        for (k in 0 until 6) rainChars[rnd.nextInt(size)] = glyphs[rnd.nextInt(glyphs.size)]
        for (c in 0 until cols) {
            colY[c] += colSpeed[c] * dt
            val head = (colY[c] / cell).toInt()
            if ((head - colLen[c]) * cell > h) {
                colY[c] = -rnd.nextFloat() * h * 0.5f
                colSpeed[c] = (90f + rnd.nextFloat() * 170f) * d
                colLen[c] = 8 + rnd.nextInt(18)
                continue
            }
            val x = c * cell
            for (j in 0 until colLen[c]) {
                val row = head - j
                if (row < 0 || row >= rows) continue
                val a = 1f - j.toFloat() / colLen[c]
                paint.color = if (j == 0) withAlpha(theme.accent2, 1f) else withAlpha(theme.accent, a * 0.85f)
                canvas.drawText(rainChars, (c * rows + row) % size, 1, x, row * cell, paint)
            }
        }
        paint.typeface = null
    }

    private fun drawBubbles(canvas: Canvas, dt: Float) {
        // light rays from the surface
        for (k in 0 until 4) {
            val top = w * (0.15f + 0.23f * k) + sin(t * 0.3f + k) * w * 0.05f
            path.reset()
            path.moveTo(top - 18f * d, 0f)
            path.lineTo(top + 18f * d, 0f)
            path.lineTo(top + 110f * d, h * 0.8f)
            path.lineTo(top + 10f * d, h * 0.8f)
            path.close()
            canvas.drawPath(path, rayPaint)
        }
        for (i in 0 until 60) {
            py[i] += vy[i] * dt
            px[i] += sin(t * 1.3f + ph[i]) * 12f * d * dt
            if (py[i] < -sz[i] * 2f) spawn(i, initial = false)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * d
            paint.color = withAlpha(theme.accent, 0.55f)
            canvas.drawCircle(px[i], py[i], sz[i], paint)
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(Color.WHITE, 0.5f)
            canvas.drawCircle(px[i] - sz[i] * 0.35f, py[i] - sz[i] * 0.35f, sz[i] * 0.22f, paint)
        }
    }

    private fun makeBolt() {
        bolt.reset()
        var x = w * rf(0.15f, 0.85f)
        var y = 0f
        val end = h * rf(0.4f, 0.8f)
        bolt.moveTo(x, y)
        var branchX = -1f
        var branchY = -1f
        while (y < end) {
            y += rf(18f, 42f) * d
            x += (rnd.nextFloat() - 0.5f) * 55f * d
            bolt.lineTo(x, y)
            if (branchX < 0f && y > end * 0.4f) { branchX = x; branchY = y }
        }
        if (branchX >= 0f) {
            var bx = branchX
            var by = branchY
            bolt.moveTo(bx, by)
            repeat(5) {
                by += rf(15f, 30f) * d
                bx += rf(10f, 35f) * d * (if (rnd.nextBoolean()) 1f else -1f)
                bolt.lineTo(bx, by)
            }
        }
    }

    private fun drawVolt(canvas: Canvas, dt: Float) {
        boltTimer -= dt
        if (boltTimer <= 0f) {
            makeBolt()
            boltLife = 0.5f
            boltTimer = rf(1.6f, 4.5f)
        }
        if (boltLife > 0f) {
            boltLife -= dt
            val a = (boltLife / 0.5f).coerceIn(0f, 1f)
            val flick = if (boltLife > 0.32f && ((t * 28f).toInt() % 2 == 0)) 0.4f else 1f
            paint.color = withAlpha(theme.accent, 0.20f * a * a * flick)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 7f * d
            paint.color = withAlpha(theme.accent, 0.35f * a * flick)
            canvas.drawPath(bolt, paint)
            paint.strokeWidth = 2.2f * d
            paint.color = withAlpha(Color.WHITE, a * flick)
            canvas.drawPath(bolt, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawPetals(canvas: Canvas, dt: Float) {
        for (i in 0 until 45) {
            py[i] += vy[i] * dt
            px[i] += (sin(t * 0.8f + ph[i]) * 28f + 10f) * d * dt
            rot[i] += vx[i] * dt
            if (py[i] > h + sz[i] * 2f || px[i] > w + sz[i] * 3f) spawn(i, initial = false)
            canvas.save()
            canvas.translate(px[i], py[i])
            canvas.rotate(rot[i])
            canvas.scale(1f, 0.35f + 0.65f * abs(sin(t * 1.8f + ph[i])))
            oval.set(-sz[i], -sz[i] * 0.6f, sz[i], sz[i] * 0.6f)
            paint.color = withAlpha(if (i % 3 == 0) theme.accent2 else theme.accent, 0.8f)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
    }

    private fun frac(v: Float) = v - floor(v)

    private fun drawGold(canvas: Canvas, dt: Float) {
        canvas.drawRect(0f, 0f, w, h, glowPaint)
        // drifting dust
        for (i in 18 until n) {
            py[i] += vy[i] * dt
            if (py[i] < 0f) { py[i] = h; px[i] = rnd.nextFloat() * w }
            val a = 0.25f + 0.5f * (0.5f + 0.5f * sin(t * 1.7f + ph[i]))
            paint.color = withAlpha(theme.accent, a)
            canvas.drawCircle(px[i], py[i], sz[i], paint)
        }
        // sparkles that pop in and out at new places
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 1.3f * d
        for (i in 0 until 18) {
            val cyc = t * 0.45f + ph[i]
            val k = floor(cyc)
            val f = cyc - k
            val s = sin(f * PI.toFloat())
            val x = frac(sin(k * 12.9898f + i * 78.233f) * 43758.547f) * w
            val y = frac(sin(k * 39.346f + i * 11.135f) * 24634.635f) * h
            val len = 9f * d * s
            paint.color = withAlpha(theme.accent2, s)
            canvas.drawLine(x - len, y, x + len, y, paint)
            canvas.drawLine(x, y - len, x, y + len, paint)
        }
        paint.style = Paint.Style.FILL
    }
}
