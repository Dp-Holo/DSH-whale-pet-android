package com.dsha.whalepet.wallpaper.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.dsha.whalepet.wallpaper.PetFrame
import com.dsha.whalepet.wallpaper.PetRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 视频壁纸渲染后端：MediaPlayer 解码到 SurfaceTexture，再由 GL 合成到壁纸 Surface。
 *
 * 为什么绕一圈 GL 而不是让 MediaPlayer 直接 setSurface(holder.surface)：
 * 直接输出无法做缩放脉动/旋转（音频律动、摇一摇），而 GL 只需在顶点着色器里乘一个
 * 缩放/旋转矩阵，几乎不增加开销；这也是"用视频做到像 3D 的轻微动感"的关键。
 *
 * 线程：所有方法都在壁纸服务的 Choreographer 回调线程调用（同一个 EGL 上下文）。
 */
class VideoPetRenderer(
    private val context: Context,
    private val holder: SurfaceHolder,
    private val assetPath: String
) : PetRenderer {

    private val tag = "WhaleVideo"

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private var program = 0
    private var texId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null

    private var player: MediaPlayer? = null
    private var prepared = false
    private var failed = false

    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var videoAspect = 1f

    private var aPosLoc = -1
    private var aTexLoc = -1
    private var uScaleLoc = -1
    private var uRotLoc = -1
    private var uAspectLoc = -1
    private var uStMatrixLoc = -1
    private var uTexLoc = -1

    private val positionBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private val texBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    private val stMatrix = FloatArray(16)

    /** 初始化是否成功；失败时由 Engine 回退到静态 Canvas 渲染 */
    val isUsable: Boolean get() = !failed

    override fun attach(width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        if (failed) return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            if (!initEgl()) {
                failed = true
                Log.w(tag, "EGL init failed")
                return
            }
        } else {
            // 尺寸/旋转变化：只需重建 window surface，GL 上下文与解码器保持
            destroyEglSurface()
            if (!createEglSurface()) {
                failed = true
                return
            }
        }
        if (program == 0) initGl()
        if (surfaceTexture == null) initTexture()
        if (player == null) startPlayer()
    }

    override fun render(frame: PetFrame, nowMs: Long) {
        if (failed || eglSurface == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        if (prepared) {
            // updateTexImage 必须在 GL 上下文当前时调用；Surface 正在销毁时可能抛异常
            runCatching { surfaceTexture?.updateTexImage() }
        }

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0.05f, 0.08f, 0.13f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (prepared && program != 0) {
            drawFrame(frame)
        }

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun drawFrame(frame: PetFrame) {
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        val va = if (videoAspect > 0f) videoAspect else surfaceAspect
        // 等比铺满（cover）：视频更宽就放大 X，更高就放大 Y，避免拉伸变形
        var sx = 1f
        var sy = 1f
        if (va >= surfaceAspect) sx = va / surfaceAspect else sy = surfaceAspect / va
        sx *= frame.scale.coerceIn(0.8f, 1.3f)
        sy *= frame.scale.coerceIn(0.8f, 1.3f)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTexLoc, 0)
        GLES20.glUniform2f(uScaleLoc, sx, sy)
        GLES20.glUniform1f(uRotLoc, frame.rotation)
        GLES20.glUniform1f(uAspectLoc, surfaceAspect)
        surfaceTexture?.getTransformMatrix(stMatrix)
        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)
    }

    /** 当前播放位置（ms），用于查音频包络；播放器未就绪时返回 0 */
    fun currentPositionMs(): Long = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    override fun detach() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        prepared = false

        runCatching { outputSurface?.release() }
        outputSurface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null

        if (program != 0) {
            runCatching { GLES20.glDeleteProgram(program) }
            program = 0
        }
        if (texId != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) }
            texId = 0
        }

        destroyEglSurface()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        failed = false
    }

    private fun initEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            return false
        }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            return false
        }
        val config = configs[0] ?: return false
        eglConfig = config
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false
        return createEglSurface()
    }

    private fun createEglSurface(): Boolean {
        val surface = runCatching { holder.surface }.getOrNull() ?: return false
        if (!surface.isValid) return false
        val config = eglConfig ?: return false
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun destroyEglSurface() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun initGl() {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertex == 0 || fragment == 0) {
            failed = true
            return
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vertex)
        GLES20.glAttachShader(p, fragment)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.w(tag, "link failed: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            failed = true
            return
        }
        program = p
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)

        aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTex")
        uScaleLoc = GLES20.glGetUniformLocation(program, "uScale")
        uRotLoc = GLES20.glGetUniformLocation(program, "uRot")
        uAspectLoc = GLES20.glGetUniformLocation(program, "uAspect")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uStMatrix")
        uTexLoc = GLES20.glGetUniformLocation(program, "uTex")
    }

    private fun initTexture() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) {
            failed = true
            return
        }
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        surfaceTexture = SurfaceTexture(texId).apply { setDefaultBufferSize(1280, 720) }
        outputSurface = Surface(surfaceTexture)
    }

    private fun startPlayer() {
        val surface = outputSurface ?: return
        val p = MediaPlayer()
        try {
            context.assets.openFd(assetPath).use { afd ->
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            p.setSurface(surface)
            p.isLooping = true
            // 壁纸默认静音：音轨只用于内容包络（提前算好），不打扰用户
            p.setVolume(0f, 0f)
            p.setOnPreparedListener { mp ->
                videoAspect = if (mp.videoHeight > 0) {
                    mp.videoWidth.toFloat() / mp.videoHeight
                } else {
                    1f
                }
                prepared = true
                runCatching { mp.start() }
            }
            p.setOnErrorListener { _, what, extra ->
                Log.w(tag, "player error what=$what extra=$extra")
                failed = true
                true
            }
            p.prepareAsync()
            player = p
        } catch (t: Throwable) {
            Log.w(tag, "startPlayer failed", t)
            runCatching { p.release() }
            failed = true
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.w(tag, "compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        // 顶点着色器：先换到"方形空间"再旋转，避免非等比 NDC 下旋转被拉斜
        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            uniform vec2 uScale;
            uniform float uRot;
            uniform float uAspect;
            uniform mat4 uStMatrix;
            varying vec2 vTex;
            void main() {
                vec2 p = aPos * uScale;
                p.x *= uAspect;
                float c = cos(uRot);
                float s = sin(uRot);
                p = mat2(c, -s, s, c) * p;
                p.x /= uAspect;
                gl_Position = vec4(p, 0.0, 1.0);
                vTex = (uStMatrix * vec4(aTex, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
        """
    }
}
