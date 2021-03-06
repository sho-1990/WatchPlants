package shsato.tk.watchplant.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.WindowManager
import shsato.tk.watchplant.Logger
import shsato.tk.watchplant.util.CameraUtil
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


class Camera2(private val context: Context, private val uiHandler: Handler) {

	enum class State {
		PREVIEW, WAITING_LOCK, WAITING_PRE_CAPTURE, WAITING_NON_PRE_CAPTURE, PICTURE_TAKEN, NONE
	}

	companion object {
		private const val CAMERA_FACING = CameraCharacteristics.LENS_FACING_BACK
		private const val IMAGE_FORMAT = ImageFormat.JPEG

		private const val MAX_PREVIEW_WIDTH  = 1920
		private const val MAX_PREVIEW_HEIGHT = 1080
		private const val LOCK_FOCUS_DELAY_ON_FOCUSED   = 5000L
		private const val LOCK_FOCUS_DELAY_ON_UNFOCUSED = 1000L

		private val ORIENTATIONS = SparseIntArray()
		init {
			ORIENTATIONS.append(Surface.ROTATION_0, 90)
			ORIENTATIONS.append(Surface.ROTATION_90, 0)
			ORIENTATIONS.append(Surface.ROTATION_180, 270)
			ORIENTATIONS.append(Surface.ROTATION_270, 180)
		}

	}

	private val mCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
	private var mCameraId: String? = null
	private var mImageReader: ImageReader? = null
	private var mTexture: SurfaceTexture? = null

	private var mFlashSupported = false

	private var mCameraDevice: CameraDevice? = null
	private var mPreviewRequestBuilder: CaptureRequest.Builder? = null
	private var mCaptureSession: CameraCaptureSession? = null
	private val mCameraOpenCloseLock = Semaphore(1)
	private var mBackgroundHandler: Handler? = null

	private var mSensorOrientation = 0
	private var mPreviewSize: Size? = null
	private var mLastAfState: Int? = null

	private var mState = State.NONE

	private val mLockAutoFocusRunnable = Runnable {
		lockAutoFocus()
	}


	private val mStateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
		override fun onOpened(cameraDevice: CameraDevice) {
			mCameraOpenCloseLock.release()
			mCameraDevice = cameraDevice
			createSession(mBackgroundHandler)
		}

		override fun onDisconnected(cameraDevice: CameraDevice?) {
			mCameraOpenCloseLock.release()
			release()
		}

