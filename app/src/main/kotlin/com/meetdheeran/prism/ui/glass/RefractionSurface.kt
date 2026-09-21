package com.meetdheeran.prism.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Real refraction on Android 12, where AGSL does not exist: an OpenGL ES 2.0 fragment shader
 * bends a snapshot of the screen at the rounded edges of every registered glass rectangle,
 * with chromatic aberration, a tilt-following specular rim, a top light and an inner shadow.
 * The snapshot is blurred in two separable passes at a third of its resolution.
 *
 * Two uses: the control center (full-screen surface over a Shizuku screenshot, tiles register
 * their bounds through [LensRegistry]) and the island's iOS-27 "lens" (a small transparent
 * surface that magnifies the strip of screen behind the notch: [magnify], [streak], [clarity]).
 */
class LensRegistry {
    val rects: SnapshotStateMap<String, Rect> = mutableStateMapOf()
    val radii: SnapshotStateMap<String, Float> = mutableStateMapOf()
}

val LocalLens = compositionLocalOf<LensRegistry?> { null }

/** Registers this composable's window bounds as a lens. No-op when no registry is provided. */
@Composable
fun Modifier.lens(key: String, cornerRadiusPx: Float): Modifier {
    val reg = LocalLens.current ?: return this
    return this.onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        if (b.width > 0f && b.height > 0f) { reg.rects[key] = b; reg.radii[key] = cornerRadiusPx }
    }
}

@Composable
fun RefractionSurface(
    bitmap: Bitmap,
    registry: LensRegistry,
    tilt: Offset,
    reveal: Float,
    dim: Float,
    modifier: Modifier = Modifier,
    /** Screen rectangle (px) that [bitmap] depicts. null = the whole screen from (0,0). */
    texRect: Rect? = null,
    /** true = the bitmap depicts exactly this surface (fallback art); overrides texRect. */
    textureIsSelf: Boolean = false,
    magnify: Float = 1f,
    streak: Float = 0f,
    /** 0 = blurred glass, 1 = crystal clear. */
    clarity: Float = 0f,
    /** Alpha outside the registered rects; 0 makes a transparent lens window. */
    outsideAlpha: Float = 1f,
    onFailed: () -> Unit = {},
) {
    val renderer = remember(bitmap) { GlassRenderer(bitmap, onFailed) }
    val view = LocalView.current
    val dm = LocalContext.current.resources.displayMetrics
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    var originOnScreen by remember { mutableStateOf(Offset.Zero) }
    var surfSize by remember { mutableStateOf(Size.Zero) }
    val keys = registry.rects.keys.sorted()
    val rects = keys.map { registry.rects[it]!! }
    val radii = keys.map { registry.radii[it] ?: 0f }
    val tex = when {
        textureIsSelf -> Rect(originOnScreen, surfSize)
        texRect != null -> texRect
        else -> Rect(0f, 0f, dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
    }
    SideEffect { renderer.update(rects, radii, originInWindow, originOnScreen, tex, tilt, reveal, dim, magnify, streak, clarity, outsideAlpha) }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    AndroidView(
        factory = { ctx: Context ->
            TextureView(ctx).apply {
                isOpaque = outsideAlpha >= 0.999f
                surfaceTextureListener = renderer
            }
        },
        modifier = modifier.onGloballyPositioned { c ->
            val b = c.boundsInWindow()
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            originInWindow = b.topLeft
            originOnScreen = Offset(loc[0] + b.left, loc[1] + b.top)
            surfSize = b.size
        },
    )
}

private const val MAX_RECTS = 24

/** Owns an EGL context on its own thread; renders when something changed. */
class GlassRenderer(private val bitmap: Bitmap, private val onFailed: () -> Unit) : TextureView.SurfaceTextureListener {
    private var thread: RenderThread? = null
    @Volatile private var rects: FloatArray = FloatArray(MAX_RECTS * 4)
    @Volatile private var radii: FloatArray = FloatArray(MAX_RECTS)
    @Volatile private var count = 0
    @Volatile private var uniforms = FloatArray(16)

    fun update(
        r: List<Rect>, rad: List<Float>, originInWindow: Offset, originOnScreen: Offset, tex: Rect,
        tilt: Offset, reveal: Float, dim: Float, magnify: Float, streak: Float, clarity: Float, outsideAlpha: Float,
    ) {
        val n = minOf(r.size, MAX_RECTS)
        val arr = FloatArray(MAX_RECTS * 4)
        val ra = FloatArray(MAX_RECTS)
        for (i in 0 until n) {
            arr[i * 4] = r[i].left - originInWindow.x; arr[i * 4 + 1] = r[i].top - originInWindow.y
            arr[i * 4 + 2] = r[i].width; arr[i * 4 + 3] = r[i].height
            ra[i] = rad[i]
        }
        rects = arr; radii = ra; count = n
        uniforms = floatArrayOf(
            -0.45f + 0.6f * tilt.x, -0.75f - 0.4f * tilt.y, // light 0,1
            reveal, dim, magnify, streak, clarity, outsideAlpha, // 2..7
            originOnScreen.x, originOnScreen.y, // 8,9
            tex.left, tex.top, max(1f, tex.width), max(1f, tex.height), // 10..13
            0f, 0f,
        )
        thread?.requestRender()
    }

    fun release() { thread?.quit(); thread = null }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        thread = RenderThread(st, width, height).also { it.start() }
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) { thread?.resize(width, height) }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private inner class RenderThread(private val st: SurfaceTexture, @Volatile private var w: Int, @Volatile private var h: Int) : Thread("prism-gl") {
        private val lock = Object()
        private var dirty = true
        private var running = true
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun requestRender() { synchronized(lock) { dirty = true; lock.notifyAll() } }
        fun resize(nw: Int, nh: Int) { w = nw; h = nh; requestRender() }
        fun quit() { synchronized(lock) { running = false; lock.notifyAll() } }

        override fun run() {
            try {
                initEgl()
                val gl = GlassProgram(bitmap)
                gl.prepare()
                while (true) {
                    synchronized(lock) {
                        while (!dirty && running) lock.wait()
                        if (!running) return
                        dirty = false
                    }
                    gl.draw(w, h, rects, radii, count, uniforms)
                    EGL14.eglSwapBuffers(display, surface)
                }
            } catch (t: Throwable) {
                android.util.Log.w("Prism", "GL glass unavailable: ${t.message}")
                onFailed()
            } finally {
                runCatching {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
                }
            }
        }

        private fun initEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
            surface = EGL14.eglCreateWindowSurface(display, configs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
        }
    }
}

