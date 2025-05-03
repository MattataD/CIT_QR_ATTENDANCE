package com.example.qr_attendance1

import android.Manifest
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.qr_attendance1.databinding.SingupBinding
import com.example.qr_attendance1.RegistrationConstants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class FaceRegData(
    val userId: String,
    val email: String,
    val tupid: String,
    val name: String,
    val role: String
)

class signup: AppCompatActivity() {

    private lateinit var binding: SingupBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var progressDialog: ProgressDialog
    private val TAG = "SIGNUP_ACTIVITY"
    private val CAMERA_PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SingupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = Firebase.auth
        firestore = Firebase.firestore

        progressDialog = ProgressDialog(this).apply {
            setMessage("Creating your account...")
            setCancelable(false)
        }

        binding.FaceReg.visibility = View.GONE
        setupClickListeners()

        // Check for camera permission at startup
        checkCameraPermissions()
    }

    // Add a method to check camera permissions at startup
    private fun checkCameraPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission not granted yet")
            // We don't request immediately on startup, we'll request when needed
        } else {
            Log.d(TAG, "Camera permission already granted")
        }
    }

    private fun setupClickListeners() {
        binding.loginRedirectText.setOnClickListener { navigateToLogin() }
        binding.signbutton.setOnClickListener { handleSignUp() }
    }

    private fun handleSignUp() {
        val email = binding.signupEmail.text.toString().trim()
        val name = binding.signupName.text.toString().trim()
        val tupid = binding.signupTupid.text.toString().trim()
        val password = binding.signupPassword.text.toString().trim()
        val selectedRole = getSelectedRole()

        if (validateInput(email, password, selectedRole)) {
            proceedWithSignUp(email, password, name, tupid, selectedRole)
        }
    }

    private fun getSelectedRole(): String {
        return when {
            binding.teacherRadioButton.isChecked -> "teacher"
            binding.studentRadioButton.isChecked -> "student"
            else -> ""
        }
    }

    private fun validateInput(email: String, password: String, role: String): Boolean {
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showToast("Invalid email format")
            return false
        }

        if (password.length < 6) {
            showToast("Password must be ≥6 characters")
            return false
        }

        if (role.isEmpty()) {
            showToast("Please select a role")
            return false
        }

        return true
    }

    private fun proceedWithSignUp(
        email: String,
        password: String,
        name: String,
        tupid: String,
        role: String
    ) {
        lifecycleScope.launch {
            showProgress("Creating account...")
            disableSignupButton()

            try {
                val authResult = withContext(Dispatchers.IO) {
                    auth.createUserWithEmailAndPassword(email, password).await()
                }

                val uid = authResult.user?.uid
                if (uid != null) {
                    saveUserToFirestore(name, tupid, email, role, uid)
                } else {
                    showToast("User creation failed")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Signup failed: ${e.message}", e)
                handleSignUpError(e)
            } finally {
                hideProgress()
                enableSignupButton()
            }
        }
    }

    private fun saveUserToFirestore(
        name: String,
        tupid: String,
        email: String,
        role: String,
        userId: String
    ) {
        lifecycleScope.launch {
            showProgress("Saving user data...")

            val userData = hashMapOf(
                "name" to name,
                "tupid" to tupid,
                "email" to email,
                "role" to role
            )

            try {
                withContext(Dispatchers.IO) {
                    firestore.collection("users").document(userId).set(userData).await()
                }

                if (role == "student") {
                    showFaceRegistrationButton(userId, email, tupid, name, role)
                    showToast("Account created! Proceed to face registration.")
                } else if (role == "teacher") {
                    // For teachers, navigate to the teacher dashboard after successful account creation
                    showToast("Teacher account created successfully")
                    navigateToTeacherDashboard(userId)
                } else {
                    showToast("Account created successfully")
                    navigateToLogin()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to save user data: ${e.message}", e)
                showToast("Failed to save user data")
                deleteFailedUser(userId)
            } finally {
                hideProgress()
            }
        }
    }

    private fun navigateToTeacherDashboard(userId: String) {
        try {
            Log.d(TAG, "Navigating to teacher dashboard with userId: $userId")
            val intent = Intent(this, TEACHER_QR::class.java).apply {
                putExtra("USER_ID", userId)
            }
            startActivity(intent)
            finishAffinity()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to teacher dashboard: ${e.message}", e)
            showToast("Error launching teacher dashboard: ${e.message}")
            // Fallback to login if there's an error
            navigateToLogin()
        }
    }

    private fun showFaceRegistrationButton(userId: String, email: String, tupid: String, name: String, role: String) {
        binding.FaceReg.visibility = View.VISIBLE
        binding.FaceReg.isEnabled = true
        binding.FaceReg.tag = FaceRegData(userId, email, tupid, name, role)

        binding.FaceReg.setOnClickListener {
            val userData = binding.FaceReg.tag as? FaceRegData
            if (userData == null) {
                showToast("Invalid user data")
                return@setOnClickListener
            }
            startFaceRegistration(userData)
        }
    }

    private fun startFaceRegistration(userData: FaceRegData) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                Log.d(TAG, "Starting face registration activity with data: $userData")
                val intent = Intent(this, FaceRegistrationActivity::class.java).apply {
                    putExtra(RegistrationConstants.EXTRA_USER_ID, userData.userId)
                    putExtra(RegistrationConstants.EXTRA_EMAIL, userData.email)
                    putExtra(RegistrationConstants.EXTRA_TUPID, userData.tupid)
                    putExtra(RegistrationConstants.EXTRA_NAME, userData.name)
                    putExtra(RegistrationConstants.EXTRA_USER_ROLE, userData.role)
                }
                startActivityForResult(intent, RegistrationConstants.FACE_REG_REQUEST_CODE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FaceRegistrationActivity: ${e.message}", e)
                showToast("Error launching face registration: ${e.message}")
            }
        } else {
            Log.d(TAG, "Requesting camera permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        if (requestCode == RegistrationConstants.FACE_REG_REQUEST_CODE) {
            when (resultCode) {
                RESULT_OK -> {
                    showToast("Face registration completed successfully")
                    navigateToLogin()
                }
                RESULT_CANCELED -> {
                    showToast("Face registration was canceled")
                }
                else -> {
                    Log.d(TAG, "Unknown result code: $resultCode")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Log.d(TAG, "Camera permission granted")
                val userData = binding.FaceReg.tag as? FaceRegData
                if (userData != null) {
                    startFaceRegistration(userData)
                } else {
                    showToast("User data lost. Please try again.")
                }
            } else {
                Log.d(TAG, "Camera permission denied")
                showToast("Camera permission is required for face registration")
            }
        }
    }

    private suspend fun deleteFailedUser(userId: String) {
        try {
            withContext(Dispatchers.IO) {
                auth.currentUser?.delete()?.await()
                firestore.collection("users").document(userId).delete().await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean up failed user: ${e.message}", e)
        }
    }

    private fun handleSignUpError(exception: Exception) {
        val errorMessage = when (exception) {
            is FirebaseAuthUserCollisionException -> "Email already in use"
            is FirebaseAuthWeakPasswordException -> "Password too weak"
            else -> "Signup failed: ${exception.message ?: "Unknown error"}"
        }
        showToast(errorMessage)
    }

    private fun showProgress(message: String) {
        progressDialog.setMessage(message)
        progressDialog.show()
    }

    private fun hideProgress() {
        if (progressDialog.isShowing) {
            progressDialog.dismiss()
        }
    }

    private fun disableSignupButton() {
        binding.signbutton.isEnabled = false
        binding.signbutton.text = "Please wait..."
    }

    private fun enableSignupButton() {
        binding.signbutton.isEnabled = true
        binding.signbutton.text = "Sign Up"
    }

    private fun navigateToLogin() {
        startActivity(Intent(this, student_login::class.java))
        finishAffinity()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideProgress()
    }
}