package com.houseviewer.app.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.houseviewer.app.model.MeshData

class HouseGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val houseRenderer: HouseRenderer = HouseRenderer()

    private var previousX = 0f
    private var previousY = 0f
    private var isScaling = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val nextDist = houseRenderer.distance / detector.scaleFactor
                houseRenderer.distance = nextDist.coerceIn(1.8f, 24.0f)
                requestRender()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetCamera()
                return true
            }
        }
    )

    init {
        setEGLContextClientVersion(2)
        setRenderer(houseRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun displayMesh(mesh: MeshData) {
        queueEvent {
            houseRenderer.setMesh(mesh)
            requestRender()
        }
    }

    fun resetCamera() {
        houseRenderer.resetCamera()
        requestRender()
    }

    fun toggleBackground() {
        houseRenderer.isSkyBackground = !houseRenderer.isSkyBackground
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isScaling && event.pointerCount == 1) {
                    val dx = event.x - previousX
                    val dy = event.y - previousY
                    houseRenderer.yawDegrees = (houseRenderer.yawDegrees - dx * 0.45f) % 360f
                    houseRenderer.pitchDegrees = (houseRenderer.pitchDegrees + dy * 0.35f)
                        .coerceIn(-75f, 82f)
                    requestRender()
                }
                previousX = event.x
                previousY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    previousX = event.getX(remainingIndex)
                    previousY = event.getY(remainingIndex)
                }
            }
        }
        return true
    }
}
