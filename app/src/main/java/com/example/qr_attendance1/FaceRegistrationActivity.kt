package com.example.qr_attendance1

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.facenet.FaceNetModel
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceRegistrationActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var statusTextView: TextView
    private lateinit var captureButton: Button
    private lateinit var faceNetModel: FaceNetModel
    private lateinit var firestore: FirebaseFirestore

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var isProcessingImage = false

    // User data received from signup activity
    private lateinit var userId: String
    private lateinit var userEmail: String
    private lateinit var userTupId: String
    private lateinit var userName: String
    private lateinit var userRole: String

    private val TAG = "FaceRegistration"
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_registration)

        // Extract user data from intent
        intent?.let {
            userId = it.getStringExtra(RegistrationConstants.EXTRA_USER_ID) ?: ""
            userEmail = it.getStringExtra(RegistrationConstants.EXTRA_EMAIL) ?: ""
            userTupId = it.getStringExtra(RegistrationConstants.EXTRA_TUPID) ?: ""
            userName = it.getStringExtra(RegistrationConstants.EXTRA_NAME) ?: ""
            userRole = it.getStringExtra(RegistrationConstants.EXTRA_USER_ROLE) ?: ""

            if (userId.isEmpty()) {
                Toast.makeText(this, "Invalid user data received", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
                return
            }

            Log.d(TAG, "Received user data: ID=$userId, Name=$userName, Role=$userRole")
        }

        // Initialize UI components
        previewView = findViewById(R.id.facePreviewView)
        statusTextView = findViewById(R.id.statusTextView)
        captureButton = findViewById(R.id.captureButton)

        // Initialize Firebase Firestore
        firestore = FirebaseFirestore.getInstance()

        // Initialize FaceNet model
        faceNetModel = FaceNetModel(this)

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Set up capture button
        captureButton.setOnClickListener {
            if (!isProcessingImage) {
                captureAndProcessFace()
            } else {
                Toast.makeText(this, "Processing in progress...", Toast.LENGTH_SHORT).show()
            }
        }

        // Check permissions and start camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                // Get camera provider
                cameraProvider = cameraProviderFuture.get()

                // Set up preview use case
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // Set up image capture use case
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                // Select front camera
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                // Unbind any bound use cases before rebinding
                cameraProvider?.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

                statusTextView.text = "Camera ready. Position your face and tap the capture button."

            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndProcessFace() {
        val imageCapture = imageCapture ?: return
        isProcessingImage = true
        captureButton.isEnabled = false
        statusTextView.text = "Capturing image..."

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                @SuppressLint("UnsafeOptInUsageError")
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    statusTextView.text = "Processing captured image..."

                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        detectFace(image)
                    } else {
                        isProcessingImage = false
                        captureButton.isEnabled = true
                        statusTextView.text = "Failed to capture image. Try again."
                    }

                    imageProxy.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed: ${exception.message}", exception)
                    statusTextView.text = "Image capture failed. Try again."
                    isProcessingImage = false
                    captureButton.isEnabled = true
                }
            }
        )
    }

    private fun detectFace(image: InputImage) {
        // High-accuracy face detector
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()

        val detector = FaceDetection.getClient(options)

        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    statusTextView.text = "No face detected. Please try again."
                    isProcessingImage = false
                    captureButton.isEnabled = true
                    return@addOnSuccessListener
                }

                if (faces.size > 1) {
                    statusTextView.text = "Multiple faces detected. Please ensure only your face is visible."
                    isProcessingImage = false
                    captureButton.isEnabled = true
                    return@addOnSuccessListener
                }

                val face = faces[0]
                processFaceForEmbedding(image, face)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed: ${e.message}", e)
                statusTextView.text = "Face detection failed: ${e.message}"
                isProcessingImage = false
                captureButton.isEnabled = true
            }
    }

    private fun processFaceForEmbedding(image: InputImage, face: Face) {
        statusTextView.text = "Generating face embedding..."

        try {
            // Convert InputImage to Bitmap and crop the face region
            val croppedFaceBitmap = cropFaceFromImage(image, face)

            // Generate face embedding using FaceNet model
            faceNetModel.getEmbedding(croppedFaceBitmap) { embedding ->
                // Convert FloatArray to List<Double> for Firestore storage
                val embeddingList = embedding.map { it.toDouble() }

                // Store the embedding in Firestore
                saveEmbeddingToFirestore(embeddingList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing face: ${e.message}", e)
            statusTextView.text = "Error processing face. Please try again."
            isProcessingImage = false
            captureButton.isEnabled = true
        }
    }

    private fun cropFaceFromImage(image: InputImage, face: Face): Bitmap {
        val rect = face.boundingBox
        val bitmap = when (image.mediaImage != null) {
            true -> {
                // Convert MediaImage to Bitmap (simplified for example)
                val mediaImage = image.mediaImage!!
                val yBuffer = mediaImage.planes[0].buffer
                val uBuffer = mediaImage.planes[1].buffer
                val vBuffer = mediaImage.planes[2].buffer

                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()

                val nv21 = ByteArray(ySize + uSize + vSize)

                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)

                val yuvImage = android.graphics.YuvImage(
                    nv21,
                    android.graphics.ImageFormat.NV21,
                    mediaImage.width,
                    mediaImage.height,
                    null
                )

                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(
                    android.graphics.Rect(0, 0, mediaImage.width, mediaImage.height),
                    100,
                    out
                )
                val jpegBytes = out.toByteArray()
                android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            }
            false -> image.bitmapInternal ?: throw IllegalArgumentException("Unable to get bitmap")
        }

        // Ensure the cropped region is within the image bounds
        val left = Math.max(0, rect.left)
        val top = Math.max(0, rect.top)
        val width = Math.min(bitmap.width - left, rect.width())
        val height = Math.min(bitmap.height - top, rect.height())

        // Crop the face from the bitmap
        return if (width > 0 && height > 0) {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } else {
            bitmap // Return original if the cropped region is invalid
        }
    }

    private fun saveEmbeddingToFirestore(embedding: List<Double>) {
        statusTextView.text = "Saving face data..."

        // Update the user document with the face embedding
        firestore.collection("users").document(userId)
            .update("faceEmbedding", embedding)
            .addOnSuccessListener {
                Log.d(TAG, "Face embedding saved successfully")
                statusTextView.text = "Face registration successful!"
                Toast.makeText(this, "Face registration successful!", Toast.LENGTH_SHORT).show()

                // Short delay before finishing activity
                previewView.postDelayed({
                    setResult(RESULT_OK)
                    finish()
                }, 1500)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error saving face embedding: ${e.message}", e)
                statusTextView.text = "Failed to save face data: ${e.message}"
                isProcessingImage = false
                captureButton.isEnabled = true
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceNetModel.close()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}