		override fun onError(cameraDevice: CameraDevice?, error: Int) {
			mCameraOpenCloseLock.release()
			release()
		}

	}

	private val mSessionCallback: CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
		override fun onConfigureFailed(session: CameraCaptureSession?) {
			release()
		}

		override fun onConfigured(session: CameraCaptureSession) {
			mCaptureSession = session
			createPreview(mBackgroundHandler)
		}

	}

	private val mCaptureCallback: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
		override fun onCaptureProgressed(session: CameraCaptureSession?, request: CaptureRequest?, partialResult: CaptureResult) {
			callOnCaptureResult(partialResult, false)
		}

		override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult) {
			callOnCaptureResult(result, true)
		}

		private fun callOnCaptureResult(result: CaptureResult, isCompleted: Boolean) {
			when (mState) {
				State.PREVIEW -> {
					val afState = result[CaptureResult.CONTROL_AF_STATE]
					Logger.d("$afState")
					if (afState == null || afState == mLastAfState) {
						return
					}
					when (afState) {
						CaptureResult.CONTROL_AF_STATE_INACTIVE -> lockAutoFocus()
						CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
							uiHandler.removeCallbacks(mLockAutoFocusRunnable)
							uiHandler.postDelayed(mLockAutoFocusRunnable, LOCK_FOCUS_DELAY_ON_FOCUSED)
						}
						CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
							uiHandler.removeCallbacks(mLockAutoFocusRunnable)
							uiHandler.postDelayed(mLockAutoFocusRunnable, LOCK_FOCUS_DELAY_ON_UNFOCUSED)
						}
						CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> uiHandler.removeCallbacks(mLockAutoFocusRunnable)
						CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> uiHandler.removeCallbacks(mLockAutoFocusRunnable)
					}
					mLastAfState = afState
				}
				State.WAITING_LOCK -> {
					capturePicture(result)
				}
				State.WAITING_PRE_CAPTURE -> {
					val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
					if (aeState == null ||
							aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
							aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
						mState = State.WAITING_NON_PRE_CAPTURE
					}
				}
				State.WAITING_NON_PRE_CAPTURE -> {
					val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
					if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
						mState = State.PICTURE_TAKEN
						captureStillPicture()
					}
				}
				else -> { /* do nothing */ }
			}
		}

	}

	fun setTexture(surfaceTexture: SurfaceTexture?) {
		mTexture = surfaceTexture
	}

	fun setup(width: Int, height: Int) {
		try {
			mCameraId = CameraUtil.selectCameraId(mCameraManager, CAMERA_FACING)
			if (mCameraId == null) {
				throw RuntimeException("camera id is null, selected facing $CAMERA_FACING")
			}
			val characteristics = mCameraManager.getCameraCharacteristics(mCameraId)
			val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
			val largest = CameraUtil.chooseLargestSize(map, IMAGE_FORMAT)
			mImageReader = CameraUtil.createImageReader(IMAGE_FORMAT, largest)
			if (mImageReader == null) {
				throw RuntimeException("ImageReader is null")
			}
			val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
			val displayRotation = wm.defaultDisplay.rotation
			mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
			val swappedDimensions = areDimensionsSwapped(displayRotation)

			val displaySize = Point()
			wm.defaultDisplay.getSize(displaySize)
			val rotatedPreviewWidth = if (swappedDimensions) height else width
			val rotatedPreviewHeight = if (swappedDimensions) width else height
			var maxPreviewWidth = if (swappedDimensions) displaySize.y else displaySize.x
			var maxPreviewHeight = if (swappedDimensions) displaySize.x else displaySize.y
			if (maxPreviewWidth > MAX_PREVIEW_WIDTH) maxPreviewWidth = MAX_PREVIEW_WIDTH
			if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) maxPreviewHeight = MAX_PREVIEW_HEIGHT

			mPreviewSize = CameraUtil.chooseOptimalSize(
					map.getOutputSizes(SurfaceTexture::class.java),
					rotatedPreviewWidth, rotatedPreviewHeight,
					maxPreviewWidth, maxPreviewHeight,
					largest)

			mFlashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

		} catch (e: CameraAccessException) {
			Logger.e(e.toString())
		} catch (e: NullPointerException) {
			// not supported on the device this code runs
		}
	}

	fun configureTransform(viewWidth: Int, viewHeight: Int): Matrix {
		val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		val rotation = wm.defaultDisplay.rotation
		val matrix = Matrix()
		val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
		if (mPreviewSize == null) {
			throw RuntimeException("call setup before this")
		}
		val bufferRect = RectF(0f, 0f, mPreviewSize!!.height.toFloat(), mPreviewSize!!.width.toFloat())
		val centerX = viewRect.centerX()
		val centerY = viewRect.centerY()

		if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
			bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
			val scale = Math.max(
					viewHeight.toFloat() / mPreviewSize!!.height,
					viewWidth.toFloat() / mPreviewSize!!.width)
			with(matrix) {
				setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
				postScale(scale, scale, centerX, centerY)
				postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
			}
		} else if (Surface.ROTATION_180 == rotation) {
			matrix.postRotate(180f, centerX, centerY)
		}
		return matrix
	}

	@SuppressLint("MissingPermission")
	fun open(backgroundHandler: Handler?) {
		mBackgroundHandler = backgroundHandler
		try {
			// Wait for camera to open - 2.5 seconds is sufficient
			if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
				throw RuntimeException("Time out waiting to lock camera opening.")
			}
			mCameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler)
		} catch (e: CameraAccessException) {
			Logger.e(e.toString())
		} catch (e: InterruptedException) {
			throw RuntimeException("Interrupted while trying to lock camera opening.", e)
		}
	}

	fun release() {
		try {
			mCameraOpenCloseLock.acquire()
			mCaptureSession?.close()
			mCaptureSession = null
			mCameraDevice?.close()
			mCameraDevice = null
			mImageReader?.close()
			mImageReader = null
			mCameraId = null
			mTexture = null
			mBackgroundHandler = null
		} catch (e: InterruptedException) {
			throw RuntimeException("Interrupted while trying to lock camera closing.", e)
		} finally {
			mCameraOpenCloseLock.release()
		}
	}

	fun takePicture(backgroundCallback: (imageReader: ImageReader?) -> Unit) {
		mImageReader?.setOnImageAvailableListener({
			mBackgroundHandler?.post({
				backgroundCallback.invoke(it)
			})
		}, mBackgroundHandler)
		lockFocus()
	}

	private fun createSession(handler: Handler?) {
		mTexture?.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
		val surface = Surface(mTexture)
		mPreviewRequestBuilder = mCameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
		mPreviewRequestBuilder!!.addTarget(surface)
		val outputs = listOf<Surface>(surface, mImageReader!!.surface)
		mCameraDevice?.createCaptureSession(outputs, mSessionCallback, handler)
	}

	private fun createPreview(handler: Handler?) {
		try {
			if (isAutoFocusSupported()) {
				mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
			} else {
				mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
			}
			mState = State.PREVIEW
			mCaptureSession?.setRepeatingRequest(mPreviewRequestBuilder?.build(), mCaptureCallback, handler)
		} catch (e: CameraAccessException) {
			Logger.e(e.toString())
		}
	}

	private fun areDimensionsSwapped(displayRotation: Int): Boolean {
		var swappedDimensions = false
		when (displayRotation) {
			Surface.ROTATION_0, Surface.ROTATION_180 -> {
				if (mSensorOrientation == 90 || mSensorOrientation == 270) {
					swappedDimensions = true
				}
			}
			Surface.ROTATION_90, Surface.ROTATION_270 -> {
				if (mSensorOrientation == 0 || mSensorOrientation == 180) {
					swappedDimensions = true
				}
			}
			else -> {
				Logger.d("Display rotation is invalid: $displayRotation")
			}
		}
		return swappedDimensions
	}

	private fun lockAutoFocus() {
		try {
			mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
			val captureRequest = mPreviewRequestBuilder?.build()
			mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
			mCaptureSession?.capture(captureRequest, mCaptureCallback, mBackgroundHandler)

		} catch (e: CameraAccessException) {
			Logger.e(e.toString())
		}
	}

	private fun getMinimumFocusDistance(): Float {
		if (mCameraId == null)
			return 0f

		var minimumLens: Float? = null
		try {
			val c = mCameraManager.getCameraCharacteristics(mCameraId)
			minimumLens = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
		} catch (e: Exception) {
			Logger.e("isHardwareLevelSupported Error")
		}

		return if (minimumLens != null) minimumLens else 0f
	}

	private fun isAutoFocusSupported(): Boolean {
		return isHardwareLevelSupported(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) || getMinimumFocusDistance() > 0
	}

	private fun isHardwareLevelSupported(requiredLevel: Int): Boolean {
		var res = false
		if (mCameraId == null)
			return res
		try {
			val cameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraId)

			val deviceLevel = cameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)!!
			when (deviceLevel) {
				CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> Logger.d("Camera support level: INFO_SUPPORTED_HARDWARE_LEVEL_3")
				CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> Logger.d("Camera support level: INFO_SUPPORTED_HARDWARE_LEVEL_FULL")
				CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> Logger.d("Camera support level: INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY")
				CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> Logger.d("Camera support level: INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED")
				else -> Logger.d("Unknown INFO_SUPPORTED_HARDWARE_LEVEL: $deviceLevel")
			}


			res = if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
				requiredLevel == deviceLevel
			} else {
				// deviceLevel is not LEGACY, can use numerical sort
				requiredLevel <= deviceLevel
			}

		} catch (e: Exception) {
			Logger.e("isHardwareLevelSupported Error $e")
		}

		return res
	}

	private fun lockFocus() {
		try {
			mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
			mState = State.WAITING_LOCK
			mCaptureSession?.capture(mPreviewRequestBuilder?.build(), mCaptureCallback, mBackgroundHandler)
		} catch (e: CameraAccessException) {
			Logger.e(e.toString())
		}
	}

	private fun unlockFocus() {
		try {
			// Reset the auto-focus trigger
			mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
					CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
			setAutoFlash(mPreviewRequestBuilder)
			mState = State.PREVIEW
			mCaptureSession?.capture(mPreviewRequestBuilder?.build(), mCaptureCallback,
					mBackgroundHandler)
			createPreview(mBackgroundHandler)
		} catch (e: CameraAccessException) {
			Logger.e(e.toString())
		}

	}

	private fun runPrecaptureSequence() {
		try {
			// This is how to tell the camera to trigger.
			mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
					CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
			// Tell #captureCallback to wait for the precapture sequence to be set.
			mState = State.WAITING_PRE_CAPTURE
			mCaptureSession?.capture(mPreviewRequestBuilder?.build(), mCaptureCallback, mBackgroundHandler)
		} catch (e: CameraAccessException) {
			Logger.e(e.toString())
		}

	}

	private fun capturePicture(result: CaptureResult) {
		val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
		if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
            mState = State.PICTURE_TAKEN
			captureStillPicture()
		} else {
			runPrecaptureSequence()
		}
