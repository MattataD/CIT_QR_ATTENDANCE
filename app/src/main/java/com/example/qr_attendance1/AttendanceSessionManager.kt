package com.example.qr_attendance1

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * Manages attendance sessions in Firestore
 */
class AttendanceSessionManager(private val firestore: FirebaseFirestore) {

    /**
     * Creates a new attendance session in Firestore
     *
     * @param sessionId Unique ID for the session
     * @param teacherId ID of the teacher creating the session
     * @param subjectCode Subject code for this session
     * @param onSuccess Callback with the session ID when creation is successful
     * @param onFailure Callback when creation fails
     */
    fun createSession(
        sessionId: String,
        teacherId: String,
        subjectCode: String,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val sessionData = hashMapOf(
            "teacherId" to teacherId,
            "subjectCode" to subjectCode,
            "startTime" to Timestamp.now(),
            "endTime" to null,
            "createdAt" to Timestamp.now()
        )

        firestore.collection("attendance_sessions")
            .document(sessionId)
            .set(sessionData)
            .addOnSuccessListener { onSuccess(sessionId) }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Ends an active attendance session
     *
     * @param sessionId ID of the session to end
     * @param onSuccess Callback when ending is successful
     * @param onFailure Callback when ending fails
     */
    fun endSession(
        sessionId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        firestore.collection("attendance_sessions")
            .document(sessionId)
            .update("endTime", Timestamp.now())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Finds an active session for a teacher
     *
     * @param teacherId ID of the teacher
     * @param onSessionFound Callback with session info when an active session is found
     * @param onNoSessionFound Callback when no active session is found
     * @param onFailure Callback when the query fails
     */
    fun findActiveSessionForTeacher(
        teacherId: String,
        onSessionFound: (String, String?) -> Unit,
        onNoSessionFound: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        firestore.collection("attendance_sessions")
            .whereEqualTo("teacherId", teacherId)
            .whereEqualTo("endTime", null)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (!querySnapshot.isEmpty) {
                    val sessionDoc = querySnapshot.documents[0]
                    val sessionId = sessionDoc.id
                    val subjectCode = sessionDoc.getString("subjectCode")
                    onSessionFound(sessionId, subjectCode)
                } else {
                    onNoSessionFound()
                }
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Sets up a listener for attendance records for a specific session
     *
     * @param sessionId ID of the session to listen to
     * @param onRecordsUpdated Callback with the updated list of records
     * @param onFailure Callback when the listener setup fails
     * @return The listener registration that can be used to remove the listener
     */
    fun listenForAttendanceRecords(
        sessionId: String,
        onRecordsUpdated: (List<AttendanceRecord>) -> Unit,
        onFailure: (Exception) -> Unit
    ): ListenerRegistration {
        return firestore.collection("attendance_sessions")
            .document(sessionId)
            .collection("records")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, exception ->
                if (exception != null) {
                    onFailure(exception)
                    return@addSnapshotListener
                }

                val records = mutableListOf<AttendanceRecord>()
                snapshot?.documents?.forEach { doc ->
                    val name = doc.getString("name") ?: "Unknown"
                    val tupid = doc.getString("tupid") ?: "Unknown"
                    val section = doc.getString("section") ?: "Unknown"
                    val timestamp = doc.getTimestamp("timestamp")

                    records.add(
                        AttendanceRecord(
                            name = name,
                            tupid = tupid,
                            section = section,
                            timestamp = timestamp
                        )
                    )
                }
                onRecordsUpdated(records)
            }
    }

    /**
     * Gets recent attendance sessions for a teacher
     *
     * @param teacherId ID of the teacher
     * @param limit Maximum number of sessions to return
     * @param onSuccess Callback with the list of sessions
     * @param onFailure Callback when the query fails
     */
    fun getRecentSessionsForTeacher(
        teacherId: String,
        limit: Long,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        firestore.collection("attendance_sessions")
            .whereEqualTo("teacherId", teacherId)
            .whereNotEqualTo("endTime", null)
            .orderBy("endTime", Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .addOnSuccessListener { querySnapshot ->
                val sessions = mutableListOf<Map<String, Any>>()
                for (doc in querySnapshot.documents) {
                    val sessionData = hashMapOf<String, Any>(
                        "sessionId" to doc.id,
                        "subjectCode" to (doc.getString("subjectCode") ?: "Unknown"),
                        "startTime" to (doc.getTimestamp("startTime") ?: Timestamp.now()),
                        "endTime" to (doc.getTimestamp("endTime") ?: Timestamp.now())
                    )
                    sessions.add(sessionData)
                }
                onSuccess(sessions)
            }
            .addOnFailureListener { onFailure(it) }
    }
}