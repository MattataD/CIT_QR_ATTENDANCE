package com.example.qr_attendance1


import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ServerTimestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AttendanceRecord(
    val name: String = "",
    val tupid: String = "",
    val section: String = "",
    @ServerTimestamp val timestamp: com.google.firebase.Timestamp? = null
) {
    override fun toString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateTime = timestamp?.toDate()?.let { sdf.format(it) } ?: "N/A"
        return "$name\t\t$tupid\t\t$section\t\t$dateTime"
    }
}

class TeacherHistoryActivity : AppCompatActivity() {

    private lateinit var historyListView: ListView
    private lateinit var firestore: FirebaseFirestore
    private lateinit var historyAdapter: ArrayAdapter<AttendanceRecord>
    private val historyList = mutableListOf<AttendanceRecord>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_history)

        historyListView = findViewById(R.id.historyListView)
        firestore = FirebaseFirestore.getInstance()
        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, historyList)
        historyListView.adapter = historyAdapter

        fetchAttendanceHistory()
    }

    private fun fetchAttendanceHistory() {
        firestore.collection("attendance_history")
            .get()
            .addOnSuccessListener { subjectQuerySnapshot ->
                historyList.clear()
                for (subjectDocument in subjectQuerySnapshot) {
                    val subjectCode = subjectDocument.id
                    subjectDocument.reference.collection(subjectCode)
                        .get()
                        .addOnSuccessListener { dateQuerySnapshot ->
                            for (dateDocument in dateQuerySnapshot) {
                                val date = dateDocument.id
                                dateDocument.reference.collection(date)
                                    .get()
                                    .addOnSuccessListener { studentQuerySnapshot ->
                                        for (studentDocument in studentQuerySnapshot) {
                                            val record = studentDocument.toObject(AttendanceRecord::class.java)
                                            record?.let { historyList.add(it) }
                                        }
                                        historyAdapter.notifyDataSetChanged()
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e("TeacherHistory", "Error fetching students for $subjectCode on $date: ${e.message}")
                                        // Handle error (e.g., display a message to the user)
                                    }
                            }
                            // Optionally handle after each date fetch if needed
                        }
                        .addOnFailureListener { e ->
                            Log.e("TeacherHistory", "Error fetching dates for $subjectCode: ${e.message}")
                            // Handle error (e.g., display a message to the user)
                        }
                }
                // Optionally handle after all data is fetched if needed
            }
            .addOnFailureListener { e ->
                Log.e("TeacherHistory", "Error fetching attendance history: ${e.message}")
                // Handle error (e.g., display a message to the user)
            }
    }
}