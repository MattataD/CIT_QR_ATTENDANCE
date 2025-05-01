package com.example.qr_attendance1

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.example.qr_attendance1.databinding.ActivityStudentprofileBinding

class studentprofile : AppCompatActivity() {

    private lateinit var binding: ActivityStudentprofileBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var userId: String
    private lateinit var db: FirebaseFirestore
    private lateinit var userEmail: String
    private var userDocumentId: String? = null // Store the user's document ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentprofileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase components
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        userId = auth.currentUser?.uid ?: ""
        userEmail = auth.currentUser?.email ?: ""

        // Initialize bottom navigation
        bottomNavigationView = binding.bottomNavigation
        setupBottomNavigation()
        bottomNavigationView.selectedItemId = R.id.student

        // Setup copy functionality for all profile fields
        setupCopyOnLongClick(binding.textView3)  // TUP ID
        setupCopyOnLongClick(binding.textView4)  // Email
        setupCopyOnLongClick(binding.textView2) // Name
        setupCopyOnLongClick(binding.editText1)  // Section

        // Set up section saving
        binding.saveSectionButton.setOnClickListener {
            saveSectionToFirestore()
        }

        // Load user data from Firebase
        if (userId.isNotEmpty()) {
            loadStudentData()
        } else {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadStudentData() {
        binding.textView4.text = userEmail

        db.collection("users")
            .document(userId)  // Direct access using UID
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    userDocumentId = document.id
                    val tupid = document.getString("tupid")
                    val name = document.getString("name")
                    val section = document.getString("section")

                    binding.textView3.text = tupid ?: "N/A"
                    binding.textView2.text = name ?: "N/A"
                    binding.editText1.setText(section ?: "")
                } else {
                    Toast.makeText(this, "Student data not found", Toast.LENGTH_SHORT).show()
                    binding.textView3.text = "N/A"
                    binding.textView2.text = "N/A"
                    binding.editText1.setText("")
                }
            }
            .addOnFailureListener { exception ->
                Toast.makeText(this, "Failed to load data: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveSectionToFirestore() {
        val section = binding.editText1.text.toString().trim()

        if (section.isEmpty()) {
            Toast.makeText(this, "Please enter a section", Toast.LENGTH_SHORT).show()
            return
        }

        userDocumentId?.let { documentId ->  // Use the stored document ID
            val updates = hashMapOf<String, Any>(
                "section" to section
            )

            db.collection("users")
                .document(documentId)
                .set(updates, SetOptions.merge())
                .addOnSuccessListener {
                    Toast.makeText(this, "Section saved successfully", Toast.LENGTH_SHORT).show()
                    // Optionally update the UI immediately
                    // binding.editText1.setText(section)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to save section: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } ?: run {
            Toast.makeText(this, "User document not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.qr -> {
                    startActivity(Intent(this, student_qr::class.java))
                    true
                }
                R.id.student -> true  // Already on profile page
                R.id.logout -> {
                    auth.signOut()
                    startActivity(Intent(this, student_login::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupCopyOnLongClick(view: View) {
        view.setOnLongClickListener {
            val text = when (view) {
                is TextView -> view.text.toString()
                else -> ""
            }

            if (text.isNotBlank() && text != "N/A") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Text", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Text copied to clipboard!", Toast.LENGTH_SHORT).show()

                // Visual feedback
                view.alpha = 0.5f
                view.postDelayed({ view.alpha = 1f }, 150)
                true
            } else {
                false
            }
        }
    }
}