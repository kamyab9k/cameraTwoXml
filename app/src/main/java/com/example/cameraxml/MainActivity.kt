package com.example.cameraxml

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.graphics.Point
import android.hardware.Camera
import androidx.camera.core.Preview
import com.example.cameraxml.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private val CAMERA_PERMISSION_REQUEST_CODE = 100
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraPreview: CameraPreview


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)        // Check camera permissions
        if (!checkCameraPermissions()) {
            // Request camera permissions if not granted
            requestCameraPermissions()
        } else {
            // Camera permissions already granted, proceed with camera setup
            setupCamera()
        }
    }

    private fun checkCameraPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    private fun setupCamera() {
        cameraPreview = CameraPreview(this, binding.textureView)

        // Start camera preview
        cameraPreview.startCameraPreview()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Camera permissions granted, proceed with camera setup
                setupCamera()
            } else {
                // Camera permissions denied, handle accordingly (e.g., show a message, disable camera functionality)
                // You may want to inform the user about the necessity of camera permissions
            }
        }
    }

}