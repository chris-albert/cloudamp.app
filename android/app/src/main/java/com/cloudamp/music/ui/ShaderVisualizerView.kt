package com.cloudamp.music.ui

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 renderer for the feedback-based visualizations. Mirrors the
 * web app's WebGL setup: a fullscreen quad rendered through a per-mode
 * fragment shader with ping-pong framebuffers for the previous-frame feedback
 * texture and a 256x1 log-remapped frequency texture updated each frame.
 */
class ShaderVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), AudioVisualizerView {

    private val renderer = FeedbackRenderer()

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** Set (or switch) the fragment shader. Feedback history is cleared. */
    fun setFragmentShader(source: String) {
        renderer.pendingSource = source
    }

    override fun updateFft(data: ByteArray) {
        renderer.latestFft = data.copyOf()
    }

    private class FeedbackRenderer : Renderer {

        companion object {
            private const val TAG = "ShaderVisualizer"
            private const val FREQ_SIZE = 256
        }

        @Volatile var pendingSource: String = GlShaders.TUNNEL_FS
        @Volatile var latestFft: ByteArray? = null

        private var program = 0
        private var compiledSource: String? = null
        private var uPrevLoc = -1
        private var uFreqLoc = -1
        private var uTimeLoc = -1

        private var vao = 0
        private var vbo = 0
        private var freqTexture = 0
        private val framebuffers = IntArray(2)
        private val textures = IntArray(2)
        private var fboWidth = 0
        private var fboHeight = 0
        private var width = 0
        private var height = 0
        private var pingPong = 0

        private val smoothedFreq = FloatArray(FREQ_SIZE)
        private val targetFreq = FloatArray(FREQ_SIZE)
        private val freqBuffer: ByteBuffer =
            ByteBuffer.allocateDirect(FREQ_SIZE).order(ByteOrder.nativeOrder())
        private val startTimeNs = System.nanoTime()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Context may be brand new (or lost) — recreate all GL objects
            program = 0
            compiledSource = null
            fboWidth = 0
            fboHeight = 0

            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0)
            vao = ids[0]
            GLES30.glGenBuffers(1, ids, 0)
            vbo = ids[0]

            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val quadBuffer = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
            quadBuffer.position(0)
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER, quad.size * 4, quadBuffer, GLES30.GL_STATIC_DRAW
            )
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindVertexArray(0)

            GLES30.glGenTextures(1, ids, 0)
            freqTexture = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, freqTexture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = w
            height = h
        }

        override fun onDrawFrame(gl: GL10?) {
            if (width <= 0 || height <= 0) return

            if (fboWidth != width || fboHeight != height) {
                rebuildFramebuffers()
            }

            val source = pendingSource
            if (source != compiledSource) {
                if (!rebuildProgram(source)) return
                clearFramebuffers()
            }
            if (program == 0) return

            uploadFreqTexture()

            val time = (System.nanoTime() - startTimeNs) / 1_000_000_000f
            val readIdx = pingPong
            val writeIdx = 1 - readIdx

            // Render feedback pass into write FBO
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[writeIdx])
            GLES30.glViewport(0, 0, width, height)
            GLES30.glUseProgram(program)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[readIdx])
            GLES30.glUniform1i(uPrevLoc, 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, freqTexture)
            GLES30.glUniform1i(uFreqLoc, 1)

            GLES30.glUniform1f(uTimeLoc, time)

            GLES30.glBindVertexArray(vao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)

            // Blit write FBO to screen
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, framebuffers[writeIdx])
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
            GLES30.glBlitFramebuffer(
                0, 0, width, height, 0, 0, width, height,
                GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

            pingPong = writeIdx
        }

        private fun uploadFreqTexture() {
            val fft = latestFft
            if (fft != null) {
                VisMath.logBars(fft, targetFreq)
            } else {
                targetFreq.fill(0f)
            }
            freqBuffer.clear()
            for (i in 0 until FREQ_SIZE) {
                // Smooth toward target — the Visualizer capture rate (~20Hz) is
                // slower than the render rate, and web's AnalyserNode smooths too
                smoothedFreq[i] = smoothedFreq[i] * 0.55f + targetFreq[i] * 0.45f
                freqBuffer.put((smoothedFreq[i] * 255f).toInt().coerceIn(0, 255).toByte())
            }
            freqBuffer.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, freqTexture)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, FREQ_SIZE, 1, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, freqBuffer
            )
        }

        private fun rebuildFramebuffers() {
            if (fboWidth > 0) {
                GLES30.glDeleteFramebuffers(2, framebuffers, 0)
                GLES30.glDeleteTextures(2, textures, 0)
            }
            GLES30.glGenFramebuffers(2, framebuffers, 0)
            GLES30.glGenTextures(2, textures, 0)
            for (i in 0..1) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
                )
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[i])
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, textures[i], 0
                )
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            fboWidth = width
            fboHeight = height
            clearFramebuffers()
        }

        private fun clearFramebuffers() {
            for (i in 0..1) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[i])
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }

        private fun rebuildProgram(fragmentSource: String): Boolean {
            val vs = compileShader(GLES30.GL_VERTEX_SHADER, GlShaders.FULLSCREEN_QUAD_VS)
            val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            if (vs == 0 || fs == 0) return false

            val newProgram = GLES30.glCreateProgram()
            GLES30.glAttachShader(newProgram, vs)
            GLES30.glAttachShader(newProgram, fs)
            GLES30.glBindAttribLocation(newProgram, 0, "a_position")
            GLES30.glLinkProgram(newProgram)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)

            val status = IntArray(1)
            GLES30.glGetProgramiv(newProgram, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "Program link error: " + GLES30.glGetProgramInfoLog(newProgram))
                GLES30.glDeleteProgram(newProgram)
                return false
            }

            if (program != 0) GLES30.glDeleteProgram(program)
            program = newProgram
            compiledSource = fragmentSource
            uPrevLoc = GLES30.glGetUniformLocation(program, "u_prev")
            uFreqLoc = GLES30.glGetUniformLocation(program, "u_freq")
            uTimeLoc = GLES30.glGetUniformLocation(program, "u_time")
            return true
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "Shader compile error: " + GLES30.glGetShaderInfoLog(shader))
                GLES30.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }
}
