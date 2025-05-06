package com.example.qr_attendance1

import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Data class representing an attendance record
 */
data class AttendanceRecord(
    val name: String,
    val tupid: String,
    val section: String,
    val timestamp: Timestamp? = null
) {
    /**
     * Returns the formatted time of the attendance
     */
    fun getFormattedTime(): String {
        if (timestamp == null) return "Unknown time"

        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return dateFormat.format(timestamp.toDate())
    }

    /**
     * Returns the formatted date of the attendance
     */
    fun getFormattedDate(): String {
        if (timestamp == null) return "Unknown date"

        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        return dateFormat.format(timestamp.toDate())
    }
}