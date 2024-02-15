package com.example.cameraxml

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.RelativeLayout
import com.example.cameraxml.databinding.ActivityMainBinding
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class CameraPreview(context: Context, private val textureView: TextureView) {
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayMetrics = DisplayMetrics()
    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var framesCount = 0
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var isCapturingFrames = false
    private var capturedFramesCount = 0
    private val capturedFramesList = mutableListOf<Bitmap>()
    private var frameCaptureListener: FrameCaptureListener? = null
    lateinit var binding: ActivityMainBinding

    // declaring that only one thread can be used
    private val cameraOpenCloseLock = Semaphore(1)

    init {
        startBackgroundThread()
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                openCamera(width, height)
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                configureTransform(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                stopBackgroundThread()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            }
        }
    }

    fun startCameraPreview() {
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            try {
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        openCamera(width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        configureTransform(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        closeCamera()
                        stopBackgroundThread()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        if (isCapturingFrames) {
                            captureFrame()
                        }
                    }
                }
            } catch (e: InterruptedException) {
                throw RuntimeException("Interrupted while trying to lock camera opening.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        val cameraId = getCameraId() ?: return
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            val map =
                getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw RuntimeException("Cannot get available preview/video sizes")

//            configureTransform(width, height)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCameraPreviewSession(map, width, height)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }

    private fun createCameraPreviewSession(map: StreamConfigurationMap, width: Int, height: Int) {
        try {
            val texture = textureView.surfaceTexture
            // Set the aspect ratio of TextureView to the aspect ratio of the preview size
            val previewSize =
                chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), width, height)
            texture?.setDefaultBufferSize(previewSize.width, previewSize.height)

            val surface = Surface(texture)

            val captureRequestBuilder =
                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder?.addTarget(surface)

            cameraDevice?.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return

                        cameraCaptureSession = session

                        try {
                            captureRequestBuilder?.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            captureRequestBuilder?.build()
                                ?.let { session.setRepeatingRequest(it, null, backgroundHandler) }
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        // Configuration failed
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val map =
            getCameraId()?.let { getCameraCharacteristics(it).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }
                ?: return

        val rotation = textureView.display.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(
            0f, 0f,
            map.getOutputSizes(SurfaceTexture::class.java)[0].height.toFloat(),
            map.getOutputSizes(SurfaceTexture::class.java)[0].width.toFloat()
        )
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale =
                (viewHeight.toFloat() / map.getOutputSizes(SurfaceTexture::class.java)[0].height).coerceAtLeast(
                    viewWidth.toFloat() / map.getOutputSizes(SurfaceTexture::class.java)[0].width
                )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    private fun getCameraId(): String? {
        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return id
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        return null
    }

    private fun getCameraCharacteristics(cameraId: String): CameraCharacteristics {
        return cameraManager.getCameraCharacteristics(cameraId)
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun captureFrame() {
        if (capturedFramesCount < framesCount) {
            val bitmap = textureView.bitmap
            if (bitmap != null) {
                capturedFramesList.add(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            }
            capturedFramesCount++
            if (bitmap != null) {
                frameCaptureListener?.onFrameCaptured(bitmap)
            }
            if (capturedFramesCount == framesCount) {
                frameCaptureListener?.onFramesCaptured(capturedFramesList)
                isCapturingFrames = false
            }
        }
        println("captured frames are $capturedFramesList")
    }

    fun startCapturingFrames(count: Int) {
        framesCount = count
        isCapturingFrames = true
        capturedFramesCount = 0
        capturedFramesList.clear()
    }

    private fun getCapturedFrames(): List<Bitmap> {
        return capturedFramesList
    }

    interface FrameCaptureListener {
        fun onFrameCaptured(frame: Bitmap)
        fun onFramesCaptured(frames: List<Bitmap>)
    }

    fun setFrameCaptureListener(listener: FrameCaptureListener) {
        frameCaptureListener = listener
    }

    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.")
        } finally {
            cameraOpenCloseLock.release()
        }
    }

//    private fun chooseOptimalSize(
//        choices: Array<Size>,
//        textureViewWidth: Int,
//        textureViewHeight: Int,
//    ): Size {
//        val aspectRatio = textureViewHeight.toDouble() / textureViewWidth.toDouble()
//        var selectedSize = choices[0]
//        var selectedAspectRatio =
//            abs(selectedSize.width.toDouble() / selectedSize.height.toDouble() - aspectRatio)
//
//        for (size in choices) {
//            val currentAspectRatio =
//                abs(size.width.toDouble() / size.height.toDouble() - aspectRatio)
//            if (currentAspectRatio < selectedAspectRatio) {
//                selectedSize = size
//                selectedAspectRatio = currentAspectRatio
//            }
//        }
//        return selectedSize
//    }

    private fun chooseOptimalSize(
        choices: Array<Size>,
        textureViewWidth: Int,
        textureViewHeight: Int,
    ): Size {
        val textureViewArea = textureViewWidth * textureViewHeight
        var selectedSize = choices[0]
        var minAreaDiff = Int.MAX_VALUE

        for (size in choices) {
            val area = size.width * size.height
            val areaDiff = abs(area - textureViewArea)
            if (areaDiff < minAreaDiff) {
                selectedSize = size
                minAreaDiff = areaDiff
            }
        }
        return selectedSize
    }


}