package com.example.qr_attendance1

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter

class TEACHER_QR : AppCompatActivity() {

    private lateinit var generateQrButton: Button
    private lateinit var endSessionButton: Button
    private lateinit var qrCodeImageView: ImageView
    private lateinit var subjectCodeEditText: EditText
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var db: FirebaseFirestore
    private lateinit var mAuth: FirebaseAuth
    private lateinit var sessionManager: AttendanceSessionManager
    private lateinit var currentUserID: String
    private var currentSessionId: String? = null
    private var currentSubjectCode: String? = null

    companion object {
        private const val TAG = "TEACHER_QR"
        private const val QR_CODE_SIZE = 512
        private const val SESSION_ID_KEY = "sessionId"
        private const val SUBJECT_CODE_KEY = "subjectCode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_qr)

        // Firebase setup
        db = FirebaseFirestore.getInstance()
        mAuth = FirebaseAuth.getInstance()
        sessionManager = AttendanceSessionManager(db)

        // Get userId from intent or current user
        setupUserId()

        // Initialize UI components
        setupUIComponents()

        // Restore saved state
        restoreSavedState(savedInstanceState)

        // Load subject code from user profile
        loadSavedSubjectCode()

        // Set up bottom navigation
        setupBottomNavigation()

        // Check for active session
        checkForActiveSession()
    }

