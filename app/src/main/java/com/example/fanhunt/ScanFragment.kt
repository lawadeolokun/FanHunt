package com.example.fanhunt

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.fanhunt.databinding.FragmentScanBinding
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentReference
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
class ScanFragment : Fragment(R.layout.fragment_scan) {

    private lateinit var binding: FragmentScanBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val fs: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // Prevents scanning multiple times per second
    private var canScan = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = FragmentScanBinding.bind(view)
        cameraExecutor = Executors.newSingleThreadExecutor()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        // Camera permission
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.CAMERA),
                101
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.previewView.surfaceProvider)

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxy(imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!canScan) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        val scanner = BarcodeScanning.getClient()

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val rawValue = barcodes.first().rawValue
                    if (!rawValue.isNullOrBlank()) {
                        handleQrScanned(rawValue)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("SCAN_DEBUG", "Scan error: ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleQrScanned(rawValue: String) {
        if (!canScan) return
        canScan = false

        // Location permission
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                201
            )
            canScan = true
            return
        }

        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(requireContext(), "Not signed in", Toast.LENGTH_SHORT).show()
            resetScan()
            return
        }

        //Error message when user scans different/invalid QR Code
        val codeId = rawValue.trim()
        if (codeId.isBlank()) {
            Toast.makeText(requireContext(), "Invalid QR code", Toast.LENGTH_SHORT).show()
            resetScan()
            return
        }

        val userRef = fs.collection("users").document(uid)
        val codeRef = fs.collection("qrCodes").document(codeId)
        val redemptionRef = userRef.collection("redemptions").document(codeId)

        // Try cached location first
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLocation ->
                if (lastLocation != null) {
                    processScanWithLocation(codeId, lastLocation, userRef, codeRef, redemptionRef)
                } else {
                    // Request fresh GPS fix
                    requestSingleLocationUpdate {
                        processScanWithLocation(codeId, it, userRef, codeRef, redemptionRef)
                    }
                }
            }
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT).show()
            resetScan()
        }
    }

    private fun processScanWithLocation(
        codeId: String,
        userLocation: Location,
        userRef: DocumentReference,
        codeRef: DocumentReference,
        redemptionRef: DocumentReference
    ) {
        fs.runTransaction { tx ->

            val qrSnap = tx.get(codeRef)
            if (!qrSnap.exists()) throw Exception("Invalid QR code")
            if (qrSnap.getBoolean("active") != true) throw Exception("Code inactive")

            val qrLat = qrSnap.getDouble("latitude") ?: throw Exception("QR missing latitude")
            val qrLng = qrSnap.getDouble("longitude") ?: throw Exception("QR missing longitude")
            val radius = qrSnap.getLong("radius") ?: 50L

            val qrLocation = Location("").apply {
                latitude = qrLat
                longitude = qrLng
            }

            val distance = userLocation.distanceTo(qrLocation)
            if (distance > radius) {
                throw Exception("You must be at the checkpoint location")
            }

            if (tx.get(redemptionRef).exists()) {
                throw Exception("Already redeemed")
            }

            val points = qrSnap.getLong("points") ?: 0L
            val userSnap = tx.get(userRef)
            val currentPoints = userSnap.getLong("totalPoints") ?: 0L

            tx.update(userRef, "totalPoints", currentPoints + points)

            tx.set(
                redemptionRef,
                mapOf(
                    "codeId" to codeId,
                    "scannedAt" to FieldValue.serverTimestamp(),
                    "pointsAwarded" to points
                )
            )

            points
        }
            .addOnSuccessListener { points ->
                Toast.makeText(
                    requireContext(),
                    "You earned $points points!",
                    Toast.LENGTH_LONG
                ).show()
                resetScan()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    requireContext(),
                    e.message ?: "Scan failed",
                    Toast.LENGTH_LONG
                ).show()
                resetScan()
            }
    }

    private fun requestSingleLocationUpdate(onLocationReady: (Location) -> Unit) {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 1000
            fastestInterval = 500
            numUpdates = 1
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation
                if (location != null) {
                    onLocationReady(location)
                }
                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            null
        )
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resetScan() {
        binding.previewView.postDelayed({ canScan = true }, 2000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }
}
