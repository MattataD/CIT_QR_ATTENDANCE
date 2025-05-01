package com.example.qr_attendance1

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.facenet.FaceNetModel
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

// Constants for configuration
private const val FACE_SIMILARITY_THRESHOLD = 0.8f
private const val CAMERA_SWITCH_DELAY_MS = 1500L
private const val MAX_FACE_DETECTION_TIME_MS = 5000L

// Separate class for handling attendance recording logic
class AttendanceService(private val firestore: FirebaseFirestore) {
    /**
     * Records student attendance for a given session.
     */
    fun recordStudentAttendance(
        sessionId: String,
        studentId: String,
        studentName: String,
        studentTupId: String,
        studentSection: String,
        subject: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val attendanceData = hashMapOf(
            "name" to studentName,
            "tupid" to studentTupId,
            "section" to studentSection,
            "subject" to subject,
            "timestamp" to Timestamp.now()
        )

        firestore.collection("attendance_sessions")
            .document(sessionId)
            .collection("records")
            .document(studentId)
            .set(attendanceData)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { exception -> onFailure(exception) }
    }
}

// Class for handling face recognition logic
class FaceRecognitionService(private val faceNetModel: FaceNetModel) {
    private var storedEmbedding: FloatArray? = null

    fun setStoredEmbedding(embedding: FloatArray?) {
        storedEmbedding = embedding
    }

    fun processFaceImage(image: InputImage, face: Face, callback: (Boolean) -> Unit) {
        try {
            val croppedFaceBitmap = cropFaceFromImage(image, face)
            val processedBitmap = mirrorBitmap(croppedFaceBitmap)

            faceNetModel.getEmbedding(processedBitmap) { liveEmbedding ->
                val isVerified = storedEmbedding?.let {
                    val distance = faceNetModel.calculateDistance(liveEmbedding, it)
                    distance < FACE_SIMILARITY_THRESHOLD
                } ?: false

                // Clean up bitmaps
                croppedFaceBitmap.recycle()
                processedBitmap.recycle()

                callback(isVerified)
            }
        } catch (e: Exception) {
            Log.e("FaceRecognition", "Error processing face image", e)
            callback(false)
        }
    }

    private fun mirrorBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.preScale(-1.0f, 1.0f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    private fun cropFaceFromImage(image: InputImage, face: Face): Bitmap {
        val rect = face.boundingBox
        val bitmap = when (image.mediaImage != null) {
            true -> convertMediaImageToBitmap(image.mediaImage!!)
            false -> image.bitmapInternal ?: throw IllegalArgumentException("Unable to get bitmap from InputImage")
        }

        // Ensure the cropped region is within bounds
        val left = max(0, rect.left)
        val top = max(0, rect.top)
        val width = min(bitmap.width - left, rect.width())
        val height = min(bitmap.height - top, rect.height())

        return if (width > 0 && height > 0) {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } else {
            bitmap
        }
    }

    private fun convertMediaImageToBitmap(mediaImage: android.media.Image): Bitmap {
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
        return android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }
}

// Main activity for student QR code and face recognition
class student_qr : AppCompatActivity() {
    private lateinit var cameraExecutor: ScheduledExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var resultTextView: TextView
    private lateinit var scanButton: Button
    private lateinit var cameraPreviewContainer: FrameLayout
    private lateinit var instructionTextView: TextView
    private lateinit var firestore: FirebaseFirestore
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var faceNetModel: FaceNetModel
    private lateinit var faceRecognitionService: FaceRecognitionService
    private lateinit var attendanceService: AttendanceService

    private var lastScannedQRData: String? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isProcessingFrame = false

    // Camera selectors
    private val backCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val frontCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    // Current camera mode
    private enum class CameraMode { QR, FACE }
    private var currentMode = CameraMode.QR

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraPreview()
            loadFaceEmbedding()
        } else {
            showUserMessage("Camera permission is required for attendance scanning")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_qr)

        initViews()
        initFirebaseComponents()
        initFaceRecognition()
        initBarcodeScanner()
        setupScanButton()
        setupBottomNavigation()
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        resultTextView = findViewById(R.id.resultTextView)
        scanButton = findViewById(R.id.scanButton)
        cameraPreviewContainer = findViewById(R.id.cameraPreviewContainer)
        instructionTextView = findViewById(R.id.instructionTextView)

