package com.simplemobiletools.camera.views

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Size
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import com.simplemobiletools.camera.activities.MainActivity
import com.simplemobiletools.camera.dialogs.ChangeResolutionDialog
import com.simplemobiletools.camera.extensions.config
import com.simplemobiletools.camera.extensions.getMyCamera
import com.simplemobiletools.camera.extensions.getOutputMediaFile
import com.simplemobiletools.camera.helpers.*
import com.simplemobiletools.camera.interfaces.MyPreview
import com.simplemobiletools.camera.models.FocusArea
import com.simplemobiletools.camera.models.MySize
import com.simplemobiletools.commons.helpers.isJellyBean1Plus
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

// based on the Android Camera2 photo sample at https://github.com/googlesamples/android-Camera2Basic
// and video sample at https://github.com/googlesamples/android-Camera2Video
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class PreviewCameraTwo : ViewGroup, TextureView.SurfaceTextureListener, MyPreview {
    private val FOCUS_TAG = "focus_tag"
    private val MAX_PREVIEW_WIDTH = 1920
    private val MAX_PREVIEW_HEIGHT = 1080
    private val CLICK_MS = 250
    private val CLICK_DIST = 20

    private val DEFAULT_ORIENTATIONS = SparseIntArray(4).apply {
        append(Surface.ROTATION_0, 90)
        append(Surface.ROTATION_90, 0)
        append(Surface.ROTATION_180, 270)
        append(Surface.ROTATION_270, 180)
    }

    private val INVERSE_ORIENTATIONS = SparseIntArray(4).apply {
        append(Surface.ROTATION_0, 270)
        append(Surface.ROTATION_90, 180)
        append(Surface.ROTATION_180, 90)
        append(Surface.ROTATION_270, 0)
    }

    private lateinit var mActivity: MainActivity
    private lateinit var mTextureView: AutoFitTextureView

    private var mSensorOrientation = 0
    private var mRotationAtCapture = 0
    private var mZoomLevel = 1
    private var mZoomFingerSpacing = 0f
    private var mDownEventAtMS = 0L
    private var mDownEventAtX = 0f
    private var mDownEventAtY = 0f
    private var mIsFlashSupported = true
    private var mIsZoomSupported = true
    private var mIsFocusSupported = true
    private var mIsImageCaptureIntent = false
    private var mIsInVideoMode = false
    private var mIsRecording = false
    private var mUseFrontCamera = false
    private var mCameraId = ""
    private var mCameraState = STATE_INIT
    private var mFlashlightState = FLASH_OFF

    private var mBackgroundThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null
    private var mImageReader: ImageReader? = null
    private var mPreviewSize: Size? = null
    private var mTargetUri: Uri? = null
    private var mCameraDevice: CameraDevice? = null
    private var mCaptureSession: CameraCaptureSession? = null
    private var mPreviewRequestBuilder: CaptureRequest.Builder? = null
    private var mPreviewRequest: CaptureRequest? = null
    private var mMediaRecorder: MediaRecorder? = null
    private val mCameraToPreviewMatrix = Matrix()
    private val mPreviewToCameraMatrix = Matrix()
    private val mCameraOpenCloseLock = Semaphore(1)
    private var mZoomRect = Rect()

    constructor(context: Context) : super(context)

    @SuppressLint("ClickableViewAccessibility")
    constructor(activity: MainActivity, textureView: AutoFitTextureView) : super(activity) {
        mActivity = activity
        mTextureView = textureView
        mUseFrontCamera = activity.config.lastUsedCamera == activity.getMyCamera().getBackCameraId().toString()
        mIsInVideoMode = !activity.config.initPhotoMode

        mTextureView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                mDownEventAtMS = System.currentTimeMillis()
                mDownEventAtX = event.x
                mDownEventAtY = event.y
            } else if (event.action == MotionEvent.ACTION_UP) {
                if (mIsFocusSupported && System.currentTimeMillis() - mDownEventAtMS < CLICK_MS &&
                        Math.abs(event.x - mDownEventAtX) < CLICK_DIST &&
                        Math.abs(event.y - mDownEventAtY) < CLICK_DIST) {
                    focusArea(event.x, event.y)
                }
            }

            if (mIsZoomSupported && event.pointerCount > 1) {
                handleZoom(event)
            }
            true
        }
    }

    override fun onResumed() {
        startBackgroundThread()

        if (mTextureView.isAvailable) {
            openCamera(mTextureView.width, mTextureView.height)
        } else {
            mTextureView.surfaceTextureListener = this
        }
    }

    override fun onPaused() {
        closeCamera()
        stopBackgroundThread()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
        closeCamera()
        openCamera(width, height)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        openCamera(width, height)
    }

    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("SimpleCameraBackground")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            mBackgroundThread?.quitSafely()
            mBackgroundThread?.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        try {
            setupCameraOutputs(width, height)
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            getCameraManager().openCamera(mCameraId, cameraStateCallback, mBackgroundHandler)
        } catch (e: Exception) {
        }
    }

    private fun closeCamera() {
        try {
            mCameraOpenCloseLock.acquire()
            mCaptureSession?.close()
            mCaptureSession = null
            mCameraDevice?.close()
            mCameraDevice = null
            mImageReader?.close()
            mImageReader = null
        } catch (e: Exception) {
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

    private fun closeCaptureSession() {
        mCaptureSession?.close()
        mCaptureSession = null
    }

    private fun handleZoom(event: MotionEvent) {
        val maxZoom = getCameraCharacteristics().get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) * 10
        val sensorRect = getCameraCharacteristics(mCameraId).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val currentFingerSpacing = getFingerSpacing(event)
        if (mZoomFingerSpacing != 0f) {
            if (currentFingerSpacing > mZoomFingerSpacing && maxZoom > mZoomLevel) {
                mZoomLevel++
            } else if (currentFingerSpacing < mZoomFingerSpacing && mZoomLevel > 1) {
                mZoomLevel--
            }

            val minWidth = sensorRect.width() / maxZoom
            val minHeight = sensorRect.height() / maxZoom
            val diffWidth = sensorRect.width() - minWidth
            val diffHeight = sensorRect.height() - minHeight
            var cropWidth = (diffWidth / 100 * mZoomLevel).toInt()
            var cropHeight = (diffHeight / 100 * mZoomLevel).toInt()
            cropWidth -= cropWidth and 3
            cropHeight -= cropHeight and 3
            mZoomRect = Rect(cropWidth, cropHeight, sensorRect.width() - cropWidth, sensorRect.height() - cropHeight)
            mPreviewRequestBuilder!!.set(CaptureRequest.SCALER_CROP_REGION, mZoomRect)
            mPreviewRequest = mPreviewRequestBuilder!!.build()
            mCaptureSession!!.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler)
        }
        mZoomFingerSpacing = currentFingerSpacing
    }

    private fun getFingerSpacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val buffer = reader.acquireNextImage().planes.first().buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        PhotoProcessor(mActivity, mTargetUri, mRotationAtCapture, getJPEGOrientation(), mUseFrontCamera).execute(bytes)
    }

    private fun getJPEGOrientation(): Int {
        var orientation = mSensorOrientation
        if (mUseFrontCamera) {
            orientation += 180
        }

        return orientation % 360
    }

    private fun getCurrentResolution(): MySize {
        val configMap = getCameraCharacteristics().get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val resIndex = if (mUseFrontCamera) {
            if (mIsInVideoMode) {
                mActivity.config.frontVideoResIndex
            } else {
                mActivity.config.frontPhotoResIndex
            }
        } else {
            if (mIsInVideoMode) {
                mActivity.config.backVideoResIndex
            } else {
                mActivity.config.backPhotoResIndex
            }
        }

        val outputSizes = if (mIsInVideoMode) {
            configMap.getOutputSizes(MediaRecorder::class.java)
        } else {
            configMap.getOutputSizes(ImageFormat.JPEG)
        }

        val size = outputSizes.sortedByDescending { it.width * it.height }[resIndex]
        return MySize(size.width, size.height)
    }

    private fun setupCameraOutputs(width: Int, height: Int) {
        val manager = getCameraManager()
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = getCameraCharacteristics(cameraId)

                val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: continue
                if ((mUseFrontCamera && facing == CameraCharacteristics.LENS_FACING_BACK) || !mUseFrontCamera && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                mCameraId = cameraId
                mActivity.config.lastUsedCamera = mCameraId
                val configMap = getCameraCharacteristics().get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val currentResolution = getCurrentResolution()

                if (mIsInVideoMode) {
                    mImageReader = null
                    mMediaRecorder = MediaRecorder()
                } else {
                    mImageReader = ImageReader.newInstance(currentResolution.width, currentResolution.height, ImageFormat.JPEG, 2)
                    mImageReader!!.setOnImageAvailableListener(imageAvailableListener, mBackgroundHandler)
                    mMediaRecorder = null
                }

                val displaySize = getRealDisplaySize()
                var maxPreviewWidth = displaySize.width
                var maxPreviewHeight = displaySize.height
                var rotatedPreviewWidth = width
                var rotatedPreviewHeight = height

                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                    rotatedPreviewWidth = height
                    rotatedPreviewHeight = width

                    val tmpWidth = maxPreviewWidth
                    maxPreviewWidth = maxPreviewHeight
                    maxPreviewHeight = tmpWidth
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT
                }

                val outputSizes = if (mIsInVideoMode) configMap.getOutputSizes(MediaRecorder::class.java) else configMap.getOutputSizes(SurfaceTexture::class.java)
                mPreviewSize = chooseOptimalPreviewSize(outputSizes, rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth, maxPreviewHeight, currentResolution)

                mTextureView.setAspectRatio(mPreviewSize!!.height, mPreviewSize!!.width)
                characteristics.apply {
                    mIsFlashSupported = get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    mIsZoomSupported = get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 0f > 0f
                    mIsFocusSupported = get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES).size > 1
                }
                mActivity.setFlashAvailable(mIsFlashSupported)
                return
            }
        } catch (e: Exception) {
        }
    }

    private fun chooseOptimalPreviewSize(choices: Array<Size>, textureViewWidth: Int, textureViewHeight: Int, maxWidth: Int, maxHeight: Int, aspectRatio: MySize): Size {
        val bigEnough = ArrayList<Size>()
        val notBigEnough = ArrayList<Size>()
        val width = aspectRatio.width
        val height = aspectRatio.height
        for (option in choices) {
            if (option.width <= maxWidth && option.height <= maxHeight && option.height == option.width * height / width) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        return when {
            bigEnough.isNotEmpty() -> bigEnough.minBy { it.width * it.height }!!
            notBigEnough.isNotEmpty() -> notBigEnough.maxBy { it.width * it.height }!!
            else -> choices.first()
        }
    }

    private fun getRealDisplaySize(): MySize {
        val metrics = DisplayMetrics()
        return if (isJellyBean1Plus()) {
            mActivity.windowManager.defaultDisplay.getRealMetrics(metrics)
            MySize(metrics.widthPixels, metrics.heightPixels)
        } else {
            mActivity.windowManager.defaultDisplay.getMetrics(metrics)
            MySize(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            mCameraDevice = cameraDevice
            createCameraPreviewSession()
            mActivity.setIsCameraAvailable(true)
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
            mActivity.setIsCameraAvailable(false)
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
            mActivity.setIsCameraAvailable(false)
        }
    }

    private fun createCameraPreviewSession() {
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                if (mCameraDevice == null) {
                    return
                }

                mCaptureSession = cameraCaptureSession
                try {
                    if (mIsInVideoMode) {
                        mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        mCaptureSession!!.setRepeatingRequest(mPreviewRequestBuilder!!.build(), null, mBackgroundHandler)
                    } else {
                        mPreviewRequestBuilder!!.apply {
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            setFlashAndExposure(this)
                            mPreviewRequest = build()
                        }
                        mCaptureSession!!.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler)
                    }
                    mCameraState = STATE_PREVIEW
                } catch (e: Exception) {
                }
            }

            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {}
        }

        try {
            closeCaptureSession()
            val texture = mTextureView.surfaceTexture!!
            texture.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)

            val surface = Surface(texture)
            mPreviewRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mPreviewRequestBuilder!!.addTarget(surface)

            if (mIsInVideoMode) {
                mCameraDevice!!.createCaptureSession(Arrays.asList(surface), stateCallback, mBackgroundHandler)
            } else {
                mCameraDevice!!.createCaptureSession(Arrays.asList(surface, mImageReader!!.surface), stateCallback, null)
            }
        } catch (e: Exception) {
        }
    }

    private fun updatePreview() {
        try {
            mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            mCaptureSession!!.setRepeatingRequest(mPreviewRequestBuilder!!.build(), null, mBackgroundHandler)
        } catch (e: CameraAccessException) {
        }
    }

    private val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            when (mCameraState) {
                STATE_WAITING_LOCK -> {
                    val autoFocusState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (autoFocusState == null) {
                        captureStillPicture()
                    } else if (autoFocusState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || autoFocusState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            captureStillPicture()
                        } else {
                            runPrecaptureSequence()
                        }
                    }
                }
                STATE_WAITING_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mCameraState = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        captureStillPicture()
                    }
                }
            }
        }

        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            process(result)
        }
    }

    private fun runPrecaptureSequence() {
        try {
            mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            mCameraState = STATE_WAITING_PRECAPTURE
            mCaptureSession!!.capture(mPreviewRequestBuilder!!.build(), mCaptureCallback, mBackgroundHandler)
        } catch (e: CameraAccessException) {
        }
    }

    private fun captureStillPicture() {
        try {
            if (mCameraDevice == null) {
                return
            }

            mCameraState = STATE_PICTURE_TAKEN
            mRotationAtCapture = mActivity.mLastHandledOrientation
            val captureBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(mImageReader!!.surface)
                setFlashAndExposure(this)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, getJPEGOrientation())
                set(CaptureRequest.SCALER_CROP_REGION, mZoomRect)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
            }

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    unlockFocus()
                    mActivity.toggleBottomButtons(false)
                }

                override fun onCaptureFailed(session: CameraCaptureSession?, request: CaptureRequest?, failure: CaptureFailure?) {
                    super.onCaptureFailed(session, request, failure)
                    mActivity.toggleBottomButtons(false)
                }
            }

            mCaptureSession!!.apply {
                stopRepeating()
                abortCaptures()
                capture(captureBuilder.build(), captureCallback, null)
            }
        } catch (e: CameraAccessException) {
        }
    }

    // inspired by https://gist.github.com/royshil/8c760c2485257c85a11cafd958548482
    private fun focusArea(x: Float, y: Float) {
        mActivity.drawFocusCircle(x, y)

        val captureCallbackHandler = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)

                if (request.tag == FOCUS_TAG) {
                    mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
                    mCaptureSession!!.setRepeatingRequest(mPreviewRequestBuilder!!.build(), mCaptureCallback, mBackgroundHandler)
                }
            }
        }

        mCaptureSession!!.stopRepeating()
        mPreviewRequestBuilder!!.apply {
            set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            mCaptureSession!!.capture(build(), captureCallbackHandler, mBackgroundHandler)

            // touch-to-focus inspired by OpenCamera
            val characteristics = getCameraCharacteristics()
            if (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) >= 1) {
                val focusArea = getFocusArea(x, y)
                val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val meteringRect = convertAreaToMeteringRectangle(sensorRect, focusArea)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            }

            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            setTag(FOCUS_TAG)
            mCaptureSession!!.capture(build(), captureCallbackHandler, mBackgroundHandler)
        }
    }

    private fun convertAreaToMeteringRectangle(sensorRect: Rect, focusArea: FocusArea): MeteringRectangle {
        val camera2Rect = convertRectToCamera2(sensorRect, focusArea.rect)
        return MeteringRectangle(camera2Rect, focusArea.weight)
    }

    private fun convertRectToCamera2(cropRect: Rect, rect: Rect): Rect {
        val leftF = (rect.left + 1000) / 2000f
        val topF = (rect.top + 1000) / 2000f
        val rightF = (rect.right + 1000) / 2000f
        val bottomF = (rect.bottom + 1000) / 2000f
        var left = (cropRect.left + leftF * (cropRect.width() - 1)).toInt()
        var right = (cropRect.left + rightF * (cropRect.width() - 1)).toInt()
        var top = (cropRect.top + topF * (cropRect.height() - 1)).toInt()
        var bottom = (cropRect.top + bottomF * (cropRect.height() - 1)).toInt()
        left = Math.max(left, cropRect.left)
        right = Math.max(right, cropRect.left)
        top = Math.max(top, cropRect.top)
        bottom = Math.max(bottom, cropRect.top)
        left = Math.min(left, cropRect.right)
        right = Math.min(right, cropRect.right)
        top = Math.min(top, cropRect.bottom)
        bottom = Math.min(bottom, cropRect.bottom)

        return Rect(left, top, right, bottom)
    }

    private fun getFocusArea(x: Float, y: Float): FocusArea {
        val coords = floatArrayOf(x, y)
        calculateCameraToPreviewMatrix()
        mPreviewToCameraMatrix.mapPoints(coords)
        val focusX = coords[0].toInt()
        val focusY = coords[1].toInt()

        val focusSize = 50
        val rect = Rect()
        rect.left = focusX - focusSize
        rect.right = focusX + focusSize
        rect.top = focusY - focusSize
        rect.bottom = focusY + focusSize

        if (rect.left < -1000) {
            rect.left = -1000
            rect.right = rect.left + 2 * focusSize
        } else if (rect.right > 1000) {
            rect.right = 1000
            rect.left = rect.right - 2 * focusSize
        }

        if (rect.top < -1000) {
            rect.top = -1000
            rect.bottom = rect.top + 2 * focusSize
        } else if (rect.bottom > 1000) {
            rect.bottom = 1000
            rect.top = rect.bottom - 2 * focusSize
        }

        return FocusArea(rect, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun calculateCameraToPreviewMatrix() {
        val yScale = if (mUseFrontCamera) -1 else 1
        mCameraToPreviewMatrix.apply {
            reset()
            setScale(1f, yScale.toFloat())
            postRotate(mSensorOrientation.toFloat())
            postScale(mTextureView.width / 2000f, mTextureView.height / 2000f)
            postTranslate(mTextureView.width / 2f, mTextureView.height / 2f)
            invert(mPreviewToCameraMatrix)
        }
    }

    private fun lockFocus() {
        try {
            mPreviewRequestBuilder!!.apply {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                mCameraState = STATE_WAITING_LOCK
                mCaptureSession!!.capture(build(), mCaptureCallback, mBackgroundHandler)
            }
        } catch (e: CameraAccessException) {
        }
    }

    private fun unlockFocus() {
        try {
            mPreviewRequestBuilder!!.apply {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                mCaptureSession!!.capture(build(), mCaptureCallback, mBackgroundHandler)
            }
            mCameraState = STATE_PREVIEW
            mCaptureSession!!.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler)
        } catch (e: CameraAccessException) {
        }
    }

    private fun setFlashAndExposure(builder: CaptureRequest.Builder) {
        val aeMode = if (mFlashlightState == FLASH_AUTO) CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH else CameraMetadata.CONTROL_AE_MODE_ON
        builder.apply {
            set(CaptureRequest.FLASH_MODE, getFlashlightMode())
            set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        }
    }

    private fun getCameraManager() = mActivity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun getCameraCharacteristics(cameraId: String = mCameraId) = getCameraManager().getCameraCharacteristics(cameraId)

    private fun getFlashlightMode() = when (mFlashlightState) {
        FLASH_ON -> CameraMetadata.FLASH_MODE_TORCH
        else -> CameraMetadata.FLASH_MODE_OFF
    }

    private fun setupMediaRecorder() {
        val videoSize = getCurrentResolution()
        val currVideoPath = mActivity.getOutputMediaFile(false)
        val rotation = mActivity.windowManager.defaultDisplay.rotation
        mMediaRecorder!!.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(currVideoPath)
            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(30)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            when (mSensorOrientation) {
                90 -> setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
                270 -> setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
            }
            prepare()
        }
    }

    private fun startRecording() {
        closeCaptureSession()
        setupMediaRecorder()
        val texture = mTextureView.surfaceTexture
        texture.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
        mPreviewRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        val surfaces = ArrayList<Surface>()

        val previewSurface = Surface(texture)
        surfaces.add(previewSurface)
        mPreviewRequestBuilder!!.addTarget(previewSurface)

        val recorderSurface = mMediaRecorder!!.surface
        surfaces.add(recorderSurface)
        mPreviewRequestBuilder!!.addTarget(recorderSurface)

        val captureCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession?) {
                mCaptureSession = session
                updatePreview()
                mIsRecording = true
                mActivity.runOnUiThread {
                    mMediaRecorder?.start()
                }
                mActivity.setRecordingState(true)
                mCameraState = STATE_RECORDING
            }

            override fun onConfigureFailed(session: CameraCaptureSession?) {}
        }

        mCameraDevice!!.createCaptureSession(surfaces, captureCallback, mBackgroundHandler)
    }

    private fun stopRecording() {
        mIsRecording = false
        mMediaRecorder!!.stop()
        mMediaRecorder!!.reset()
        Thread {
            closeCamera()
            openCamera(mTextureView.width, mTextureView.height)
        }.start()
        mActivity.setRecordingState(false)
    }

    override fun setTargetUri(uri: Uri) {
        mTargetUri = uri
    }

    override fun setIsImageCaptureIntent(isImageCaptureIntent: Boolean) {
        mIsImageCaptureIntent = isImageCaptureIntent
    }

    override fun setFlashlightState(state: Int) {
        mFlashlightState = state
        checkFlashlight()
    }

    override fun getCameraState() = mCameraState

    override fun showChangeResolutionDialog() {
        val oldResolution = getCurrentResolution()
        val configMap = getCameraCharacteristics().get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val photoResolutions = configMap.getOutputSizes(ImageFormat.JPEG).map { MySize(it.width, it.height) } as ArrayList
        val videoResolutions = configMap.getOutputSizes(MediaRecorder::class.java).map { MySize(it.width, it.height) } as ArrayList
        ChangeResolutionDialog(mActivity, mUseFrontCamera, photoResolutions, videoResolutions) {
            if (oldResolution != getCurrentResolution()) {
                closeCamera()
                openCamera(mTextureView.width, mTextureView.height)
            }
        }
    }

    override fun toggleFrontBackCamera() {
        mUseFrontCamera = !mUseFrontCamera
        closeCamera()
        openCamera(mTextureView.width, mTextureView.height)
    }

    override fun toggleFlashlight() {
        val newState = ++mFlashlightState % if (mIsInVideoMode) 2 else 3
        setFlashlightState(newState)
    }

    override fun tryTakePicture() {
        if (mIsFocusSupported) {
            lockFocus()
        } else {
            captureStillPicture()
        }
    }

    override fun toggleRecording() {
        if (mCameraDevice == null || !mTextureView.isAvailable || mPreviewSize == null) {
            return
        }

        if (mIsRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    override fun tryInitVideoMode() {
        initVideoMode()
    }

    override fun initPhotoMode() {
        mIsInVideoMode = false
        closeCamera()
        openCamera(mTextureView.width, mTextureView.height)
    }

    override fun initVideoMode(): Boolean {
        mIsInVideoMode = true
        closeCamera()
        openCamera(mTextureView.width, mTextureView.height)
        return true
    }

    override fun checkFlashlight() {
        if ((mCameraState == STATE_PREVIEW || mCameraState == STATE_RECORDING) && mIsFlashSupported) {
            setFlashAndExposure(mPreviewRequestBuilder!!)
            mPreviewRequest = mPreviewRequestBuilder!!.build()
            mCaptureSession?.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler)
            mActivity.updateFlashlightState(mFlashlightState)
        }
    }

    override fun deviceOrientationChanged() {}

    override fun resumeCamera() = true

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}
}
