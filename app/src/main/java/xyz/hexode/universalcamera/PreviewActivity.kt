package xyz.hexode.universalcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_preview.*
import java.io.File
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


class PreviewActivity : AppCompatActivity() {

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val mCameraOpenCloseLock = Semaphore(1)

    private val mSpinnerCameraAdapter by lazy {
        ArrayAdapter<CameraSpinnerItem>(this, R.layout.spinner_camera_item)
    }

    private val mCameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var mBackgroundThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null
    private val mTempSaveFile by lazy {
        File("${getExternalFilesDir(null)}/.tmp.jpg")
    }
    private val mSaveFile by lazy {
        File("${getExternalFilesDir(null)}/pic.jpg")
    }
    private lateinit var mCaptureRequestBuilder: CaptureRequest.Builder
    private lateinit var mPreviewRequest: CaptureRequest
    private var mSelectedCameraDevice: CameraDevice? = null
    private lateinit var previewSize: Size
    private var mImageReader: ImageReader? = null
    private var mCaptureSession: CameraCaptureSession? = null
    private val mTextureListener = object :
            TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, width: Int, height: Int) =
                configureTransform(width, height)

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) = Unit
        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?) = true
        override fun onSurfaceTextureAvailable(p0: SurfaceTexture?, width: Int, height: Int) =
                openCamera((spinnerSelectedCamera.selectedItem as CameraSpinnerItem).cameraId,
                        width,
                        height)
    }

    /**
     * A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture.
     */
    private val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            when (mState) {
                STATE_PREVIEW -> Unit // Do nothing when the camera preview is working normally.
                STATE_WAITING_LOCK -> capturePicture(result)
                STATE_WAITING_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        private fun capturePicture(result: CaptureResult) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            when (afState) {
                null, CaptureResult.CONTROL_AF_STATE_INACTIVE -> captureStillPicture()
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    when (aeState) {
                        null, CaptureResult.CONTROL_AE_STATE_CONVERGED -> {
                            mState = STATE_PICTURE_TAKEN
                            captureStillPicture()
                        }
                        else -> runPrecaptureSequence()
                    }
                }
            }
        }

        override fun onCaptureProgressed(session: CameraCaptureSession,
                                         request: CaptureRequest,
                                         partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult) {
            process(result)
        }
    }

    private var mState = STATE_PREVIEW

    val CameraDevice.size: Size
        get() =
            mCameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                    .getOutputSizes(SurfaceTexture::class.java)[0]


    private val CameraDevice.isFlashSupported: Boolean
        get() =
            mCameraManager.getCameraCharacteristics(this.id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    companion object {
        val TAG: String = PreviewActivity::class.java.simpleName
        const val MY_PERMISSIONS_REQUEST_CAMERA = 0
        private val SCREEN_ORIENTATIONS_TO_JPEG = SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }
        /**
         * Max preview width that is guaranteed by Camera2 API
         */
        private const val MAX_PREVIEW_WIDTH = 1920

        /**
         * Max preview height that is guaranteed by Camera2 API
         */
        private const val MAX_PREVIEW_HEIGHT = 1080

        /**
         * Camera state: Showing camera preview.
         */
        private const val STATE_PREVIEW = 0

        /**
         * Camera state: Waiting for the focus to be locked.
         */
        private const val STATE_WAITING_LOCK = 1

        /**
         * Camera state: Waiting for the exposure to be precapture state.
         */
        private const val STATE_WAITING_PRECAPTURE = 2

        /**
         * Camera state: Waiting for the exposure state to be something other than precapture.
         */
        private const val STATE_WAITING_NON_PRECAPTURE = 3

        /**
         * Camera state: Picture was taken.
         */
        private const val STATE_PICTURE_TAKEN = 4

        /**
         * Given `choices` of `Size`s supported by a camera, choose the smallest one that
         * is at least as large as the respective texture view size, and that is at most as large as
         * the respective max size, and whose aspect ratio matches with the specified value. If such
         * size doesn't exist, choose the largest one that is at most as large as the respective max
         * size, and whose aspect ratio matches with the specified value.
         *
         * @param choices           The list of sizes that the camera supports for the intended
         *                          output class
         * @param textureViewWidth  The width of the texture view relative to sensor coordinate
         * @param textureViewHeight The height of the texture view relative to sensor coordinate
         * @param maxWidth          The maximum width that can be chosen
         * @param maxHeight         The maximum height that can be chosen
         * @param aspectRatio       The aspect ratio
         * @return The optimal `Size`, or an arbitrary one if none were big enough
         */
        @JvmStatic
        private fun chooseOptimalSize(
                choices: Array<Size>,
                textureViewWidth: Int,
                textureViewHeight: Int,
                maxWidth: Int,
                maxHeight: Int,
                aspectRatio: Size
        ): Size {

            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = ArrayList<Size>()
            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = ArrayList<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight &&
                        option.height == option.width * h / w) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.
            return when {
                bigEnough.size > 0 -> Collections.min(bigEnough, CompareSizesByArea())
                notBigEnough.size > 0 -> Collections.max(notBigEnough, CompareSizesByArea())
                else -> {
                    Log.e(TAG, "Couldn't find any suitable preview size")
                    choices[0]
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        textureViewPreview.surfaceTextureListener = mTextureListener
        buttonTakePicture.setOnClickListener { takePicture() }
        buttonAcceptCapture.setOnClickListener {
            updateUI(false)
            mTempSaveFile.copyTo(mSaveFile, true)
            Toast.makeText(
                    this@PreviewActivity,
                    "Saved: $mSaveFile",
                    Toast.LENGTH_SHORT).show()
            unlockFocus()
        }
        buttonRejectCapture.setOnClickListener {
            updateUI(false)
            unlockFocus()
        }

        populateCameraSpinner()
    }

    private fun updateUI(isValidationMode: Boolean) {
        if (isValidationMode) {
            buttonAcceptCapture.show()
            buttonRejectCapture.show()
            buttonTakePicture.hide()
        } else {
            buttonAcceptCapture.hide()
            buttonRejectCapture.hide()
            buttonTakePicture.show()
        }
    }

    override fun onResume() {
        super.onResume()
        mBackgroundThread = HandlerThread("CameraBackground").also {
            it.start()
            mBackgroundHandler = Handler(it.looper)
        }
        if (textureViewPreview.isAvailable) {
            val selectedCameraId = (spinnerSelectedCamera.selectedItem as CameraSpinnerItem).cameraId
            openCamera(selectedCameraId, textureViewPreview.width, textureViewPreview.height)
        } else {
            textureViewPreview.surfaceTextureListener = mTextureListener
        }

        spinnerSelectedCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(parent: AdapterView<*>?,
                                        v: View?,
                                        position: Int,
                                        id: Long) {
                if (textureViewPreview.isAvailable) {
                    if (spinnerSelectedCamera.adapter is ArrayAdapter<*>) {
                        closeCamera()
                        mSpinnerCameraAdapter.getItem(position)?.let {
                            openCamera(it.cameraId,
                                    textureViewPreview.width,
                                    textureViewPreview.height)
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        spinnerSelectedCamera.onItemSelectedListener = null

        // Close camera
        closeCamera()

        // Stop background thread
        mBackgroundThread?.quitSafely()
        try {
            mBackgroundThread?.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun requestCameraPermission() {
        // Permission is not granted
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {

            // Permission is not granted
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                            Manifest.permission.CAMERA)) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.CAMERA),
                        MY_PERMISSIONS_REQUEST_CAMERA)

                // MY_PERMISSIONS_REQUEST_CAMERA is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        }

    }

    private fun openCamera(cameraId: String, width: Int, height: Int) {

        // Set up camera outputs.
        val map = mCameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        // For still image captures, we use the largest available size.
        val largest = Collections.max(
                Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)),
                CompareSizesByArea())
        mImageReader = ImageReader
                .newInstance(largest.width, largest.height, ImageFormat.JPEG, 2)
                .apply {
                    setOnImageAvailableListener(
                            {
                                mBackgroundHandler
                                        ?.post(ImageSaver(it.acquireNextImage(), mTempSaveFile))
                            },
                            mBackgroundHandler
                    )
                }

        // Find out if we need to swap dimension to get the preview size relative to sensor
        // coordinate.
        val displayRotation = windowManager.defaultDisplay.rotation

        val swappedDimensions = areDimensionsSwapped(cameraId, displayRotation)

        val displaySize = Point()
        windowManager.defaultDisplay.getSize(displaySize)
        val rotatedPreviewWidth = if (swappedDimensions) height else width
        val rotatedPreviewHeight = if (swappedDimensions) width else height
        var maxPreviewWidth = if (swappedDimensions) displaySize.y else displaySize.x
        var maxPreviewHeight = if (swappedDimensions) displaySize.x else displaySize.y

        if (maxPreviewWidth > MAX_PREVIEW_WIDTH) maxPreviewWidth = MAX_PREVIEW_WIDTH
        if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) maxPreviewHeight = MAX_PREVIEW_HEIGHT

        // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
        // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
        // garbage capture data.
        previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                rotatedPreviewWidth, rotatedPreviewHeight,
                maxPreviewWidth, maxPreviewHeight,
                largest)

        // We fit the aspect ratio of TextureView to the size of preview we picked.
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            textureViewPreview.setAspectRatio(previewSize.width, previewSize.height)
        } else {
            textureViewPreview.setAspectRatio(previewSize.height, previewSize.width)
        }

        configureTransform(width, height)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                    throw RuntimeException("Time out waiting to lock camera opening.")
                }
                mCameraManager.openCamera(cameraId,
                        object : CameraDevice.StateCallback() {
                            override fun onOpened(cameraDevice: CameraDevice) {
                                mCameraOpenCloseLock.release()
                                mSelectedCameraDevice = cameraDevice
                                createCameraPreview(cameraDevice)
                            }

                            override fun onDisconnected(cameraDevice: CameraDevice?) {
                                mCameraOpenCloseLock.release()
                                cameraDevice?.close()
                                mSelectedCameraDevice = null
                            }

                            override fun onError(cameraDevice: CameraDevice?, error: Int) {
                                Log.e(TAG,
                                        "Error ($error) while opening camera. Terminating Activity!")
                                onDisconnected(cameraDevice)
                                finish()
                            }

                        }, mBackgroundHandler)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "", e)
            }
        } else {
            requestCameraPermission()
        }
    }

    private fun closeCamera() {
        try {
            mCameraOpenCloseLock.acquire()
            mCaptureSession?.close()
            mCaptureSession = null
            mSelectedCameraDevice?.close()
            mSelectedCameraDevice = null
            mImageReader?.close()
            mImageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

    private fun takePicture() {
        lockFocus()
    }

    private fun lockFocus() {
        try {
            // This is how to tell the camera to trigger.
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            // Tell #captureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE
            mCaptureSession?.capture(mCaptureRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "", e)
        }

    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private fun unlockFocus() {
        try {
            // Reset the auto-focus trigger
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            setAutoFlash(mCaptureRequestBuilder)
            mCaptureSession?.capture(mCaptureRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler)
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW
            mCaptureSession?.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "", e)
        }

    }

    private fun createCameraPreview(cameraDevice: CameraDevice) {
        try {
            textureViewPreview.surfaceTexture.setDefaultBufferSize(previewSize.width,
                    previewSize.height)
            val surface = Surface(textureViewPreview.surfaceTexture)
            mCaptureRequestBuilder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }
            cameraDevice.createCaptureSession(arrayListOf(surface, mImageReader?.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigureFailed(p0: CameraCaptureSession?) {
                            Log.e(TAG, "Capture session configuration failed!")
                        }

                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            mSelectedCameraDevice ?: return
                            mCaptureSession = cameraCaptureSession

                            try {
                                // Auto focus should be continuous for camera preview.
                                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                // Flash is automatically enabled when necessary.
                                setAutoFlash(mCaptureRequestBuilder)

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mCaptureRequestBuilder.build()
                                mCaptureSession?.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, null)
                            } catch (e: CameraAccessException) {
                                Log.e(TAG, "", e)
                            } catch (e: IllegalStateException) {
                                Log.e(TAG, "", e)
                            }
                        }
                    }, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "", e)
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * [.captureCallback] from both [.lockFocus].
     */
    private fun captureStillPicture() {
        try {
            mSelectedCameraDevice?.let {
                val rotation = windowManager.defaultDisplay.rotation

                // This is the CaptureRequest.Builder that we use to take a picture.
                val captureBuilder = it.createCaptureRequest(
                        CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(mImageReader!!.surface)

                    // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
                    // We have to take that into account and rotate JPEG properly.
                    // For devices with orientation of 90, we return our mapping from ORIENTATIONS.
                    // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
                    val sensorOrientation = mCameraManager
                            .getCameraCharacteristics(mSelectedCameraDevice!!.id)
                            .get(CameraCharacteristics.SENSOR_ORIENTATION)
                    set(CaptureRequest.JPEG_ORIENTATION,
                            (SCREEN_ORIENTATIONS_TO_JPEG.get(rotation) + (sensorOrientation
                                    ?: 0) + 270) % 360)

                    // Use the same AE and AF modes as the preview.
                    set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.also { setAutoFlash(it) }

                val captureCallback = object : CameraCaptureSession.CaptureCallback() {

                    override fun onCaptureCompleted(session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult) {
                        runOnUiThread {
                            updateUI(true)
                        }
//                    unlockFocus() --> wait until user validation
                    }
                }

                mCaptureSession?.apply {
                    stopRepeating()
                    abortCaptures()
                    capture(captureBuilder.build(), captureCallback, null)
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "", e)
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in [.captureCallback] from [.lockFocus].
     */
    private fun runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            // Tell #captureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE
            mCaptureSession?.capture(mCaptureRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
        if (mSelectedCameraDevice?.isFlashSupported == true) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = Math.max(
                    viewHeight.toFloat() / previewSize.height,
                    viewWidth.toFloat() / previewSize.width)
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureViewPreview.setTransform(matrix)
    }

    /**
     * Determines if the dimensions are swapped given the phone's current rotation.
     *
     * @param displayRotation The current rotation of the display     *
     * @return true if the dimensions are swapped, false otherwise.
     */
    private fun areDimensionsSwapped(cameraId: String, displayRotation: Int): Boolean {
        var swappedDimensions = false
        val sensorOrientation = mCameraManager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION)
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true
                }
            }
            else -> {
                Log.e(TAG, "Display rotation is invalid: $displayRotation")
            }
        }
        return swappedDimensions
    }

    private data class CameraSpinnerItem(val cameraId: String, val cameraName: String) {
        override fun toString(): String {
            return "$cameraId: $cameraName"
        }
    }

    private fun populateCameraSpinner() {

        for (cameraId in mCameraManager.cameraIdList) {
            val cameraName = when (mCameraManager.getCameraCharacteristics(cameraId).get(
                    CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }
            mSpinnerCameraAdapter.add(CameraSpinnerItem(cameraId, cameraName))
        }
        spinnerSelectedCamera.adapter = mSpinnerCameraAdapter
    }
}
