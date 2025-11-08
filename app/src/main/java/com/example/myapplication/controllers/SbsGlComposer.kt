package com.opic3d.Spatial.trendingvideos.controllers.gl

import android.opengl.*
import android.util.Log
import android.util.Size
import android.view.Surface
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Side-by-Side (SBS) OpenGL compositor for stereo (3D) rendering.
 * - Recorder: Horizontal SBS (left|right)
 * - Preview: Vertical Split (top=leftEye, bottom=rightEye)
 * - Proper upright orientation (no rotation artifacts)
 */
class SbsGlComposer(
    private val outW: Int,
    private val outH: Int,
    private var outSurface: Surface,
    private val eyeSize: Size,
    private val previewSurface: Surface? = null
) {
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var previewEglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null

    private var leftTex = 0
    private var rightTex = 0
    private var leftSt: SurfaceTexture? = null
    private var rightSt: SurfaceTexture? = null
    lateinit var leftSurface: Surface; private set
    lateinit var rightSurface: Surface; private set

    private var prog = 0
    private var posLoc = 0
    private var texLoc = 0
    private var samplerLoc = 0
    private var rotationLoc = 0

    private val thread = HandlerThread("SbsComposer")
    private lateinit var handler: Handler
    @Volatile private var running = false
    private val initLatch = CountDownLatch(1)

    init {
        thread.start()
        handler = Handler(thread.looper)
        handler.post {
            try { initGl() } finally { initLatch.countDown() }
        }
        initLatch.await(1, TimeUnit.SECONDS)
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val vers = IntArray(2)
        EGL14.eglInitialize(eglDisplay, vers, 0, vers, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val attribCtx = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, attribCtx, 0)

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, outSurface, intArrayOf(EGL14.EGL_NONE), 0
        )

        previewEglSurface = previewSurface?.let {
            EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, it, intArrayOf(EGL14.EGL_NONE), 0)
        }

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // --- Shaders ---
        val vsh = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            uniform mat4 uRotation;
            void main() {
                gl_Position = aPos;
                // Rotate around center (0.5,0.5)
                vec2 centered = aTex - vec2(0.5, 0.5);
                vec2 rotated = (uRotation * vec4(centered, 0.0, 1.0)).xy;
                vTex = rotated + vec2(0.5, 0.5);
            }
        """.trimIndent()

        val fsh = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
        """.trimIndent()

        fun compile(type: Int, src: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            return id
        }

        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vsh))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fsh))
        GLES20.glLinkProgram(prog)

        posLoc = GLES20.glGetAttribLocation(prog, "aPos")
        texLoc = GLES20.glGetAttribLocation(prog, "aTex")
        samplerLoc = GLES20.glGetUniformLocation(prog, "uTex")
        rotationLoc = GLES20.glGetUniformLocation(prog, "uRotation")

        fun makeOesTex(): Int {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return tex[0]
        }

        leftTex = makeOesTex()
        rightTex = makeOesTex()

        leftSt = SurfaceTexture(leftTex).apply { setDefaultBufferSize(eyeSize.width, eyeSize.height) }
        rightSt = SurfaceTexture(rightTex).apply { setDefaultBufferSize(eyeSize.width, eyeSize.height) }
        leftSurface = Surface(leftSt)
        rightSurface = Surface(rightSt)

        leftSt!!.setOnFrameAvailableListener({ render() }, handler)
        rightSt!!.setOnFrameAvailableListener({ render() }, handler)
    }

    fun start() { running = true }
    fun stop() { running = false }

    private fun render() {
        if (!running) return
        handler.post {
            try {
                leftSt?.updateTexImage()
                rightSt?.updateTexImage()
                drawFrame()
            } catch (t: Throwable) {
                Log.w("SbsGlComposer", "render err: ${t.message}")
            }
        }
    }

    /** Recorder = horizontal SBS, Preview = vertical split upright */
    private fun drawFrame() {
        GLES20.glUseProgram(prog)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val leftQuadH = floatArrayOf(
            -1f, -1f, 0f, 1f,
            0f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
            0f,  1f, 1f, 0f
        )
        val rightQuadH = floatArrayOf(
            0f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            0f,  1f, 0f, 0f,
            1f,  1f, 1f, 0f
        )

        val topQuadV = floatArrayOf(
            -1f,  0f, 0f, 1f,
            1f,  0f, 1f, 1f,
            -1f,  1f, 0f, 0f,
            1f,  1f, 1f, 0f
        )
        val bottomQuadV = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f,  0f, 0f, 0f,
            1f,  0f, 1f, 0f
        )

        fun drawHalf(texId: Int, data: FloatArray, rotate: Boolean = false) {
            val buf = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buf.put(data).position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
            buf.position(2)
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, buf)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(samplerLoc, 0)

            val mat = FloatArray(16)
            if (rotate) Matrix.setRotateM(mat, 0, -90f, 0f, 0f, 1f)
            else Matrix.setIdentityM(mat, 0)
            GLES20.glUniformMatrix4fv(rotationLoc, 1, false, mat, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        // Recorder surface (horizontal SBS)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawHalf(leftTex, leftQuadH, rotate = false)
        drawHalf(rightTex, rightQuadH, rotate = false)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        // Preview surface (vertical split with upright rotation)
        if (previewEglSurface != null && previewEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)
            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawHalf(leftTex, topQuadV, rotate = true)
            drawHalf(rightTex, bottomQuadV, rotate = true)
            EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
        }

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun release() {
        running = false
        handler.post {
            leftSt?.setOnFrameAvailableListener(null)
            rightSt?.setOnFrameAvailableListener(null)
            leftSurface.release()
            rightSurface.release()
            leftSt?.release()
            rightSt?.release()
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (previewEglSurface != null && previewEglSurface != EGL14.EGL_NO_SURFACE)
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        thread.quitSafely()
    }

    fun switchOutputSurface(newSurface: Surface) {
        handler.post {
            try {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, newSurface, intArrayOf(EGL14.EGL_NONE), 0
                )
                outSurface = newSurface
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                Log.d("SbsGlComposer", "Output surface switched successfully")
            } catch (t: Throwable) {
                Log.e("SbsGlComposer", "Failed to switch output surface: ${t.message}")
            }
        }
    }
}
