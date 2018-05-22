package xyz.hexode.universalcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.HandlerThread
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.android.synthetic.main.activity_preview.*
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler


class PreviewActivity : AppCompatActivity() {

    private val mBackgroundThread by lazy { HandlerThread("Camera Background") }
    private val mBackgroundHandler by lazy { Handler(mBackgroundThread.looper) }

    private lateinit var mCameraDevice: CameraDevice
    private lateinit var mCaptureSession: CameraCaptureSession
    private val captureRequestBuilder by lazy { mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); }
    private val mTextureListener = object :
            TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) = Unit
        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) = Unit
        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?) = true
        override fun onSurfaceTextureAvailable(p0: SurfaceTexture?, p1: Int, p2: Int) = openCamera()

    }

    companion object {
        val TAG = PreviewActivity::class.java.simpleName!!
        const val MY_PERMISSIONS_REQUEST_CAMERA = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        textureViewPreview.surfaceTextureListener = mTextureListener
        buttonTakePicture.setOnClickListener { takePicture() }
    }

    override fun onResume() {
        super.onResume()
        mBackgroundThread.start()
        if (textureViewPreview.isAvailable) {
            openCamera()
        } else {
            textureViewPreview.surfaceTextureListener = mTextureListener
        }
    }

    override fun onPause() {
        super.onPause()
        mBackgroundThread.quitSafely()
        try {
            mBackgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "", e)
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

    private fun openCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.last()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                cameraManager.openCamera(cameraId,
                        object : CameraDevice.StateCallback() {
                            override fun onOpened(cameraDevice: CameraDevice?) {
                                cameraDevice?.let { mCameraDevice = cameraDevice }
                                val size = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(SurfaceTexture::class.java)[0]
                                createCameraPreview(cameraDevice, size)
                            }

                            override fun onDisconnected(cameraDevice: CameraDevice?) {
                                cameraDevice?.close()
                            }

                            override fun onError(cameraDevice: CameraDevice?, error: Int) {
                                Log.e(TAG, "Error ($error) while opening camera. Terminating Activity!")
                                cameraDevice?.close()
                                finish()
                            }

                        }, null)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "", e)
            }
        } else {
            requestCameraPermission()
        }
    }

    private fun takePicture() {
        TODO()
    }

    private fun createCameraPreview(cameraDevice: CameraDevice?, size: Size) {
        try {
            textureViewPreview.surfaceTexture.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(textureViewPreview.surfaceTexture)
            captureRequestBuilder.addTarget(surface)
            cameraDevice?.createCaptureSession(arrayListOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(p0: CameraCaptureSession?) = Unit
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession?) {
                    cameraCaptureSession?.let { mCaptureSession = cameraCaptureSession }
                    updatePreview()
                }

            }, null)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "", e)
        }
    }

    private fun updatePreview() {
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        try {
            mCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }
}
