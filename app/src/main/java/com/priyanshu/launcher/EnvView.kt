package com.priyanshu.launcher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.view.Choreographer
import kotlin.math.max

/**
 * Full-screen GPU environment behind the home screen.
 * Renders only while started, at the frame rate and resolution of the chosen GraphicsMode,
 * and tilts the camera with the phone (gyroscope parallax).
 */
class EnvView(context: Context) : GLSurfaceView(context), SensorEventListener {

    val renderer = EnvRenderer(context)

    var mode: GraphicsMode = GraphicsMode.BALANCED
        set(v) {
            field = v
            renderer.quality = v.quality
            applyScale()
        }

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val accelSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotM = FloatArray(9)
    private val orient = FloatArray(3)
    private var baseRoll = Float.NaN
    private var basePitch = 0f
    private var targetX = 0f
    private var targetY = 0f

    private var running = false
    private var lastFrame = 0L

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val interval = 1_000_000_000L / mode.fps
            if (frameTimeNanos - lastFrame >= interval - 3_000_000L) {
                lastFrame = frameTimeNanos
                renderer.parX += (targetX - renderer.parX) * 0.08f
                renderer.parY += (targetY - renderer.parY) * 0.08f
                requestRender()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        onResume()
        baseRoll = Float.NaN
        if (rotationSensor != null) {
            sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelSensor != null) {
            sensors.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        sensors.unregisterListener(this)
        onPause()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyScale()
    }

    private fun applyScale() {
        if (width > 0 && height > 0) {
            holder.setFixedSize(max(1, (width * mode.scale).toInt()), max(1, (height * mode.scale).toInt()))
        }
    }

    /** Sends a shockwave through the environment at a screen point. */
    fun ripple(x: Float, y: Float) {
        if (width == 0 || height == 0) return
        renderer.touchX = x / width
        renderer.touchY = 1f - y / height
        renderer.touchAt = renderer.now()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotM, e.values)
            SensorManager.getOrientation(rotM, orient)
            val pitch = orient[1]
            val roll = orient[2]
            if (baseRoll.isNaN()) { baseRoll = roll; basePitch = pitch }
            // drift the neutral position slowly so the scene re-centres when you change grip
            baseRoll += (roll - baseRoll) * 0.01f
            basePitch += (pitch - basePitch) * 0.01f
            targetX = ((roll - baseRoll) / 0.35f).coerceIn(-1f, 1f)
            targetY = ((pitch - basePitch) / 0.35f).coerceIn(-1f, 1f)
        } else {
            targetX = (-e.values[0] / 4f).coerceIn(-1f, 1f)
            targetY = ((e.values[1] - 6f) / 4f).coerceIn(-1f, 1f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