/** Shaders + textures. Lives entirely on the GL thread. */
private class GlassProgram(private val bitmap: Bitmap) {
    private var quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private var lensProg = 0
    private var blurProg = 0
    private var texBackdrop = 0
    private var texBlurA = 0
    private var texBlurB = 0
    private var fboA = 0
    private var fboB = 0
    private var blurW = 0
    private var blurH = 0
    private var blurred = false

    fun prepare() {
        lensProg = program(VERT, LENS_FRAG)
        blurProg = program(VERT, BLUR_FRAG)
        texBackdrop = texture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBackdrop)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        blurW = max(1, bitmap.width / 3)
        blurH = max(1, bitmap.height / 3)
        texBlurA = texture(blurW, blurH)
        texBlurB = texture(blurW, blurH)
        fboA = fbo(texBlurA)
        fboB = fbo(texBlurB)
    }

    private fun blurOnce() {
        GLES20.glUseProgram(blurProg)
        val aPos = GLES20.glGetAttribLocation(blurProg, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        val uTex = GLES20.glGetUniformLocation(blurProg, "uTex")
        val uDir = GLES20.glGetUniformLocation(blurProg, "uDir")
        GLES20.glViewport(0, 0, blurW, blurH)
        var src = texBackdrop
        repeat(2) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src); GLES20.glUniform1i(uTex, 0)
            GLES20.glUniform2f(uDir, 1.4f / blurW, 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboB)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBlurA)
            GLES20.glUniform2f(uDir, 0f, 1.4f / blurH)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            src = texBlurB
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        blurred = true
    }

    fun draw(w: Int, h: Int, rects: FloatArray, radii: FloatArray, count: Int, u: FloatArray) {
        if (!blurred) blurOnce()
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(lensProg)
        val aPos = GLES20.glGetAttribLocation(lensProg, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBackdrop)
        GLES20.glUniform1i(loc("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBlurB)
        GLES20.glUniform1i(loc("uBlur"), 1)
        GLES20.glUniform2f(loc("uRes"), w.toFloat(), h.toFloat())
        GLES20.glUniform1i(loc("uCount"), count)
        GLES20.glUniform4fv(loc("uRects"), MAX_RECTS, rects, 0)
        GLES20.glUniform1fv(loc("uRadii"), MAX_RECTS, radii, 0)
        GLES20.glUniform2f(loc("uLight"), u[0], u[1])
        GLES20.glUniform1f(loc("uReveal"), u[2])
        GLES20.glUniform1f(loc("uDim"), u[3])
        GLES20.glUniform1f(loc("uMag"), u[4])
        GLES20.glUniform1f(loc("uStreak"), u[5])
        GLES20.glUniform1f(loc("uClarity"), u[6])
        GLES20.glUniform1f(loc("uOutside"), u[7])
        GLES20.glUniform2f(loc("uSurfOrigin"), u[8], u[9])
        GLES20.glUniform4f(loc("uTexRect"), u[10], u[11], u[12], u[13])
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun loc(name: String) = GLES20.glGetUniformLocation(lensProg, name)

    private fun texture(w: Int = 0, h: Int = 0): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (w > 0) GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        return ids[0]
    }

    private fun fbo(tex: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "fbo" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return ids[0]
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        // vUv: x right, y DOWN (matches window pixels and the bitmap's row order).
        private const val VERT = """
            attribute vec2 aPos; varying vec2 vUv;
            void main(){ vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5); gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val BLUR_FRAG = """
            precision mediump float; uniform sampler2D uTex; uniform vec2 uDir; varying vec2 vUv;
            void main(){
              vec3 c = texture2D(uTex, vUv).rgb * 0.227;
              c += (texture2D(uTex, vUv + uDir * 1.0).rgb + texture2D(uTex, vUv - uDir * 1.0).rgb) * 0.195;
              c += (texture2D(uTex, vUv + uDir * 2.0).rgb + texture2D(uTex, vUv - uDir * 2.0).rgb) * 0.122;
              c += (texture2D(uTex, vUv + uDir * 3.0).rgb + texture2D(uTex, vUv - uDir * 3.0).rgb) * 0.054;
              c += (texture2D(uTex, vUv + uDir * 4.0).rgb + texture2D(uTex, vUv - uDir * 4.0).rgb) * 0.016;
              gl_FragColor = vec4(c, 1.0);
            }
        """

        private const val LENS_FRAG = """
            precision mediump float;
            uniform sampler2D uTex; uniform sampler2D uBlur; uniform vec2 uRes; uniform int uCount;
            uniform vec4 uRects[24]; uniform float uRadii[24]; uniform vec2 uLight;
            uniform float uReveal; uniform float uDim; uniform float uMag; uniform float uStreak; uniform float uClarity; uniform float uOutside;
            uniform vec2 uSurfOrigin; uniform vec4 uTexRect;
            varying vec2 vUv;
            float sdRoundRect(vec2 p, vec2 b, float r){ vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }
            vec2 toTex(vec2 px){ return (uSurfOrigin + px - uTexRect.xy) / uTexRect.zw; }
            vec3 look(vec2 px){ vec2 t = clamp(toTex(px), 0.001, 0.999); return mix(texture2D(uBlur, t).rgb, texture2D(uTex, t).rgb, uClarity); }
            void main(){
              vec2 px = vUv * uRes;
              vec3 base = texture2D(uTex, clamp(toTex(px), 0.001, 0.999)).rgb * (1.0 - uDim);
              float best = 1e9; vec2 bc = vec2(0.0); vec2 bh = vec2(1.0);
              for (int i = 0; i < 24; i++) {
                if (i >= uCount) break;
                vec4 r = uRects[i]; vec2 c = r.xy + r.zw * 0.5; vec2 h = r.zw * 0.5;
                float d = sdRoundRect(px - c, h, uRadii[i]);
                if (d < best) { best = d; bc = c; bh = h; }
              }
              if (uCount == 0 || best > 0.0) { gl_FragColor = vec4(base * uOutside, uOutside); return; }
              // Magnify about the pane centre, then bend near the rim.
              vec2 pz = bc + (px - bc) / uMag;
              float band = 18.0;
              float t = clamp(-best / band, 0.0, 1.0);
              float lensAmt = (1.0 - t) * (1.0 - t);
              vec2 dir = normalize((px - bc) / bh + vec2(0.0001, 0.0002));
              vec2 offPx = dir * lensAmt * 26.0;
              vec3 col;
              col.r = look(pz - offPx * 1.00).r;
              col.g = look(pz - offPx * 1.07).g;
              col.b = look(pz - offPx * 1.14).b;
              float lum = dot(col, vec3(0.299, 0.587, 0.114));
              col = mix(vec3(lum), col, 1.3) * (0.9 + 0.1 * uClarity) + 0.10 * (1.0 - uClarity);
              float top = clamp(1.0 - (px.y - (bc.y - bh.y)) / (bh.y * 0.9), 0.0, 1.0);
              col += 0.10 * top * top;
              float bottom = clamp((px.y - (bc.y + bh.y * 0.55)) / (bh.y * 0.45), 0.0, 1.0);
              col -= 0.10 * bottom * bottom;
              float rim = smoothstep(2.6, 0.0, abs(best + 1.4));
              float facing = 0.5 + 0.5 * dot(normalize(px - bc + vec2(0.0001)), normalize(uLight));
              col += rim * (0.18 + 0.55 * facing);
              if (uStreak > 0.0) {
                // The iOS-27 rainbow streak: a soft diagonal band whose hue sweeps across the pane.
                float sx = (px.x - (bc.x - bh.x)) / (2.0 * bh.x);
                float sy = (px.y - (bc.y - bh.y)) / (2.0 * bh.y);
                float s = sx * 0.75 + sy * 0.55;
                float bandS = smoothstep(0.28, 0.42, s) * (1.0 - smoothstep(0.50, 0.66, s));
                vec3 rb = 0.5 + 0.5 * cos(6.2831 * (sx * 1.3 + vec3(0.0, 0.33, 0.67)));
                col += rb * bandS * 0.6 * uStreak;
              }
              gl_FragColor = vec4(mix(base, col, uReveal), 1.0);
            }
        """
    }
}
