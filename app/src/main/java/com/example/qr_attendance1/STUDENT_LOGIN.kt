package com.example.qr_attendance1

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.qr_attendance1.databinding.LoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class student_login : AppCompatActivity() {

    private lateinit var binding: LoginBinding
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    companion object {
        const val ROLE_STUDENT = "student"
        const val ROLE_TEACHER = "teacher"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firebaseAuth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        binding.signsupRedirectText.setOnClickListener {
            val intent = Intent(this, signup::class.java)
            startActivity(intent)
        }

        binding.loginButton.setOnClickListener {
            val email = binding.LoginEmail.text.toString().trim()
            val password = binding.loginPassword.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                if (Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    firebaseAuth.signInWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                val userId = firebaseAuth.currentUser?.uid
                                if (userId != null) {
                                    firestore.collection("users").document(userId)
                                        .get()
                                        .addOnSuccessListener { document ->
                                            if (document != null && document.exists()) {
                                                val role = document.getString("role")
                                                if (role == ROLE_STUDENT) {
                                                    Toast.makeText(
                                                        this,
                                                        "Student Login Successful!",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    val intent = Intent(this, student_qr::class.java)
                                                    startActivity(intent)
                                                    finish()
                                                } else if (role == ROLE_TEACHER) {
                                                    Toast.makeText(
                                                        this,
                                                        "Teacher Login Successful!",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    val intent = Intent(this, TEACHER_QR::class.java)
                                                    startActivity(intent)
                                                    finish()
                                                } else {
                                                    Toast.makeText(
                                                        this,
                                                        "Error: Unrecognized user role.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    firebaseAuth.signOut()
                                                    finish()
                                                }
                                            } else {
                                                Toast.makeText(
                                                    this,
                                                    "Error: Could not retrieve user data.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                firebaseAuth.signOut()
                                                finish()
                                            }
                                        }
                                        .addOnFailureListener { exception ->
                                            Toast.makeText(
                                                this,
                                                "Error retrieving user data: ${exception.message}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            firebaseAuth.signOut()
                                            finish()
                                        }
                                } else {
                                    Toast.makeText(this, "Error: Could not get current user ID.", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            } else {
                                Toast.makeText(
                                    this,
                                    "Login Failed: ${task.exception?.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            }
                        }
                } else {
                    Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Please enter both email and password", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