//		val afState = result.get(CaptureResult.CONTROL_AF_STATE)
//		if (afState == null) {
//			captureStillPicture()
//		} else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
//				|| afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
//			 CONTROL_AE_STATE can be null on some devices
//			val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
//			if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
//				captureStillPicture()
//			} else {
//				runPrecaptureSequence()
//			}
//		}
	}

	private fun setAutoFlash(requestBuilder: CaptureRequest.Builder?) {
		if (mFlashSupported) {
			requestBuilder?.set(CaptureRequest.CONTROL_AE_MODE,
					CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
		}
	}

	private fun captureStillPicture() {
		try {
			if (mCameraDevice == null) return
			val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

			// This is the CaptureRequest.Builder that we use to take a picture.
			val captureBuilder = mCameraDevice?.createCaptureRequest(
					CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
				addTarget(mImageReader?.surface)

				// Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
				// We have to take that into account and rotate JPEG properly.
				// For devices with orientation of 90, we return our mapping from ORIENTATIONS.
				// For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
				set(CaptureRequest.JPEG_ORIENTATION,
						(ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360)

				// Use the same AE and AF modes as the preview.
				set(CaptureRequest.CONTROL_AF_MODE,
						CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
			}?.also { setAutoFlash(it) }

			val captureCallback = object : CameraCaptureSession.CaptureCallback() {

				override fun onCaptureCompleted(session: CameraCaptureSession,
				                                request: CaptureRequest,
				                                result: TotalCaptureResult) {
//					createPreview(mBackgroundHandler)
					unlockFocus()
				}
			}

			mCaptureSession?.also {
				it.stopRepeating()
				it.abortCaptures()
				it.capture(captureBuilder?.build(), captureCallback, null)
			}
		} catch (e: CameraAccessException) {
			Logger.e(e.toString())
		}
	}

}