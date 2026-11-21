package com.priyanshu.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Cinematic mode: plays a pre-rendered, seamlessly looping 3D scene through the phone's hardware
 * video decoder, then runs one very light shader over it for live parallax, touch shockwaves,
 * the charging glow and recolouring to the current universe. Smooth on mid-range phones.
 */
class VideoRenderer(private val context: Context, private val videoAsset: String) :
    GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var accent = floatArrayOf(0.25f, 0.85f, 1f)
    @Volatile var parX = 0f
    @Volatile var parY = 0f
    @Volatile var touchX = 0.5f
    @Volatile var touchY = 0.5f
    @Volatile var touchAt = -100f
    @Volatile var charging = 0f
    @Volatile var renderScale = 1f
    @Volatile var playing = false
    @Volatile var failed = false

    private var program = 0
    private var texId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var player: MediaPlayer? = null
    private val texMatrix = FloatArray(16)
    private var width = 1
    private var height = 1
    private var videoW = 720
    private var videoH = 1600
    private val startNs = System.nanoTime()
    private var quad: FloatBuffer? = null
    private val lock = Any()
    private var frameReady = false

    private var uTexM = -1; private var uRes = -1; private var uTime = -1; private var uPar = -1
    private var uTouch = -1; private var uCharging = -1; private var uHue = -1; private var uSat = -1
    private var uAccent = -1; private var uScale = -1

    fun now(): Float = ((System.nanoTime() - startNs) / 1_000_000_000.0).toFloat()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quad = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0)
        }
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        buildProgram()
        startPlayer()
    }

    private fun startPlayer() {
        releasePlayer()
        try {
            val st = SurfaceTexture(texId)
            st.setOnFrameAvailableListener(this)
            surfaceTexture = st
            val surface = Surface(st)
            val afd = context.assets.openFd(videoAsset)
            val mp = MediaPlayer()
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setSurface(surface)
            surface.release()
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) { videoW = w; videoH = h } }
            mp.prepare()
            if (mp.videoWidth > 0) { videoW = mp.videoWidth; videoH = mp.videoHeight }
            if (playing) mp.start()
            player = mp
        } catch (e: Exception) {
            Log.e(TAG, "Video background failed", e)
            failed = true
        }
    }

    fun setPlaying(p: Boolean) {
        playing = p
        try {
            val mp = player ?: return
            if (p && !mp.isPlaying) mp.start()
            if (!p && mp.isPlaying) mp.pause()
        } catch (_: Exception) { }
    }

    fun releasePlayer() {
        try { player?.release() } catch (_: Exception) { }
        player = null
        try { surfaceTexture?.release() } catch (_: Exception) { }
        surfaceTexture = null
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        synchronized(lock) { frameReady = true }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(lock) {
            if (frameReady) {
                try { surfaceTexture?.updateTexImage() } catch (_: Exception) { }
                frameReady = false
            }
        }
        if (program == 0) {
            GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return
        }
        surfaceTexture?.getTransformMatrix(texMatrix)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glUniformMatrix4fv(uTexM, 1, false, texMatrix, 0)
        GLES30.glUniform2f(uRes, width.toFloat(), height.toFloat())
        val t = now()
        GLES30.glUniform1f(uTime, t)
        GLES30.glUniform2f(uPar, parX, parY)
        GLES30.glUniform3f(uTouch, touchX, touchY, t - touchAt)
        GLES30.glUniform1f(uCharging, charging)
        val a = accent
        GLES30.glUniform3f(uAccent, a[0], a[1], a[2])
        // recolour: rotate hue in YIQ space from the cyan the video was rendered in to the accent
        val (srcHue, srcChroma) = yiqHue(SOURCE[0], SOURCE[1], SOURCE[2])
        val (dstHue, dstChroma) = yiqHue(a[0], a[1], a[2])
        GLES30.glUniform1f(uHue, dstHue - srcHue)
        GLES30.glUniform1f(uSat, (dstChroma / srcChroma).coerceIn(0.35f, 1.6f))
        // cover the screen with the video, keeping its aspect ratio
        val screenAspect = width.toFloat() / height
        val videoAspect = videoW.toFloat() / videoH
        if (screenAspect > videoAspect) GLES30.glUniform2f(uScale, 1f, videoAspect / screenAspect)
        else GLES30.glUniform2f(uScale, screenAspect / videoAspect, 1f)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun yiqHue(r: Float, g: Float, b: Float): Pair<Float, Float> {
        val i = 0.596f * r - 0.274f * g - 0.322f * b
        val q = 0.211f * r - 0.523f * g + 0.312f * b
        return Pair(atan2(q, i), max(hypot(i, q), 0.02f))
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { Log.e(TAG, GLES30.glGetShaderInfoLog(s)); GLES30.glDeleteShader(s); return 0 }
        return s
    }

    private fun buildProgram() {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAG)
        if (vs == 0 || fs == 0) { failed = true; return }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs)
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { Log.e(TAG, GLES30.glGetProgramInfoLog(p)); failed = true; return }
        program = p
        uTexM = GLES30.glGetUniformLocation(p, "uTexM")
        uRes = GLES30.glGetUniformLocation(p, "uRes")
        uTime = GLES30.glGetUniformLocation(p, "uTime")
        uPar = GLES30.glGetUniformLocation(p, "uPar")
        uTouch = GLES30.glGetUniformLocation(p, "uTouch")
        uCharging = GLES30.glGetUniformLocation(p, "uCharging")
        uHue = GLES30.glGetUniformLocation(p, "uHue")
        uSat = GLES30.glGetUniformLocation(p, "uSat")
        uAccent = GLES30.glGetUniformLocation(p, "uAccent")
        uScale = GLES30.glGetUniformLocation(p, "uScale")
        GLES30.glUseProgram(p)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uTex"), 0)
    }

    companion object {
        private const val TAG = "MultiverseX"
        val SOURCE = floatArrayOf(0.25f, 0.85f, 1f)
        private const val VERT = "#version 300 es\nin vec2 aPos;\nout vec2 vUv;\nvoid main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n"
        private const val FRAG = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uTexM;
uniform vec2 uRes;
uniform float uTime;
uniform vec2 uPar;
uniform vec3 uTouch;
uniform float uCharging;
uniform float uHue;
uniform float uSat;
uniform vec3 uAccent;
uniform vec2 uScale;
in vec2 vUv;
out vec4 fragColor;
void main() {
    // parallax: the scene is shown 7% larger and slides with the phone's tilt
    vec2 c = (vUv - 0.5) * uScale / 1.07 + 0.5 + uPar * vec2(0.022, 0.016);
    // touch shockwave bends the image as it travels
    vec2 asp = vec2(uRes.x / uRes.y, 1.0);
    vec2 d = (vUv - uTouch.xy) * asp;
    float dist = length(d);
    float age = uTouch.z;
    float ring = 0.0;
    if (age < 1.5) {
        ring = exp(-pow((dist - age * 0.75) * 26.0, 2.0)) * (1.0 - age / 1.5);
        c += (d / max(dist, 0.0001)) / asp * ring * 0.014;
    }
    vec3 col = texture(uTex, (uTexM * vec4(c, 0.0, 1.0)).xy).rgb;
    // recolour to the universe accent (hue rotation in YIQ keeps brightness and detail)
    float Y = dot(col, vec3(0.299, 0.587, 0.114));
    float I = dot(col, vec3(0.596, -0.274, -0.322));
    float Q = dot(col, vec3(0.211, -0.523, 0.312));
    float ch = cos(uHue), sh = sin(uHue);
    vec2 iq = vec2(I * ch - Q * sh, I * sh + Q * ch) * uSat;
    col = clamp(vec3(Y + 0.956 * iq.x + 0.621 * iq.y, Y - 0.272 * iq.x - 0.647 * iq.y, Y - 1.106 * iq.x + 1.703 * iq.y), 0.0, 1.0);
    col += ring * mix(uAccent, vec3(1.0), 0.35) * 0.8;
    if (age < 1.5) col += uAccent * exp(-dist * 14.0) * (1.0 - age / 1.5) * 0.35;
    col += uCharging * uAccent * 0.3 * exp(-vUv.y * 7.0) * (0.65 + 0.35 * sin(uTime * 3.0 - vUv.x * 6.0));
    fragColor = vec4(col, 1.0);
}
"""
    }
}

/** View hosting the cinematic video environment, with gyroscope parallax and a frame-rate cap. */
class VideoEnvView(context: Context, videoAsset: String) : GLSurfaceView(context), SensorEventListener {

    val renderer = VideoRenderer(context, videoAsset)
    var fps = 30
    var useSensors = true

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
            val interval = 1_000_000_000L / fps
            if (frameTimeNanos - lastFrame >= interval - 3_000_000L) {
                lastFrame = frameTimeNanos
                renderer.parX += (targetX - renderer.parX) * 0.1f
                renderer.parY += (targetY - renderer.parY) * 0.1f
                requestRender()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setScale(scale: Float) {
        if (width > 0 && height > 0) holder.setFixedSize(max(1, (width * scale).toInt()), max(1, (height * scale).toInt()))
        renderer.renderScale = scale
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setScale(renderer.renderScale)
    }

    fun start() {
        if (running) return
        running = true
        onResume()
        queueEvent { renderer.setPlaying(true) }
        renderer.playing = true
        baseRoll = Float.NaN
        if (useSensors) {
            if (rotationSensor != null) sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            else if (accelSensor != null) sensors.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        sensors.unregisterListener(this)
        renderer.playing = false
        queueEvent { renderer.setPlaying(false) }
        onPause()
    }

    fun release() {
        queueEvent { renderer.releasePlayer() }
    }

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

    @Suppress("unused")
    private fun accentOf(c: Int) = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
}
