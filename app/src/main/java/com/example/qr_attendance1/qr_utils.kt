package com.example.qr_attendance1

import org.json.JSONObject
import java.util.UUID

/**
 * Utility class for QR code related operations
 */
object QRCodeUtils {

    /**
     * Data class to hold parsed session information from QR code
     */
    data class SessionInfo(
        val sessionId: String,
        val subjectCode: String
    )

    /**
     * Validates if a QR code string is a valid attendance QR code
     * A valid QR code should be a JSON string containing sessionId and subjectCode
     */
    fun isValidAttendanceQR(qrData: String): Boolean {
        return try {
            val json = JSONObject(qrData)
            json.has("sessionId") && json.has("subjectCode")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parses attendance QR code data into a SessionInfo object
     * Returns null if the QR code is invalid
     */
    fun parseAttendanceQR(qrData: String): SessionInfo? {
        return try {
            val json = JSONObject(qrData)
            val sessionId = json.getString("sessionId")
            val subjectCode = json.getString("subjectCode")

            if (sessionId.isNotEmpty() && subjectCode.isNotEmpty()) {
                SessionInfo(sessionId, subjectCode)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generates a unique session ID for attendance tracking
     * Uses UUID to ensure uniqueness
     */
    fun generateSessionId(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * Creates JSON content for attendance QR code
     * @param sessionId The unique ID for this attendance session
     * @param subjectCode The subject code for this session
     * @return A JSON string containing session information
     */
    fun createAttendanceQRContent(sessionId: String, subjectCode: String): String {
        val json = JSONObject()
        json.put("sessionId", sessionId)
        json.put("subjectCode", subjectCode)
        return json.toString()
    }
}