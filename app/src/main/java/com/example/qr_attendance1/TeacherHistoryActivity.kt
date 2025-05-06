package com.example.qr_attendance1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TeacherHistoryActivity : AppCompatActivity() {

    private lateinit var subjectCodeEditText: EditText
    private lateinit var attendanceRecyclerView: RecyclerView
    private lateinit var downloadButton: ImageButton
    private lateinit var firestore: FirebaseFirestore
    private lateinit var mAuth: FirebaseAuth
    private lateinit var sessionManager: AttendanceSessionManager
    private lateinit var currentUserID: String
    private lateinit var attendanceAdapter: AttendanceAdapter
    private lateinit var bottomNavigationView: BottomNavigationView

    private var attendanceListener: ListenerRegistration? = null
    private val attendanceRecords = ArrayList<AttendanceRecord>()
    private var currentSessionId: String? = null

    // Permission request code
    private val STORAGE_PERMISSION_CODE = 101

    companion object {
        private const val TAG = "TeacherHistory"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_history)

        // Initialize Firebase
        firestore = FirebaseFirestore.getInstance()
        mAuth = FirebaseAuth.getInstance()
        sessionManager = AttendanceSessionManager(firestore)

        // Get userId from intent if provided
        if (intent.hasExtra("USER_ID")) {
            currentUserID = intent.getStringExtra("USER_ID") ?: ""
            Log.d(TAG, "Received userId from intent: $currentUserID")
        } else {
            // Fall back to current user if not provided in intent
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

        // Check if session ID was passed
        if (intent.hasExtra("SESSION_ID")) {
            currentSessionId = intent.getStringExtra("SESSION_ID")
            Log.d(TAG, "Received sessionId: $currentSessionId")
        }

        // Initialize UI components
        subjectCodeEditText = findViewById(R.id.subjectCodeEditText)
        attendanceRecyclerView = findViewById(R.id.attendanceRecyclerView)
        downloadButton = findViewById(R.id.downloadButton)
        bottomNavigationView = findViewById(R.id.bottom_navigation1)

        // Set up RecyclerView
        attendanceRecyclerView.layoutManager = LinearLayoutManager(this)
        attendanceAdapter = AttendanceAdapter(attendanceRecords)
        attendanceRecyclerView.adapter = attendanceAdapter

        // Set click listener for download button
        downloadButton.setOnClickListener { checkPermissionAndDownload() }

        // Set up bottom navigation
        setupBottomNavigation()

        // Check for active session and set up listener
        if (currentSessionId != null) {
            setupAttendanceListener(currentSessionId!!)
        } else {
            checkForActiveSession()
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.record -> true
                R.id.teacherqr -> {
                    startActivity(Intent(this, TEACHER_QR::class.java))
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
        // Query for active sessions by this teacher
        sessionManager.findActiveSessionForTeacher(
            currentUserID,
            onSessionFound = { sessionId, subjectCode ->
                currentSessionId = sessionId

                // Update UI to show active session
                updateActiveSessionUI(subjectCode)

                // Setup listener for this session
                setupAttendanceListener(sessionId)
            },
            onNoSessionFound = {
                // No active session, show recent history instead
                showAttendanceHistory()
            },
            onFailure = { e ->
                Log.e(TAG, "Error checking for active sessions", e)
                Toast.makeText(this, "Failed to check for active sessions: ${e.message}", Toast.LENGTH_SHORT).show()
                showAttendanceHistory()
            }
        )
    }

    private fun updateActiveSessionUI(subjectCode: String?) {
        subjectCodeEditText.setText(subjectCode ?: "")
        subjectCodeEditText.isEnabled = false
    }

    private fun setupAttendanceListener(sessionId: String) {
        // Clear previous records
        attendanceRecords.clear()

        attendanceListener = sessionManager.listenForAttendanceRecords(
            sessionId,
            onRecordsUpdated = { records ->
                // Update the records list
                attendanceRecords.clear()
                attendanceRecords.addAll(records)

                // Update the adapter
                runOnUiThread {
                    attendanceAdapter.updateData(attendanceRecords)
                }
            },
            onFailure = { e ->
                Log.e(TAG, "Failed to listen for attendance records", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Failed to load attendance records: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun showAttendanceHistory() {
        subjectCodeEditText.isEnabled = true
        subjectCodeEditText.setText("")
        subjectCodeEditText.hint = "SUBJECT CODE:"

        // Query the most recent completed sessions
        sessionManager.getRecentSessionsForTeacher(
            teacherId = currentUserID,
            limit = 10,
            onSuccess = { sessions ->
                if (sessions.isEmpty()) {
                    Toast.makeText(this, "No attendance history found.", Toast.LENGTH_SHORT).show()
                    return@getRecentSessionsForTeacher
                }

                attendanceRecords.clear()

                // Use the first session's details
                val firstSession = sessions.firstOrNull()
                if (firstSession != null) {
                    val sessionId = firstSession["sessionId"] as String
                    val subjectCode = firstSession["subjectCode"] as String

                    // Display subject code
                    subjectCodeEditText.setText(subjectCode)

                    // Fetch records for this session
                    fetchRecordsForSession(sessionId)
                }
            },
            onFailure = { e ->
                Log.e(TAG, "Error fetching session history", e)
                Toast.makeText(this, "Failed to load attendance history: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun fetchRecordsForSession(sessionId: String) {
        firestore.collection("attendance_sessions")
            .document(sessionId)
            .collection("records")
            .get()
            .addOnSuccessListener { recordsSnapshot ->
                attendanceRecords.clear()

                for (record in recordsSnapshot) {
                    val name = record.getString("name") ?: "Unknown"
                    val tupid = record.getString("tupid") ?: "Unknown"
                    val section = record.getString("section") ?: "Unknown"
                    val timestamp = record.getTimestamp("timestamp")

                    // Add to list
                    attendanceRecords.add(
                        AttendanceRecord(
                            name = name,
                            tupid = tupid,
                            section = section,
                            timestamp = timestamp
                        )
                    )
                }

                // Update the adapter with all fetched records
                attendanceAdapter.updateData(attendanceRecords)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error fetching records for session $sessionId", e)
                Toast.makeText(this, "Failed to load records: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // Request storage permission
    private fun checkPermissionAndDownload() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_CODE
            )
        } else {
            // Permission already granted
            exportAttendanceToExcel()
        }
    }

    // Handle permission request result
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                exportAttendanceToExcel()
            } else {
                Toast.makeText(this, "Storage permission is required to download records", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Export attendance records to Excel file
    private fun exportAttendanceToExcel() {
        if (attendanceRecords.isEmpty()) {
            Toast.makeText(this, "No attendance records to export", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Create a new workbook
            val workbook: Workbook = XSSFWorkbook()

            // Create a sheet in the workbook
            val sheet: Sheet = workbook.createSheet("Attendance Records")

            // Create header row
            val headerRow: Row = sheet.createRow(0)
            val headers = arrayOf("Name", "TUP ID", "Section", "Time")

            for (i in headers.indices) {
                val cell: Cell = headerRow.createCell(i)
                cell.setCellValue(headers[i])
            }

            // Create data rows
            var rowIdx = 1
            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            for (record in attendanceRecords) {
                val row: Row = sheet.createRow(rowIdx++)

                row.createCell(0).setCellValue(record.name)
                row.createCell(1).setCellValue(record.tupid)

                // Split section to get the class section
                val sectionParts = record.section.split(" ")
                row.createCell(2).setCellValue(sectionParts.firstOrNull() ?: "Unknown")

                // Format time
                row.createCell(3).setCellValue(record.timestamp?.toDate()?.let { dateFormat.format(it) } ?: "Unknown")
            }

            // Set static column widths instead of autoSizeColumn (which uses AWT)
            sheet.setColumnWidth(0, 20 * 256) // Name
            sheet.setColumnWidth(1, 15 * 256) // TUP ID
            sheet.setColumnWidth(2, 10 * 256) // Section
            sheet.setColumnWidth(3, 15 * 256) // Time

            // Create filename with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Attendance_History_$timestamp.xlsx"

            // Get the Downloads directory
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)

            // Write to file
            val fileOutputStream = FileOutputStream(file)
            workbook.write(fileOutputStream)
            fileOutputStream.close()

            // Close workbook
            workbook.close()

            Toast.makeText(this, "Excel file saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
            Log.d(TAG, "Excel file saved to ${file.absolutePath}")

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to export: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Export error: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        attendanceListener?.remove()
        super.onDestroy()
    }
}