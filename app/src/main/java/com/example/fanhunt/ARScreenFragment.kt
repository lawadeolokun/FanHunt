package com.example.fanhunt

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import kotlin.math.*

data class ARCheckpoint(
    val id: String,
    val lat: Double,
    val lng: Double,
    val points: Int,
    val label: String
)

data class SpawnedObject(
    val anchorNode: AnchorNode,
    val modelNode: ModelNode,
    val checkpoint: ARCheckpoint
)

class ARScreenFragment : Fragment(R.layout.fragment_ar) {

    // --- Views ---
    private lateinit var arView: ARSceneView
    private lateinit var tapOverlay: View
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var tvScore: TextView
    private lateinit var tvRemaining: TextView
    private lateinit var tvStatus: TextView
    private lateinit var lockedScreen: View  // shown when AR hunt is already completed

    // --- Firebase ---
    private val db   = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // --- GPS ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var originLat: Double? = null
    private var originLng: Double? = null

    // --- Checkpoints ---
    private val checkpoints       = mutableListOf<ARCheckpoint>()
    private var checkpointsLoaded = false

    // --- AR state ---
    private val spawnedObjects       = mutableListOf<SpawnedObject>()
    private val spawnedIds           = mutableSetOf<String>()
    private var latestFrame: Frame?  = null
    private var stableTrackingFrames = 0
    private val requiredStableFrames = 30

    // --- Score ---
    private var sessionScore = 0
    private var gameOver     = false

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arView       = view.findViewById(R.id.arView)
        tvScore      = view.findViewById(R.id.tvScore)
        tvRemaining  = view.findViewById(R.id.tvRemaining)
        tvStatus     = view.findViewById(R.id.tvStatus)
        lockedScreen = view.findViewById(R.id.lockedScreen)

        // Hide locked screen initially while we check
        lockedScreen.visibility = View.GONE

        arView.planeRenderer.isVisible = false

        setupTapOverlay(view as FrameLayout)

        arView.configureSession { _, config ->
            config.planeFindingMode    = Config.PlaneFindingMode.HORIZONTAL
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        }

        arView.onSessionUpdated = { _, frame ->
            latestFrame = frame
            if (frame.camera.trackingState == TrackingState.TRACKING) {
                stableTrackingFrames++
            } else {
                stableTrackingFrames = 0
            }
            if (!gameOver &&
                stableTrackingFrames >= requiredStableFrames &&
                checkpointsLoaded &&
                originLat != null
            ) {
                spawnPendingCheckpoints(frame)
            }
        }

        updateHud()

