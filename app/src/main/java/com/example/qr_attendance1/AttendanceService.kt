package com.example.qratendance1

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

/**
 * Service class for handling attendance-related operations
 * Separated from the UI activity for better code organization
 */
class AttendanceService(private val db: FirebaseFirestore) {

    companion object {
        private const val TAG = "AttendanceService"
    }

    /**
     * Records a student's attendance for a specific session
     */
    fun recordStudentAttendance(
        sessionId: String,
        studentId: String,
        studentName: String,
        studentTupId: String,
        studentSection: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (sessionId.isBlank()) {
            Log.e(TAG, "Session ID is invalid. Cannot record attendance.")
            onFailure(IllegalArgumentException("Invalid session ID"))
            return
        }

        // Check if the session exists
        db.collection("attendance_sessions").document(sessionId)
            .get()
            .addOnSuccessListener { documentSnapshot ->
                if (documentSnapshot.exists()) {
                    val endTime = documentSnapshot.getTimestamp("endTime")
                    if (endTime == null) {
                        // Session is active, proceed
                        checkIfAlreadyRecorded(
                            sessionId, studentId,
                            onAlreadyRecorded = {
                                onFailure(IllegalStateException("Attendance already recorded"))
                            },
                            onNotRecorded = {
                                saveAttendanceRecord(
                                    sessionId,
                                    studentId,
                                    studentName,
                                    studentTupId,
                                    studentSection,
                                    onSuccess,
                                    onFailure
                                )
                            },
                            onError = onFailure
                        )
                    } else {
                        onFailure(IllegalStateException("Session has already ended"))
                    }
                } else {
                    onFailure(IllegalArgumentException("Session not found"))
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error verifying session: ${e.message}")
                onFailure(e)
            }
    }

    private fun checkIfAlreadyRecorded(
        sessionId: String,
        studentId: String,
        onAlreadyRecorded: () -> Unit,
        onNotRecorded: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        db.collection("attendance_sessions").document(sessionId)
            .collection("records").document(studentId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    onAlreadyRecorded()
                } else {
                    onNotRecorded()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error checking existing attendance: ${e.message}")
                onError(e)
            }
    }

    private fun saveAttendanceRecord(
        sessionId: String,
        studentId: String,
        studentName: String,
        studentTupId: String,
        studentSection: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val attendanceData = hashMapOf(
            "studentId" to studentId,
            "name" to studentName,
            "tupId" to studentTupId,
            "section" to studentSection,
            "timestamp" to FieldValue.serverTimestamp()
        )

        db.collection("attendance_sessions").document(sessionId)
            .collection("records").document(studentId)
            .set(attendanceData)
            .addOnSuccessListener {
                Log.d(TAG, "Student attendance recorded for: $studentId in session $sessionId")
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error recording attendance: ${e.message}")
                onFailure(e)
            }
    }
}
