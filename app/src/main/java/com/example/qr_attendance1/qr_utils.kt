package com.example.qr_attendance1

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * A utility class for handling QR code operations in the attendance system.
 * This class centralizes QR code parsing logic for improved maintainability.
 */
class QRCodeUtils {
    companion object {
        private const val TAG = "QRCodeUtils"
        
        /**
         * Data class to hold attendance session information from QR code
         */
        data class AttendanceSessionInfo(
            val sessionId: String,
            val subjectCode: String
        )
        
        /**
         * Parses QR code content into attendance session information
         * 
         * @param qrContent The raw string content from the QR code scan
         * @return AttendanceSessionInfo if parsing succeeds, null otherwise
         */
        fun parseAttendanceQR(qrContent: String): AttendanceSessionInfo? {
            return try {
                // First try to parse as JSON
                val json = JSONObject(qrContent)
                
                // Check if required fields exist
                val sessionId = json.optString("sessionId")
                val subjectCode = json.optString("subjectCode")
                
                if (sessionId.isBlank() || subjectCode.isBlank()) {
                    Log.e(TAG, "QR code missing required fields: $qrContent")
                    return null
                }
                
                // Return parsed data
                AttendanceSessionInfo(sessionId, subjectCode)
            } catch (e: JSONException) {
                // If not valid JSON, try alternative format parsing
                tryAlternativeFormatParsing(qrContent)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing QR code content", e)
                null
            }
        }
        
        /**
         * Attempts to parse QR content that might be in non-JSON format
         * For example: "sessionId:123456,subjectCode:CS101"
         */
        private fun tryAlternativeFormatParsing(qrContent: String): AttendanceSessionInfo? {
            try {
                // Try comma-separated key-value pairs
                val pairs = qrContent.split(",")
                var sessionId: String? = null
                var subjectCode: String? = null
                
                for (pair in pairs) {
                    val keyValue = pair.split(":", "=")
                    if (keyValue.size == 2) {
                        val key = keyValue[0].trim().lowercase()
                        val value = keyValue[1].trim()
                        
                        when (key) {
                            "sessionid" -> sessionId = value
                            "subjectcode" -> subjectCode = value
                        }
                    }
                }
                
                if (!sessionId.isNullOrBlank() && !subjectCode.isNullOrBlank()) {
                    return AttendanceSessionInfo(sessionId, subjectCode)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Alternative format parsing failed", e)
            }
            
            return null
        }
        
        /**
         * Creates QR code content for an attendance session
         * 
         * @param sessionId The unique session identifier
         * @param subjectCode The subject code for the session
         * @return JSON string ready for QR code generation
         */
        fun createAttendanceQRContent(sessionId: String, subjectCode: String): String {
            return try {
                val json = JSONObject()
                json.put("sessionId", sessionId)
                json.put("subjectCode", subjectCode)
                json.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error creating QR content", e)
                // Fallback to simple format if JSON fails
                "sessionId:$sessionId,subjectCode:$subjectCode"
            }
        }
        
        /**
         * Validates that QR content contains required attendance information
         * 
         * @param qrContent The raw string content from the QR code scan
         * @return true if content is valid for attendance, false otherwise
         */
        fun isValidAttendanceQR(qrContent: String): Boolean {
            return parseAttendanceQR(qrContent) != null
        }
    }
}