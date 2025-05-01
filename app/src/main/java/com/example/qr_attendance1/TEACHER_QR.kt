package com.example.qr_attendance1

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class TEACHER_QR : AppCompatActivity() {

    private lateinit var generateQrButton: Button
    private lateinit var endSessionButton: Button
    private lateinit var sessionInfoTextView: TextView
    private lateinit var qrCodeImageView: ImageView
    private lateinit var subjectCodeEditText: EditText
    private lateinit var db: FirebaseFirestore
    private lateinit var mAuth: FirebaseAuth
    private lateinit var currentUserID: String
    private lateinit var attendanceRecordsTextView: TextView
    private var attendanceListener: ListenerRegistration? = null
    private var currentSessionId: String? = null
    private var currentSubjectCode: String? = null

    companion object {
        private const val TAG = "TEACHER_QR"
        private const val QR_CODE_SIZE = 512
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_qr)

        // Firebase
        db = FirebaseFirestore.getInstance()
        mAuth = FirebaseAuth.getInstance()

        // UI Elements
        generateQrButton = findViewById(R.id.generateQrButton)
        endSessionButton = findViewById(R.id.endSessionButton)
        sessionInfoTextView = findViewById(R.id.sessionInfoTextView)
        qrCodeImageView = findViewById(R.id.qrCodeImageView)
        subjectCodeEditText = findViewById(R.id.subjectCodeEditText)
        attendanceRecordsTextView = findViewById(R.id.attendanceRecordsTextView)


        endSessionButton.setOnClickListener { endCurrentSession() }

        val currentUser = mAuth.currentUser
        if (currentUser != null) {
            currentUserID = currentUser.uid
            loadSavedSubjectCode()
        } else {
            Toast.makeText(this, "Please sign in to continue", Toast.LENGTH_SHORT).show()
            finish()
        }

        generateQrButton.setOnClickListener {
            val enteredCode = subjectCodeEditText.text.toString().trim()
            if (enteredCode.isNotEmpty()) {
                saveSubjectCode(enteredCode)
                generateQrCode()
            } else {
                Toast.makeText(this, "Please enter a subject code", Toast.LENGTH_SHORT).show()
            }
        }

        // Restore saved state
        currentSessionId = savedInstanceState?.getString("sessionId")
        currentSubjectCode = savedInstanceState?.getString("subjectCode")

        if (currentSessionId != null && currentSubjectCode != null) {
            sessionInfoTextView.text = getString(
                R.string.session_info_format,
                currentSessionId,
                currentSubjectCode
            )
            qrCodeImageView.visibility = View.VISIBLE
            endSessionButton.visibility = View.VISIBLE
        }
    }

    private fun setupAttendanceListener(sessionId: String) {
        attendanceListener = db.collection("attendance_sessions")
            .document(sessionId)
            .collection("records")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e)
                    return@addSnapshotListener
                }

                val records = StringBuilder("Attendance Records:\n")
                for (doc in snapshot?.documents.orEmpty()) {
                    val name = doc.getString("name") ?: "Unknown"
                    val tupid = doc.getString("tupid") ?: "Unknown"
                    val section = doc.getString("section") ?: "Unknown"
                    val timestamp = doc.getTimestamp("timestamp")?.toDate()?.toString() ?: "Unknown time"

                    records.append("$name ($tupid) - $section at $timestamp\n")
                }

                attendanceRecordsTextView.text = records.toString()
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("sessionId", currentSessionId)
        outState.putString("subjectCode", currentSubjectCode)
    }

    private fun loadSavedSubjectCode() {
        db.collection("users").document(currentUserID)
            .get()
            .addOnSuccessListener { doc ->
                currentSubjectCode = doc.getString("subjectCode")
                if (currentSubjectCode != null) {
                    Log.d(TAG, "Subject code: $currentSubjectCode")
                    subjectCodeEditText.setText(currentSubjectCode)
                    generateQrButton.isEnabled = true
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load Subject Code", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveSubjectCode(code: String) {
        currentSubjectCode = code
        db.collection("users").document(currentUserID)
            .update("subjectCode", code)
            .addOnSuccessListener {
                Log.d(TAG, "Subject code saved: $code")
                Toast.makeText(this, "Subject code saved", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to save subject code: ${it.message}")
                Toast.makeText(this, "Failed to save subject code", Toast.LENGTH_SHORT).show()
            }
    }

    private fun generateQrCode() {
        if (currentSubjectCode == null) {
            Toast.makeText(this, "Subject code not available", Toast.LENGTH_SHORT).show()
            return
        }

        currentSessionId = generateSessionId()
        val qrCodeContent = createQrCodeContent(currentSessionId, currentSubjectCode)

        if (qrCodeContent == null) {
            Toast.makeText(this, "Failed to create QR content", Toast.LENGTH_SHORT).show()
            return
        }

        val qrCodeBitmap = generateQrCodeImage(qrCodeContent)

        if (qrCodeBitmap != null) {
            qrCodeImageView.setImageBitmap(qrCodeBitmap)
            qrCodeImageView.visibility = View.VISIBLE
            endSessionButton.visibility = View.VISIBLE
            sessionInfoTextView.text = getString(R.string.session_info_format, currentSessionId, currentSubjectCode)
            storeSessionInfo(currentSessionId, currentSubjectCode)
            currentSessionId?.let { setupAttendanceListener(it) }
            Toast.makeText(this, "QR Code Generated!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "QR Code generation failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateSessionId(): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val uuid = UUID.randomUUID().toString()
        return "$timestamp-$uuid"
    }

    private fun createQrCodeContent(sessionId: String?, subjectCode: String?): String? {
        return try {
            val json = JSONObject()
            json.put("sessionId", sessionId)
            json.put("subjectCode", subjectCode)
            json.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating JSON: ${e.message}")
            null
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
            Log.e(TAG, "QR generation error: ${e.message}")
            null
        }
    }

    private fun storeSessionInfo(sessionId: String?, subjectCode: String?) {
        if (sessionId == null) return

        val sessionData = hashMapOf(
            "sessionId" to sessionId,
            "subjectCode" to subjectCode,
            "teacherId" to currentUserID,
            "startTime" to FieldValue.serverTimestamp()
        )

        db.collection("attendance_sessions").document(sessionId)
            .set(sessionData)
            .addOnSuccessListener {
                Log.d(TAG, "Session stored")
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to store session: ${it.message}")
            }
    }

    private fun endCurrentSession() {
        currentSessionId?.let { sessionId ->
            // First remove any active attendance listener
            attendanceListener?.remove()
            attendanceListener = null

            // Update session with end time
            db.collection("attendance_sessions").document(sessionId)
                .update("endTime", FieldValue.serverTimestamp())
                .addOnSuccessListener {
                    // UI cleanup
                    Toast.makeText(this, "Session ended", Toast.LENGTH_SHORT).show()
                    qrCodeImageView.setImageBitmap(null)
                    qrCodeImageView.visibility = View.GONE
                    endSessionButton.visibility = View.GONE
                    sessionInfoTextView.text = "No active session"

                    // Clear attendance records display
                    attendanceRecordsTextView.text = "No active session"

                    // Reset session tracking
                    currentSessionId = null
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to end session", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Failed to update session end time", e)
                }
        } ?: run {
            Toast.makeText(this, "No active session to end", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onDestroy() {
        attendanceListener?.remove()
        super.onDestroy()
    }
}