        resultTextView.visibility = View.VISIBLE
        resultTextView.text = ""
        currentMode = CameraMode.QR
        updateInstructionForCurrentMode()
    }

    private fun initFirebaseComponents() {
        firestore = FirebaseFirestore.getInstance()
        firebaseAuth = FirebaseAuth.getInstance()
        attendanceService = AttendanceService(firestore)
    }

    private fun initFaceRecognition() {
        faceNetModel = FaceNetModel(this)
        faceRecognitionService = FaceRecognitionService(faceNetModel)
        // Change from:
        // cameraExecutor = Executors.newSingleThreadExecutor()
        // To:
        cameraExecutor = Executors.newSingleThreadScheduledExecutor()
    }

    private fun initBarcodeScanner() {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)
    }

    private fun setupScanButton() {
        scanButton.setOnClickListener {
            cameraPreviewContainer.visibility = View.VISIBLE
            instructionTextView.visibility = View.GONE
            resultTextView.visibility = View.VISIBLE

            currentMode = CameraMode.QR
            resultTextView.text = "Scanning for QR code... Please point camera at QR code"
            checkCameraPermissionAndStart()
        }
    }

    private fun setupBottomNavigation() {
        val bottomNavigationView: BottomNavigationView = findViewById(R.id.bottom_navigation)
        bottomNavigationView.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.qr -> true
                R.id.student -> {
                    startActivity(Intent(this, studentprofile::class.java))
                    finish()
                    true
                }
                R.id.logout -> {
                    firebaseAuth.signOut()
                    startActivity(Intent(this, student_login::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
        bottomNavigationView.selectedItemId = R.id.qr
    }

    private fun updateInstructionForCurrentMode() {
        instructionTextView.text = when (currentMode) {
            CameraMode.QR -> "Point the back camera at a QR code and tap 'Scan'"
            CameraMode.FACE -> "Position your face in the frame for verification"
        }
    }

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
            loadFaceEmbedding()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                showUserMessage("Camera initialization failed. Please restart the app.")
                Log.e("CameraInit", "Failed to get camera provider", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val cameraSelector = when(currentMode) {
            CameraMode.QR -> backCameraSelector
            CameraMode.FACE -> frontCameraSelector
        }

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val rotation = previewView.display.rotation
        preview.setTargetRotation(rotation)

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(rotation)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (!isProcessingFrame && currentMode == CameraMode.QR) {
                        analyzeImageForQR(imageProxy)
                    } else {
                        imageProxy.close()
                    }
                }
            }

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, imageAnalysis
            )
        } catch (exc: Exception) {
            showUserMessage("Camera error occurred. Please try again.")
            Log.e("CameraBind", "Use case binding failed", exc)
        }
    }

    private fun loadFaceEmbedding() {
        val userId = firebaseAuth.currentUser?.uid ?: run {
            showUserMessage("User not logged in")
            return
        }

        resultTextView.text = "Loading face data..."

        firestore.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                val embeddingList = document.get("faceEmbedding") as? List<Double>
                if (embeddingList != null) {
                    faceRecognitionService.setStoredEmbedding(embeddingList.map { it.toFloat() }.toFloatArray())
                    resultTextView.text = "Face data loaded. Ready to scan."
                } else {
                    resultTextView.text = "No face data found. Please register your face first."
                }
            }
            .addOnFailureListener { e ->
                showUserMessage("Error loading face data")
                Log.e("FaceLoad", "Error fetching face embedding", e)
            }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyzeImageForQR(imageProxy: ImageProxy) {
        isProcessingFrame = true
        val mediaImage = imageProxy.image ?: run {
            isProcessingFrame = false
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    barcodes.first().rawValue?.let { qrData ->
                        lastScannedQRData = qrData
                        resultTextView.text = "QR code detected! Switching to face verification..."
                        switchToFaceRecognitionMode()
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("QRScan", "Failed to scan QR code", e)
            }
            .addOnCompleteListener {
                isProcessingFrame = false
                imageProxy.close()
            }
    }

    private fun switchToFaceRecognitionMode() {
        currentMode = CameraMode.FACE
        updateInstructionForCurrentMode()

        cameraProvider?.let {
            it.unbindAll()
            bindCameraUseCases()
            scanButton.postDelayed({ captureImageForFace() }, CAMERA_SWITCH_DELAY_MS)
        }
    }

    private fun captureImageForFace() {
        val imageCapture = this.imageCapture ?: return

        scanButton.isEnabled = false
        resultTextView.text = "Capturing image for face verification..."

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                @SuppressLint("UnsafeOptInUsageError")
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    processImageForFace(imageProxy)
                    imageProxy.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    showUserMessage("Face image capture failed")
                    Log.e("FaceCapture", "Image capture failed", exception)
                    resetAfterProcessing()
                }
            }
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageForFace(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()

        val faceDetector = FaceDetection.getClient(options)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    verifyFaceWithTimeout(image, faces.first())
                } else {
                    showUserMessage("No face detected. Please try again.")
                    resetAfterProcessing()
                }
            }
            .addOnFailureListener { e ->
                showUserMessage("Failed to detect face")
                Log.e("FaceDetect", "Face detection failed", e)
                resetAfterProcessing()
            }
    }

    private fun verifyFaceWithTimeout(image: InputImage, face: Face) {
        val timeoutFuture = cameraExecutor.schedule({
            if (!isFinishing) {
                runOnUiThread {
                    showUserMessage("Face verification timed out. Please try again.")
                    resetAfterProcessing()
                }
            }
        }, MAX_FACE_DETECTION_TIME_MS, TimeUnit.MILLISECONDS)

        faceRecognitionService.processFaceImage(image, face) { isVerified ->
            timeoutFuture.cancel(true) // Cancel the timeout if verification completes
            runOnUiThread {
                if (isVerified) {
                    resultTextView.text = "Face verified! Processing attendance..."
                    lastScannedQRData?.let { processScannedData(it) } ?: run {
                        showUserMessage("QR code data lost. Please try again.")
                        resetAfterProcessing()
                    }
                } else {
                    showUserMessage("Face verification failed. Please try again.")
                    resetAfterProcessing()
                }
            }
        }
    }

    private fun processScannedData(qrData: String) {
        try {
            val json = JSONObject(qrData)
            val sessionId = json.getString("sessionId")
            val subjectCode = json.getString("subjectCode")

            // First verify the session is still active
            firestore.collection("attendance_sessions").document(sessionId)
                .get()
                .addOnSuccessListener { sessionDoc ->
                    // Check if session has ended
                    if (sessionDoc.getTimestamp("endTime") != null) {
                        showUserMessage("This session has already ended")
                        resetAfterProcessing()
                        return@addOnSuccessListener
                    }

                    // Verify user is logged in
                    val user = firebaseAuth.currentUser ?: run {
                        showUserMessage("User not logged in")
                        resetAfterProcessing()
                        return@addOnSuccessListener
                    }

                    // Get user data
                    firestore.collection("users").document(user.uid)
                        .get()
                        .addOnSuccessListener { userDoc ->
                            val name = userDoc.getString("name") ?: "Unknown"
                            val tupid = userDoc.getString("tupid") ?: "Unknown"
                            val section = userDoc.getString("section") ?: "Unknown"

                            recordAttendance(sessionId,subjectCode,user.uid,name,tupid,section)

                        }
                        .addOnFailureListener { e ->
                            showUserMessage("Failed to fetch user data")
                            Log.e("UserData", "Fetch failed", e)
                            resetAfterProcessing()
                        }
                }
                .addOnFailureListener { e ->
                    showUserMessage("Failed to verify session status")
                    Log.e("SessionCheck", "Verification failed", e)
                    resetAfterProcessing()
                }
        } catch (e: Exception) {
            showUserMessage("Invalid QR code format")
            Log.e("QRParse", "Invalid QR data", e)
            resetAfterProcessing()
        }
    }

    private fun recordAttendance(sessionId: String,subjectCode: String,studentId: String,studentName: String,studentTupId: String,studentSection: String ){
        attendanceService.recordStudentAttendance(
            sessionId = sessionId,
            studentId = studentId,
            studentName = studentName,
            studentTupId = studentTupId,
            studentSection = studentSection,
            subject = subjectCode,
            onSuccess = {
                showUserMessage("✅ Attendance recorded successfully!")
                resetAfterProcessing()
            },
            onFailure = { e ->
                showUserMessage("Failed to record attendance")
                Log.e("Attendance", "Recording failed", e)
                resetAfterProcessing()
            }
        )
    }

    private fun resetAfterProcessing() {
        currentMode = CameraMode.QR
        updateInstructionForCurrentMode()
        cameraPreviewContainer.visibility = View.GONE
        instructionTextView.visibility = View.VISIBLE
        scanButton.isEnabled = true
        lastScannedQRData = null
    }

    private fun showUserMessage(message: String) {
        if (!isFinishing) {
            resultTextView.text = message
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceNetModel.close()
    }
}
