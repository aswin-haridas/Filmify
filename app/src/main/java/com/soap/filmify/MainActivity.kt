package com.soap.filmify

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.renderscript.*
import com.soap.filmify.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {
    // View binding and image capture variables
    private lateinit var viewBinding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private val progressBar: ProgressBar by lazy { findViewById(R.id.progressBar) }
    private val handler = Handler(Looper.getMainLooper())


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)


        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
        // Set up the listener for the take photo button
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }

        // Create a single thread executor for camera operations
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    // Function for starting the camera
    private fun startCamera() {
        // Use ProcessCameraProvider to bind camera lifecycle to lifecycle owner
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        Log.i("TAG", "camera started")
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            // Set up preview and image capture use cases
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            progressBar.visibility = View.GONE

            // Select back camera as default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind all previously bound use cases and bind the new ones to the camera
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showProgressBar() {
        progressBar.visibility = View.VISIBLE
        handler.postDelayed({
            progressBar.visibility = View.GONE
        }, 1000)
    }

    // Function for taking a photo
    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return
        Log.i("TAG", "pic clicked")

        val preview = viewBinding.viewFinder
        val alphaAnimation = AlphaAnimation(0.0f, 1.0f)
        alphaAnimation.duration = 600  // Set the animation duration to 500 ms

        // Start the animation on the Preview view
        preview.startAnimation(alphaAnimation)
        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/filmify")
            }
        }
        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()
        // Set up image capture listener, which is triggered after photo has been taken
        imageCapture.takePicture(outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                @SuppressLint("SetTextI18n")
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {

                    // Retrieve the saved image URI
                    val savedUri = output.savedUri ?: return

                    // Load the image into a Bitmap object
                    val inputStream = contentResolver.openInputStream(savedUri)
                    val originalBitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (findViewById<ToggleButton>(R.id.black_and_white_button).isChecked){

                        val resultBitmap = convertToGrayscale(originalBitmap)
                        val outputStream = contentResolver.openOutputStream(savedUri)
                        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                        outputStream?.close()

                    }else{
                        // Scale down the bitmap to a smaller size
                        val scaleFactor = 0.25f // Scale factor of 0.5 will reduce the size to half
                        val downscale = Bitmap.createScaledBitmap(
                            originalBitmap,
                            (originalBitmap.width * scaleFactor).toInt(),
                            (originalBitmap.height * scaleFactor).toInt(),
                            true
                        )
                        // Apply a blur filter to the scaled-down image & blending with the old bitmap with 100 alpha
                        if (findViewById<ToggleButton>(R.id.modebutton).isChecked) {
                            val blurredBitmap = applyGaussianBlur(downscale, 25f)
                            val blurredBitmap1 = applyGaussianBlur(blurredBitmap, 25f)
                            val upscale = Bitmap.createScaledBitmap(
                                blurredBitmap1,
                                (blurredBitmap1.width * 4.0f).toInt(),
                                (blurredBitmap1.height * 4.0f).toInt(),
                                true
                            )
                            val resultBitmap = blendBitmaps(originalBitmap, upscale, 200)
                            val outputStream = contentResolver.openOutputStream(savedUri)
                            resultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                            outputStream?.close()
                        } else {
                            val blurredBitmap = applyGaussianBlur(downscale, 10f)
                            val blurredBitmap1 = applyGaussianBlur(blurredBitmap, 10f)
                            val upscale = Bitmap.createScaledBitmap(
                                blurredBitmap1,
                                (blurredBitmap1.width * 4.0f).toInt(),
                                (blurredBitmap1.height * 4.0f).toInt(),
                                true
                            )
                            val resultBitmap = blendBitmaps(originalBitmap, upscale, 200)
                            val outputStream = contentResolver.openOutputStream(savedUri)
                            resultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                            outputStream?.close()
                        }
                    }
                    showProgressBar()

                }
            })
    }


    fun convertToGrayscale(bitmap: Bitmap): Bitmap {
        val grayMatrix = ColorMatrix()
        grayMatrix.setSaturation(0f)

        val grayPaint = Paint()
        grayPaint.colorFilter = ColorMatrixColorFilter(grayMatrix)

        val grayBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayBitmap)
        canvas.drawBitmap(bitmap, 0f, 0f, grayPaint)

        return grayBitmap
    }



    fun applyGaussianBlur(bitmap: Bitmap, radius: Float): Bitmap {
        // Create a RenderScript context
        val rs = RenderScript.create(this)

        // Create an input allocation from the bitmap
        val input = Allocation.createFromBitmap(rs, bitmap)

        // Create an output allocation with the same dimensions as the input
        val output = Allocation.createTyped(rs, input.type)
        // Create a Gaussian blur script
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

        // Set the blur radius
        script.setRadius(radius)

        // Run the script
        script.setInput(input)
        script.forEach(output)

        // Copy the output allocation to a new bitmap
        val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        output.copyTo(outputBitmap)

        // Release the resources
        rs.destroy()

        return outputBitmap
    }

    fun blendBitmaps(originalBitmap: Bitmap, blurredBitmap: Bitmap, alpha: Int): Bitmap {
        // Create a new bitmap for the final result
        val resultBitmap = Bitmap.createBitmap(
            originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888
        )
        // Create a canvas to draw on the result bitmap
        val canvas = Canvas(resultBitmap)
        // Draw the original bitmap on the canvas
        canvas.drawBitmap(originalBitmap, 0f, 0f, null)

        // Create a paint object with the specified alpha value
        val paint = Paint()
        paint.alpha = alpha
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

        // Draw the blurred bitmap on the canvas with the paint object
        canvas.drawBitmap(blurredBitmap, 0f, 0f, paint)
        // Release the resources used by the blurred bitmap
        blurredBitmap.recycle()

        return resultBitmap
    }


    // Function to check if all required permissions are granted
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }


    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10

        // Define the list of required permissions.
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
        ).apply {
            // If the device is running on Android 9 (Pie) or below, then also add WRITE_EXTERNAL_STORAGE permission.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()


    }

    // Handle the result of the permission request.
    @SuppressLint("MissingSuperCall")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                // If all permissions are granted, then start the camera.
                startCamera()
            } else {
                // If permissions are not granted, then show a message and finish the activity.
                Toast.makeText(
                    this, "Permissions not granted by the user.", Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}



