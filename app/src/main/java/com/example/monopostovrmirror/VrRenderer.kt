package com.example.monopostovrmirror

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the MediaProjection output (delivered as a GL_TEXTURE_EXTERNAL_OES
 * SurfaceTexture) twice into a single frame: left half of the screen and
 * right half of the screen. No CPU pixel copies happen anywhere in this path -
 * the GPU samples the same OES texture twice per frame.
 */
class VrRenderer(
    private val settingsProvider: () -> VrSettings,
    private val onCaptureSurfaceReady: (Surface) -> Unit
) : GLSurfaceView.Renderer {

    // Set by the GLSurfaceView owner so we can request a redraw when a new
    // camera/game frame arrives (RENDERMODE_WHEN_DIRTY keeps this efficient
    // on a mid-range phone instead of burning battery redrawing at max rate).
class VrRenderer(
    private val captureWidth: Int,
    private val captureHeight: Int,
    private val settingsProvider: () -> VrSettings,
    private val onCaptureSurfaceReady: (Surface) -> Unit
) : GLSurfaceView.Renderer {    var glSurfaceView: GLSurfaceView? = null

    private var textureId = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private var program = 0
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0
    private var uTexMatrixHandle = 0
    private var uTexTransformHandle = 0
    private var uBarrelHandle = 0
    private var uTextureHandle = 0

    private val texMatrix = FloatArray(16)
    private val frameAvailableLock = Object()
    private var frameAvailable = false

    // Full-screen quad in NDC split into two halves (left eye / right eye).
    // Position: x,y ; TexCoord: u,v
    private val leftQuad = quadBuffer(-1f, -1f, 0f, 1f)   // NDC x in [-1, 0]
    private val rightQuad = quadBuffer(0f, -1f, 1f, 1f)   // NDC x in [0, 1]

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform vec4 uTexTransform; // scaleX, scaleY, offsetX, offsetY
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec4 tc = uTexMatrix * vec4(aTexCoord, 0.0, 1.0);
                vec2 centered = (tc.xy - 0.5) / uTexTransform.xy;
                vTexCoord = centered + 0.5 + uTexTransform.zw;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uBarrel;
            void main() {
                vec2 uv = vTexCoord;
                if (uBarrel > 0.5) {
                    vec2 c = uv - 0.5;
                    float r2 = dot(c, c);
                    uv = 0.5 + c * (1.0 + 0.22 * r2);
                }
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTexture, uv);
                }
            }
        """

        private fun quadBuffer(x0: Float, y0: Float, x1: Float, y1: Float): FloatBuffer {
            // 6 vertices (two triangles), interleaved: posX, posY, texU, texV
            val verts = floatArrayOf(
                x0, y0, 0f, 0f,
                x1, y0, 1f, 0f,
                x0, y1, 0f, 1f,
                x0, y1, 0f, 1f,
                x1, y0, 1f, 0f,
                x1, y1, 1f, 1f
            )
            return ByteBuffer.allocateDirect(verts.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(verts); position(0) }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        textureId = createExternalOesTexture()
        surfaceTexture = SurfaceTexture(textureId)
surfaceTexture.setDefaultBufferSize(captureWidth, captureHeight)surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener {
            synchronized(frameAvailableLock) { frameAvailable = true }
            glSurfaceView?.requestRender()
        }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexTransformHandle = GLES20.glGetUniformLocation(program, "uTexTransform")
        uBarrelHandle = GLES20.glGetUniformLocation(program, "uBarrel")
        uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")

        // Hand the capture-ready Surface back to the service so it can call
        // MediaProjection.createVirtualDisplay(...) with it.
        onCaptureSurfaceReady(Surface(surfaceTexture))
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(frameAvailableLock) {
            if (frameAvailable) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)
                frameAvailable = false
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTextureHandle, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, texMatrix, 0)

        val s = settingsProvider()
        GLES20.glUniform1f(uBarrelHandle, if (s.barrelDistortion) 1f else 0f)

        // Left eye: small negative offset; right eye: small positive offset.
        // With the SAME source frame this just nudges each half's sampling
        // window slightly apart - a cheap parallax feel, NOT true stereo depth,
        // exactly as specified (duplicate image is acceptable).
        drawEye(leftQuad, s, eyeOffsetSign = -1f)
        drawEye(rightQuad, s, eyeOffsetSign = 1f)
    }

    private fun drawEye(quad: FloatBuffer, s: VrSettings, eyeOffsetSign: Float) {
        quad.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)

        val scale = s.zoom
        val offsetX = s.horizontalPos + eyeOffsetSign * s.eyeSeparation
        val offsetY = s.verticalPos
        GLES20.glUniform4f(uTexTransformHandle, scale, scale, offsetX, offsetY)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
    }

    /** Called from CaptureService when the WindowManager overlay is torn down. */
    fun release() {
        try {
            surfaceTexture.release()
        } catch (_: Exception) {
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
        }
    }

    private fun createExternalOesTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Program link failed: $log")
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }
}