    private fun setupUserId() {
        if (intent.hasExtra("USER_ID")) {
            currentUserID = intent.getStringExtra("USER_ID") ?: ""
            Log.d(TAG, "Received userId from intent: $currentUserID")
        } else {
            val currentUser = mAuth.currentUser
            if (currentUser != null) {
                currentUserID = currentUser.uid
                Log.d(TAG, "Using current Firebase user ID: $currentUserID")
            } else {
                Toast.makeText(this, "Please sign in to continue", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }
    }

    private fun setupUIComponents() {
        generateQrButton = findViewById(R.id.generateQrButton)
        endSessionButton = findViewById(R.id.endSessionButton)
        qrCodeImageView = findViewById(R.id.qrCodeImageView)
        subjectCodeEditText = findViewById(R.id.subjectCodeEditText)
        bottomNavigationView = findViewById(R.id.bottom_navigation1)

        // Initially disable buttons until we've loaded data
        generateQrButton.isEnabled = false
        endSessionButton.visibility = View.GONE

        // Set click listeners
        generateQrButton.setOnClickListener {
            val enteredCode = subjectCodeEditText.text.toString().trim()
            if (enteredCode.isNotEmpty()) {
                saveSubjectCode(enteredCode)
                generateQrCode()
            } else {
                Toast.makeText(this, "Please enter a subject code", Toast.LENGTH_SHORT).show()
            }
        }

        endSessionButton.setOnClickListener { endCurrentSession() }
    }

    private fun restoreSavedState(savedInstanceState: Bundle?) {
        currentSessionId = savedInstanceState?.getString(SESSION_ID_KEY)
        currentSubjectCode = savedInstanceState?.getString(SUBJECT_CODE_KEY)

        if (currentSessionId != null && currentSubjectCode != null) {
            qrCodeImageView.visibility = View.VISIBLE
            endSessionButton.visibility = View.VISIBLE
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.teacherqr -> true
                R.id.record -> {
                    startActivity(Intent(this, TeacherHistoryActivity::class.java))
                    finish()
                    true
                }
                R.id.LOGOUT -> {
                    mAuth.signOut()
                    startActivity(Intent(this, student_login::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
        bottomNavigationView.selectedItemId = R.id.bottom_navigation1
    }


    private fun checkForActiveSession() {
        sessionManager.findActiveSessionForTeacher(
            currentUserID,
            onSessionFound = { sessionId, subjectCode ->
                currentSessionId = sessionId
                currentSubjectCode = subjectCode

                // Generate and display QR code
                if (subjectCode != null) {
                    subjectCodeEditText.setText(subjectCode)
                    val qrContent = QRCodeUtils.createAttendanceQRContent(sessionId, subjectCode)
                    val qrBitmap = generateQrCodeImage(qrContent)
                    if (qrBitmap != null) {
                        qrCodeImageView.setImageBitmap(qrBitmap)
                        qrCodeImageView.visibility = View.VISIBLE
                        endSessionButton.visibility = View.VISIBLE
                    }
                }

                Toast.makeText(this, "Active session restored", Toast.LENGTH_SHORT).show()
            },
            onNoSessionFound = {
                // No active session, just enable the generate button
                generateQrButton.isEnabled = true
            },
            onFailure = { e ->
                Log.e(TAG, "Error checking for active session", e)
                Toast.makeText(this, "Failed to check for active sessions", Toast.LENGTH_SHORT).show()
                generateQrButton.isEnabled = true
            }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SESSION_ID_KEY, currentSessionId)
        outState.putString(SUBJECT_CODE_KEY, currentSubjectCode)
    }

    private fun loadSavedSubjectCode() {
        db.collection("users").document(currentUserID)
            .get()
            .addOnSuccessListener { doc ->
                currentSubjectCode = doc.getString("subjectCode")
                if (currentSubjectCode != null) {
                    Log.d(TAG, "Subject code: $currentSubjectCode")
                    subjectCodeEditText.setText(currentSubjectCode)
                }
                // Always enable the button, even if subjectCode is null
                generateQrButton.isEnabled = true
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load Subject Code", Toast.LENGTH_SHORT).show()
                // Even if loading fails, enable the button so user can enter a new code
                generateQrButton.isEnabled = true
            }
    }

    private fun saveSubjectCode(code: String) {
        currentSubjectCode = code
        db.collection("users").document(currentUserID)
            .update("subjectCode", code)
            .addOnSuccessListener {
                Log.d(TAG, "Subject code saved: $code")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to save subject code: ${e.message}", e)
                Toast.makeText(this, "Failed to save subject code", Toast.LENGTH_SHORT).show()
            }
    }

    private fun generateQrCode() {
        if (currentSubjectCode.isNullOrBlank()) {
            Toast.makeText(this, "Please enter a valid subject code", Toast.LENGTH_SHORT).show()
            return
        }

        // Generate a new session ID
        val sessionId = QRCodeUtils.generateSessionId()
        val qrCodeContent = QRCodeUtils.createAttendanceQRContent(sessionId, currentSubjectCode!!)
        val qrCodeBitmap = generateQrCodeImage(qrCodeContent)

        if (qrCodeBitmap != null) {
            // Create the session in Firebase
            sessionManager.createSession(
                sessionId = sessionId,
                teacherId = currentUserID,
                subjectCode = currentSubjectCode!!,
                onSuccess = { newSessionId ->
                    // Update UI
                    qrCodeImageView.setImageBitmap(qrCodeBitmap)
                    qrCodeImageView.visibility = View.VISIBLE
                    endSessionButton.visibility = View.VISIBLE

                    // Update tracking variables
                    currentSessionId = newSessionId

                    Toast.makeText(this, "QR Code Generated!", Toast.LENGTH_SHORT).show()

                    // Removed auto-navigation to history activity
                },
                onFailure = { e ->
                    Toast.makeText(this, "Failed to create session: ${e.message}", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Failed to create session", e)
                }
            )
        } else {
            Toast.makeText(this, "QR Code generation failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateQrCodeImage(data: String): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, QR_CODE_SIZE, QR_CODE_SIZE)
            val bmp = Bitmap.createBitmap(QR_CODE_SIZE, QR_CODE_SIZE, Bitmap.Config.RGB_565)

            for (x in 0 until QR_CODE_SIZE) {
                for (y in 0 until QR_CODE_SIZE) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            bmp
        } catch (e: WriterException) {
            Log.e(TAG, "QR generation error: ${e.message}", e)
            null
        }
    }

    private fun endCurrentSession() {
        currentSessionId?.let { sessionId ->
            sessionManager.endSession(
                sessionId = sessionId,
                onSuccess = {
                    // UI cleanup
                    Toast.makeText(this, "Session ended", Toast.LENGTH_SHORT).show()
                    qrCodeImageView.setImageBitmap(null)
                    qrCodeImageView.visibility = View.GONE
                    endSessionButton.visibility = View.GONE

                    // Reset session tracking
                    currentSessionId = null

                    // Navigate to history
                    navigateToHistory()
                },
                onFailure = { e ->
                    Toast.makeText(this, "Failed to end session: ${e.message}", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Failed to end session", e)
                }
            )
        } ?: run {
            Toast.makeText(this, "No active session to end", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToHistory() {
        val intent = Intent(this, TeacherHistoryActivity::class.java).apply {
            putExtra("USER_ID", currentUserID)
            // Pass the current session ID if there is one
            currentSessionId?.let {
                putExtra("SESSION_ID", it)
            }
        }
        startActivity(intent)
    }
}