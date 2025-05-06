package com.example.qr_attendance1

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale

class AttendanceAdapter(private var attendanceRecords: List<AttendanceRecord>) :
    RecyclerView.Adapter<AttendanceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameTextView: TextView = view.findViewById(R.id.nameTextView)
        val tupidTextView: TextView = view.findViewById(R.id.tupidTextView)
        val sectionTextView: TextView = view.findViewById(R.id.sectionTextView)
        val timeTextView: TextView = view.findViewById(R.id.timeTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.attendance_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = attendanceRecords[position]

        holder.nameTextView.text = record.name
        holder.tupidTextView.text = record.tupid

        // Get just the section part before any spaces
        val section = record.section.split(" ").firstOrNull() ?: "Unknown"
        holder.sectionTextView.text = section

        // Format time
        val timeText = if (record.timestamp != null) {
            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            dateFormat.format(record.timestamp.toDate())
        } else {
            "Unknown"
        }
        holder.timeTextView.text = timeText
    }

    override fun getItemCount() = attendanceRecords.size

    fun updateData(newRecords: List<AttendanceRecord>) {
        this.attendanceRecords = newRecords
        notifyDataSetChanged()
    }
    }