        // Check lock status FIRST — only start GPS/AR if the user is allowed to play
        checkArLockStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    // ---------------------------------------------------------------------------
    // Lock check — reads arCompleted from the user's Firestore document
    // ---------------------------------------------------------------------------
    private fun checkArLockStatus() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            // Not signed in — allow play (or redirect to login if you prefer)
            acquireGpsAndLoadCheckpoints()
            return
        }

        tvStatus.text = "Checking status…"

        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val completed = doc.getBoolean("arCompleted") ?: false
                if (completed) {
                    showLockedScreen()
                } else {
                    acquireGpsAndLoadCheckpoints()
                }
            }
            .addOnFailureListener {
                // If check fails, allow play — better to let through than block unfairly
                acquireGpsAndLoadCheckpoints()
            }
    }

    // ---------------------------------------------------------------------------
    // Locked screen — hides AR, shows completion message
    // ---------------------------------------------------------------------------
    private fun showLockedScreen() {
        // Hide all AR UI
        arView.visibility       = View.GONE
        tapOverlay.visibility   = View.GONE
        tvScore.visibility      = View.GONE
        tvRemaining.visibility  = View.GONE
        tvStatus.visibility     = View.GONE

        // Show the locked overlay
        lockedScreen.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------------------
    // GPS
    // ---------------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun acquireGpsAndLoadCheckpoints() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            tvStatus.text = "Location permission required"
            return
        }

        tvStatus.text = "Locating…"

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) setOrigin(loc.latitude, loc.longitude)
        }

        @Suppress("DEPRECATION")
        val request = LocationRequest.create().apply {
            priority        = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval        = 1000L
            fastestInterval = 500L
            numUpdates      = 3
        }

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            setOrigin(loc.latitude, loc.longitude)
        }
    }

    private fun setOrigin(lat: Double, lng: Double) {
        if (originLat != null) return
        originLat = lat
        originLng = lng
        tvStatus.text = "GPS locked — walk to explore"
        loadCheckpoints()
    }

    // ---------------------------------------------------------------------------
    // Firestore — load checkpoints
    // ---------------------------------------------------------------------------
    private fun loadCheckpoints() {
        db.collection("qrCodes").get()
            .addOnSuccessListener { snapshot ->
                checkpoints.clear()
                for (doc in snapshot.documents) {
                    val lat    = doc.getDouble("latitude")  ?: continue
                    val lng    = doc.getDouble("longitude") ?: continue
                    val points = (doc.getLong("points") ?: 0L).toInt()
                    checkpoints.add(
                        ARCheckpoint(
                            id     = doc.id,
                            lat    = lat,
                            lng    = lng,
                            points = points,
                            label  = when {
                                points >= 100 -> "Gold Trophy"
                                points >= 50  -> "Silver Medal"
                                else          -> "Bronze Coin"
                            }
                        )
                    )
                }
                checkpointsLoaded = true
                updateHud()
            }
            .addOnFailureListener {
                tvStatus.text = "Failed to load checkpoints"
            }
    }

    // ---------------------------------------------------------------------------
    // Firestore — save points and mark AR as completed for this user
    // ---------------------------------------------------------------------------
    private fun saveSessionPointsToProfile(pointsEarned: Int, onComplete: () -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            onComplete()
            return
        }

        val userRef = db.collection("users").document(uid)

        userRef.update(
            mapOf(
                "totalPoints" to FieldValue.increment(pointsEarned.toLong()),
                // Lock the AR hunt
                "arCompleted" to true
            )
        ).addOnSuccessListener {
            val redemption = hashMapOf(
                "type"             to "ar_session",
                "points"           to pointsEarned,
                "timestamp"        to FieldValue.serverTimestamp(),
                "checkpointsFound" to spawnedIds.size
            )
            userRef.collection("redemptions")
                .add(redemption)
                .addOnCompleteListener { onComplete() }
        }.addOnFailureListener {
            onComplete()
        }
    }

    // ---------------------------------------------------------------------------
    // Spawn checkpoints into AR world space
    // ---------------------------------------------------------------------------
    private fun spawnPendingCheckpoints(frame: Frame) {
        val oLat = originLat ?: return
        val oLng = originLng ?: return

        for (cp in checkpoints) {
            if (spawnedIds.contains(cp.id)) continue

            val (offsetX, offsetZ) = latLngToMetreOffset(oLat, oLng, cp.lat, cp.lng)
            val cameraY = frame.camera.pose.ty()

            val spawnPose = Pose.makeTranslation(
                offsetX.toFloat(),
                cameraY,
                offsetZ.toFloat()
            )

            val anchor = arView.session?.createAnchor(spawnPose) ?: continue

            val scale = when {
                cp.points >= 100 -> 0.5f
                cp.points >= 50  -> 0.75f
                else             -> 1.1f
            }

            val anchorNode = AnchorNode(arView.engine, anchor)
            arView.addChildNode(anchorNode)

            val model = ModelNode(
                modelInstance = arView.modelLoader.createModelInstance(
                    assetFileLocation = "models/trophy.glb"
                ),
                scaleToUnits = scale
            )

            model.rotation    = io.github.sceneview.math.Rotation(90f, 0f, 0f)
            model.isTouchable = true

            val spawned = SpawnedObject(anchorNode, model, cp)
            model.onSingleTapConfirmed = {
                collectObject(spawned)
                true
            }

            anchorNode.addChildNode(model)
            spawnedObjects.add(spawned)
            spawnedIds.add(cp.id)
        }

        updateHud()
    }

    // ---------------------------------------------------------------------------
    // Coordinate conversion
    // ---------------------------------------------------------------------------
    private fun latLngToMetreOffset(
        originLat: Double, originLng: Double,
        targetLat: Double, targetLng: Double
    ): Pair<Double, Double> {
        val R      = 6_371_000.0
        val dLat   = Math.toRadians(targetLat - originLat)
        val dLng   = Math.toRadians(targetLng - originLng)
        val midLat = Math.toRadians((originLat + targetLat) / 2.0)
        val north  = dLat * R
        val east   = dLng * R * cos(midLat)
        return Pair(east, -north)
    }

    // ---------------------------------------------------------------------------
    // Tap & collection
    // ---------------------------------------------------------------------------
    private fun handleTap(tapX: Float, tapY: Float) {
        if (gameOver) return
        val frame = latestFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        var closest: SpawnedObject? = null
        var closestDist = Float.MAX_VALUE
        val hitRadius = maxOf(arView.width, arView.height) * 0.25f

        for (obj in spawnedObjects) {
            val pos = getScreenPos(frame, obj.anchorNode.anchor?.pose ?: continue) ?: continue
            val dx = tapX - pos.first
            val dy = tapY - pos.second
            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist <= hitRadius && dist < closestDist) {
                closestDist = dist
                closest = obj
            }
        }

        closest?.let { collectObject(it) }
    }

    private fun collectObject(obj: SpawnedObject) {
        if (!spawnedObjects.contains(obj)) return

        spawnedObjects.remove(obj)
        obj.anchorNode.anchor?.detach()
        arView.removeChildNode(obj.anchorNode)

        sessionScore += obj.checkpoint.points

        Toast.makeText(
            requireContext(),
            "${obj.checkpoint.label} collected! +${obj.checkpoint.points} pts",
            Toast.LENGTH_SHORT
        ).show()

        updateHud()

        if (spawnedObjects.isEmpty() && spawnedIds.size >= checkpoints.size && checkpoints.isNotEmpty()) {
            finishRound()
        }
    }

    // ---------------------------------------------------------------------------
    // Round end — save points + lock, then show dialog
    // ---------------------------------------------------------------------------
    private fun finishRound() {
        gameOver = true
        tvStatus.text = "Saving score…"

        saveSessionPointsToProfile(sessionScore) {
            if (isAdded) showScoreScreen()
        }
    }

    // ---------------------------------------------------------------------------
    // HUD & score screen
    // ---------------------------------------------------------------------------
    private fun updateHud() {
        tvScore.text = "Score: $sessionScore"
        val stillToSpawn = checkpoints.size - spawnedIds.size
        tvRemaining.text = when {
            !checkpointsLoaded -> "Loading checkpoints…"
            originLat == null  -> "Waiting for GPS…"
            else -> "${spawnedObjects.size} nearby · $stillToSpawn more to find"
        }
    }

    private fun showScoreScreen() {
        val maxPossible = checkpoints.sumOf { it.points }.takeIf { it > 0 } ?: 1
        val rank = when {
            sessionScore >= maxPossible * 0.8 -> "🥇 Trophy Hunter"
            sessionScore >= maxPossible * 0.5 -> "🥈 Explorer"
            else                              -> "🥉 Scout"
        }

        val uid = auth.currentUser?.uid
        if (uid != null) {
            db.collection("users").document(uid).get()
                .addOnSuccessListener { doc ->
                    val newTotal = doc.getLong("totalPoints") ?: sessionScore.toLong()
                    if (isAdded) buildScoreDialog(rank, newTotal).show()
                }
                .addOnFailureListener {
                    if (isAdded) buildScoreDialog(rank, null).show()
                }
        } else {
            buildScoreDialog(rank, null).show()
        }
    }

    private fun buildScoreDialog(rank: String, newTotal: Long?): AlertDialog {
        val totalLine = if (newTotal != null) "\nTotal account points: $newTotal pts" else ""

        return AlertDialog.Builder(requireContext())
            .setTitle("Round Complete!")
            .setMessage(
                "Rank: $rank\n\n" +
                        "This session: +$sessionScore pts$totalLine\n\n" +
                        "All checkpoints found!\n" +
                        "Points have been added to your account.\n\n" +
                        "The AR hunt is now completed.\nYou will not be able to participate again."
            )
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                // Navigate back — no Play Again since hunt is now locked
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
            .create()
    }

    // ---------------------------------------------------------------------------
    // Overlay
    // ---------------------------------------------------------------------------
    private fun setupTapOverlay(root: FrameLayout) {
        tapOverlay = View(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable  = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        root.addView(tapOverlay)

        gestureDetector = GestureDetectorCompat(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    handleTap(e.x, e.y)
                    return true
                }
            }
        )
        tapOverlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun getScreenPos(frame: Frame, pose: Pose): Pair<Float, Float>? {
        val screenW = arView.width.toFloat()
        val screenH = arView.height.toFloat()
        if (screenW == 0f || screenH == 0f) return null

        val worldPos = floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1f)

        val viewMatrix = FloatArray(16)
        frame.camera.getViewMatrix(viewMatrix, 0)

        val projMatrix = FloatArray(16)
        frame.camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

        val camPos = FloatArray(4)
        multiplyMatrixVector(viewMatrix, worldPos, camPos)

        val clipPos = FloatArray(4)
        multiplyMatrixVector(projMatrix, camPos, clipPos)

        if (clipPos[3] <= 0f) return null

        val ndcX = clipPos[0] / clipPos[3]
        val ndcY = clipPos[1] / clipPos[3]

        return Pair(
            (ndcX + 1f) / 2f * screenW,
            (1f - ndcY) / 2f * screenH
        )
    }

    private fun multiplyMatrixVector(mat: FloatArray, vec: FloatArray, out: FloatArray) {
        for (i in 0..3) {
            out[i] = mat[i] * vec[0] +
                    mat[i + 4] * vec[1] +
                    mat[i + 8] * vec[2] +
                    mat[i + 12] * vec[3]
        }
    }
}