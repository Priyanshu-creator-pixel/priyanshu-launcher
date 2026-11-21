package com.priyanshu.launcher

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Render cost presets. scale = fraction of screen resolution, quality = shader detail level. */
enum class GraphicsMode(val label: String, val scale: Float, val fps: Int, val quality: Float) {
    EXTREME("Extreme", 1.0f, 60, 3f),
    HIGH("High", 0.75f, 60, 2f),
    BALANCED("Balanced", 0.5f, 30, 1f),
    SAVER("Battery saver", 0.33f, 20, 0f)
}

/**
 * Draws one full-screen GPU environment (a GLSL ES 3.00 fragment shader from assets/shaders).
 * All fields are written from the UI thread and read on the GL thread.
 */
class EnvRenderer(private val context: Context) : GLSurfaceView.Renderer {

    @Volatile var envName = "reactor_core"
    @Volatile var accent = floatArrayOf(0.25f, 0.85f, 1f)
    @Volatile var quality = 1f
    @Volatile var parX = 0f
    @Volatile var parY = 0f
    @Volatile var touchX = 0.5f
    @Volatile var touchY = 0.5f
    @Volatile var touchAt = -100f
    @Volatile var battery = 1f
    @Volatile var charging = 0f
    @Volatile var day = 0.5f

    /** Set if the shader failed to build, so the UI can fall back to the 2D effect. */
    @Volatile var failed = false

    private var program = 0
    private var loaded = ""
    private var width = 1
    private var height = 1
    private val startNs = System.nanoTime()
    private var quad: FloatBuffer? = null

    private var uRes = -1
    private var uTime = -1
    private var uPar = -1
    private var uTouch = -1
    private var uBattery = -1
    private var uCharging = -1
    private var uDay = -1
    private var uAccent = -1
    private var uQuality = -1

    /** Seconds since start, wrapped hourly to keep float precision on the GPU. */
    fun now(): Float = (((System.nanoTime() - startNs) / 1_000_000_000.0) % 3600.0).toFloat()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quad = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
            position(0)
        }
        program = 0
        loaded = ""
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (loaded != envName) build(envName)
        if (program == 0) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val t = now()
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uRes, width.toFloat(), height.toFloat())
        GLES30.glUniform1f(uTime, t)
        GLES30.glUniform2f(uPar, parX, parY)
        GLES30.glUniform3f(uTouch, touchX, touchY, t - touchAt)
        GLES30.glUniform1f(uBattery, battery)
        GLES30.glUniform1f(uCharging, charging)
        GLES30.glUniform1f(uDay, day)
        val a = accent
        GLES30.glUniform3f(uAccent, a[0], a[1], a[2])
        GLES30.glUniform1f(uQuality, quality)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun asset(name: String): String =
        context.assets.open("shaders/$name").bufferedReader().use { it.readText() }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES30.glGetShaderInfoLog(s))
            GLES30.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun build(name: String) {
        loaded = name
        if (program != 0) GLES30.glDeleteProgram(program)
        program = 0
        val fragSrc = try {
            asset("common.glsl") + "\n" + asset("$name.glsl") + "\n" + asset("footer.glsl")
        } catch (e: Exception) {
            Log.e(TAG, "Missing shader $name", e)
            failed = true
            return
        }
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        if (vs == 0 || fs == 0) { failed = true; return }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES30.glGetProgramInfoLog(p))
            GLES30.glDeleteProgram(p)
            failed = true
            return
        }
        program = p
        failed = false
        uRes = GLES30.glGetUniformLocation(p, "uRes")
        uTime = GLES30.glGetUniformLocation(p, "uTime")
        uPar = GLES30.glGetUniformLocation(p, "uPar")
        uTouch = GLES30.glGetUniformLocation(p, "uTouch")
        uBattery = GLES30.glGetUniformLocation(p, "uBattery")
        uCharging = GLES30.glGetUniformLocation(p, "uCharging")
        uDay = GLES30.glGetUniformLocation(p, "uDay")
        uAccent = GLES30.glGetUniformLocation(p, "uAccent")
        uQuality = GLES30.glGetUniformLocation(p, "uQuality")
    }

    companion object {
        private const val TAG = "MultiverseX"
        private const val VERT = "#version 300 es\nin vec2 aPos;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n"
    }
}
