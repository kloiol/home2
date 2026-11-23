package com.houseviewer.app.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.houseviewer.app.model.MeshData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class HouseRenderer : GLSurfaceView.Renderer {

    @Volatile
    var yawDegrees: Float = 38f

    @Volatile
    var pitchDegrees: Float = 22f

    @Volatile
    var distance: Float = 4.2f

    @Volatile
    var defaultDistance: Float = 4.2f

    @Volatile
    var isCityMode: Boolean = false

    @Volatile
    var isSkyBackground: Boolean = false

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aNormalLoc: Int = -1
    private var aColorLoc: Int = -1
    private var uMvpMatrixLoc: Int = -1
    private var uModelMatrixLoc: Int = -1

    private var positionBuffer: FloatBuffer? = null
    private var normalBuffer: FloatBuffer? = null
    private var colorBuffer: FloatBuffer? = null
    private var vertexCount: Int = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    fun setMesh(mesh: MeshData, cityMode: Boolean = false) {
        isCityMode = cityMode
        vertexCount = mesh.vertexCount
        if (vertexCount == 0) {
            positionBuffer = null
            normalBuffer = null
            colorBuffer = null
            return
        }
        positionBuffer = toFloatBuffer(mesh.positions)
        normalBuffer = toFloatBuffer(mesh.normals)
        colorBuffer = toFloatBuffer(mesh.colors)

        if (cityMode) {
            defaultDistance = (mesh.boundingExtent * 1.38f).coerceIn(24f, 130f)
            distance = defaultDistance
            pitchDegrees = 36f
        } else {
            defaultDistance = (mesh.boundingExtent * 2.05f).coerceIn(3.8f, 13.5f)
            distance = defaultDistance
            if (pitchDegrees > 45f) pitchDegrees = 22f
        }
    }

    fun resetCamera() {
        yawDegrees = 38f
        pitchDegrees = if (isCityMode) 36f else 22f
        distance = defaultDistance
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        programId = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aNormalLoc = GLES20.glGetAttribLocation(programId, "aNormal")
        aColorLoc = GLES20.glGetAttribLocation(programId, "aColor")
        uMvpMatrixLoc = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
        uModelMatrixLoc = GLES20.glGetUniformLocation(programId, "uModelMatrix")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 44f, aspect, 0.2f, 400f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (isSkyBackground) {
            GLES20.glClearColor(0.50f, 0.77f, 0.96f, 1.0f)
        } else {
            GLES20.glClearColor(0.05f, 0.067f, 0.09f, 1.0f)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val posBuf = positionBuffer ?: return
        val normBuf = normalBuffer ?: return
        val colBuf = colorBuffer ?: return
        if (vertexCount <= 0 || programId == 0) return

        val yawRad = Math.toRadians(yawDegrees.toDouble())
        val pitchRad = Math.toRadians(pitchDegrees.toDouble())

        val eyeX = (distance * cos(pitchRad) * sin(yawRad)).toFloat()
        val eyeY = (distance * sin(pitchRad)).toFloat()
        val eyeZ = (distance * cos(pitchRad) * cos(yawRad)).toFloat()

        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glUseProgram(programId)
        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelMatrixLoc, 1, false, modelMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, posBuf)

        GLES20.glEnableVertexAttribArray(aNormalLoc)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 0, normBuf)

        GLES20.glEnableVertexAttribArray(aColorLoc)
        GLES20.glVertexAttribPointer(aColorLoc, 3, GLES20.GL_FLOAT, false, 0, colBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aNormalLoc)
        GLES20.glDisableVertexAttribArray(aColorLoc)
    }

    private fun toFloatBuffer(array: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(array.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(array)
                position(0)
            }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        return program
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uModelMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                vNormal = normalize(mat3(uModelMatrix) * aNormal);
                vColor = aColor;
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 keyLight = normalize(vec3(0.45, 0.80, 0.40));
                vec3 fillLight = normalize(vec3(-0.60, 0.30, -0.50));
                float diffKey = max(dot(n, keyLight), 0.0);
                float diffFill = max(dot(n, fillLight), 0.0) * 0.25;
                float hemi = 0.38 + 0.10 * n.y;
                float shade = clamp(hemi + diffKey * 0.56 + diffFill, 0.25, 1.10);
                gl_FragColor = vec4(vColor * shade, 1.0);
            }
        """
    }
}
