package com.example.fanhunt

import android.app.AlertDialog
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
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
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Trophy definitions — edit offsetX / offsetZ to reposition in the room.
// Facing forward: -Z = ahead, +Z = behind, +X = right, -X = left (metres)
// ---------------------------------------------------------------------------
data class TrophyDef(
    val id: String,
    val offsetX: Float,
    val offsetZ: Float,
    val label: String
)

data class SpawnedObject(
    val anchorNode: AnchorNode,
    val modelNode: ModelNode,
    val id: String,
    val label: String,
    val points: Int
)

class ARScreenFragment : Fragment(R.layout.fragment_ar) {

    // --- Views ---
    private lateinit var arView: ARSceneView
    private lateinit var tapOverlay: View
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var tvScore: TextView
    private lateinit var tvRemaining: TextView
    private lateinit var tvStatus: TextView
    private lateinit var lockedScreen: View
    private lateinit var btnFinish: Button

    // --- Firebase ---
    private val db   = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // ---------------------------------------------------------------------------
    // 🏆 TROPHY POSITIONS
    // ---------------------------------------------------------------------------
    private val trophyDefs = listOf(
        TrophyDef(id = "checkpoint(100)", offsetX =  0.0f, offsetZ = -4.0f, label = "Gold Trophy"),
        TrophyDef(id = "checkpoint(75)",  offsetX =  3.5f, offsetZ = -3.5f, label = "Silver Medal"),
        TrophyDef(id = "checkpoint(50)",  offsetX = -3.5f, offsetZ = -3.5f, label = "Silver Medal"),
        TrophyDef(id = "checkpoint(25)",  offsetX =  4.5f, offsetZ =  0.0f, label = "Bronze Coin"),
        TrophyDef(id = "checkpoint(20)",  offsetX = -4.5f, offsetZ =  0.0f, label = "Bronze Coin"),
        TrophyDef(id = "checkpoint(10)",  offsetX =  3.0f, offsetZ =  4.0f, label = "Bronze Coin"),
        TrophyDef(id = "checkpoint(5)",   offsetX = -3.0f, offsetZ =  4.0f, label = "Bronze Coin")
    )

    // Points per trophy from Firestore
    private val pointsMap = mutableMapOf<String, Int>()
    private var pointsLoaded = false

    // IDs already collected in previous sessions — loaded from Firestore on start
    private val previouslyCollectedIds = mutableSetOf<String>()

    // IDs collected THIS session
    private val sessionCollectedIds = mutableSetOf<String>()

    // --- AR state ---
    private val spawnedObjects       = mutableListOf<SpawnedObject>()
    private var hasSpawned           = false
    private var latestFrame: Frame?  = null
    private var stableTrackingFrames = 0
    private val requiredStableFrames = 30

    // --- Score ---
    private var sessionScore = 0   // points earned this session only
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
        btnFinish    = view.findViewById(R.id.btnFinish)

        lockedScreen.visibility = View.GONE
        tvStatus.text = "Checking status…"

        btnFinish.setOnClickListener { onFinishPressed() }

        setupTapOverlay(view as FrameLayout)

        arView.planeRenderer.isVisible = false

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
                !hasSpawned &&
                pointsLoaded &&
                stableTrackingFrames >= requiredStableFrames
            ) {
                spawnRemainingTrophies(frame)
            }
        }

        updateHud()
        checkLockThenLoadProgress()
    }

    // ---------------------------------------------------------------------------
    // Step 1 — check lock + load previously collected IDs from Firestore
    // ---------------------------------------------------------------------------
    private fun checkLockThenLoadProgress() {
        val uid = auth.currentUser?.uid ?: run {
            loadPointsFromFirestore()
            return
        }

        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                // Locked — all trophies already collected in a previous session
                if (doc.getBoolean("arCompleted") == true) {
                    showLockedScreen()
                    return@addOnSuccessListener
                }

                // Restore progress — which trophies were collected in earlier sessions
                @Suppress("UNCHECKED_CAST")
                val collected = doc.get("arCollectedIds") as? List<String> ?: emptyList()
                previouslyCollectedIds.addAll(collected)

                loadPointsFromFirestore()
            }
            .addOnFailureListener {
                loadPointsFromFirestore()
            }
    }

    // ---------------------------------------------------------------------------
    // Step 2 — load points from Firestore qrCodes
    // ---------------------------------------------------------------------------
    private fun loadPointsFromFirestore() {
        tvStatus.text = "Loading trophies…"

        val ids = trophyDefs.map { it.id }

        db.collection("qrCodes")
            .whereIn("__name__", ids)
            .get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    pointsMap[doc.id] = (doc.getLong("points") ?: 0L).toInt()
                }
                pointsLoaded = true

                val remaining = trophyDefs.count { it.id !in previouslyCollectedIds }
                tvStatus.text = if (previouslyCollectedIds.isNotEmpty())
                    "Welcome back! $remaining trophies left to find."
                else
                    "Stand at your start point — trophies will appear!"
                updateHud()
            }
            .addOnFailureListener {
                trophyDefs.forEach { pointsMap[it.id] = 10 }
                pointsLoaded = true
                tvStatus.text = "Stand at your start point — trophies will appear!"
                updateHud()
            }
    }

    // ---------------------------------------------------------------------------
    // Step 3 — spawn only trophies not yet collected
    // ---------------------------------------------------------------------------
    private fun spawnRemainingTrophies(frame: Frame) {
        hasSpawned = true

        val cameraPose = frame.camera.pose
        val cameraY    = cameraPose.ty() - 1.2f  // drop below eye level to floor/surface height

        // Direction vectors from camera orientation
        val qx = cameraPose.qx(); val qy = cameraPose.qy()
        val qz = cameraPose.qz(); val qw = cameraPose.qw()
        val fwdX  = 2f * (qx * qz + qw * qy)
        val fwdZ  = 1f - 2f * (qx * qx + qy * qy)
        val rightX =  fwdZ
        val rightZ = -fwdX

        var spawnedCount = 0

        for (def in trophyDefs) {
            // Skip already collected in previous sessions
            if (def.id in previouslyCollectedIds) continue

            val points = pointsMap[def.id] ?: 0

            val worldX = cameraPose.tx() + def.offsetX * rightX + def.offsetZ * fwdX
            val worldZ = cameraPose.tz() + def.offsetX * rightZ + def.offsetZ * fwdZ

            val anchor = arView.session?.createAnchor(
                Pose.makeTranslation(worldX, cameraY, worldZ)
            ) ?: continue

            val anchorNode = AnchorNode(arView.engine, anchor)
            arView.addChildNode(anchorNode)

            val scale = when {
                points >= 100 -> 0.5f
                points >= 50  -> 0.75f
                else          -> 1.1f
            }

            val model = ModelNode(
                modelInstance = arView.modelLoader.createModelInstance(
                    assetFileLocation = "models/trophy.glb"
                ),
                scaleToUnits = scale
            )

            model.rotation    = io.github.sceneview.math.Rotation(-90f, 0f, 0f)  // upright: flip X axis
            model.isTouchable = true

            val spawned = SpawnedObject(anchorNode, model, def.id, def.label, points)
            model.onSingleTapConfirmed = {
                collectObject(spawned)
                true
            }

            anchorNode.addChildNode(model)
            spawnedObjects.add(spawned)
            spawnedCount++
        }

        val totalCollected = previouslyCollectedIds.size
        tvStatus.text = if (totalCollected > 0)
            "$spawnedCount remaining · $totalCollected already found"
        else
            "Find all $spawnedCount trophies!"

        updateHud()
    }

    // ---------------------------------------------------------------------------
    // Collect a trophy
    // ---------------------------------------------------------------------------
    private fun collectObject(obj: SpawnedObject) {
        if (!spawnedObjects.contains(obj)) return

        spawnedObjects.remove(obj)
        obj.anchorNode.anchor?.detach()
        arView.removeChildNode(obj.anchorNode)

        sessionScore += obj.points
        sessionCollectedIds.add(obj.id)

        Toast.makeText(
            requireContext(),
            "${obj.label} collected! +${obj.points} pts",
            Toast.LENGTH_SHORT
        ).show()

        updateHud()

        // All trophies across all sessions collected — full completion
        val allCollected = previouslyCollectedIds.size + sessionCollectedIds.size
        if (allCollected >= trophyDefs.size && spawnedObjects.isEmpty()) {
            finishRound(allComplete = true)
        }
    }

    // ---------------------------------------------------------------------------
    // Finish button — user chooses to stop early
    // ---------------------------------------------------------------------------
    private fun onFinishPressed() {
        if (gameOver) return

        if (sessionScore == 0 && sessionCollectedIds.isEmpty()) {
            // Nothing collected yet — just go back, no need to save
            requireActivity().onBackPressedDispatcher.onBackPressed()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Finish Early?")
            .setMessage(
                "You've collected ${ sessionCollectedIds.size} trophy(s) worth $sessionScore pts this session.\n\n" +
                        "Your points will be saved and the remaining " +
                        "${spawnedObjects.size} trophy(s) will be here when you come back."
            )
            .setPositiveButton("Save & Exit") { _, _ ->
                finishRound(allComplete = false)
            }
            .setNegativeButton("Keep Playing", null)
            .create()
            .show()
    }

    // ---------------------------------------------------------------------------
    // Round end — partial or full completion
    // ---------------------------------------------------------------------------
    private fun finishRound(allComplete: Boolean) {
        if (gameOver) return
        gameOver = true
        btnFinish.isEnabled = false
        tvStatus.text = "Saving score…"

        saveProgress(sessionScore, sessionCollectedIds.toList(), allComplete) {
            if (isAdded) {
                if (allComplete) {
                    showScoreScreen(allComplete = true)
                } else {
                    showPartialSaveConfirmation()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Firestore — save points + collected IDs, lock only if all complete
    // ---------------------------------------------------------------------------
    private fun saveProgress(
        pointsEarned: Int,
        newlyCollectedIds: List<String>,
        lockHunt: Boolean,
        onComplete: () -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: run { onComplete(); return }
        val userRef = db.collection("users").document(uid)

        val updates = mutableMapOf<String, Any>(
            "totalPoints"      to FieldValue.increment(pointsEarned.toLong()),
            // Merge newly collected IDs into the persistent arCollectedIds array
            "arCollectedIds"   to FieldValue.arrayUnion(*newlyCollectedIds.toTypedArray())
        )

        // Only set arCompleted when every trophy has been found
        if (lockHunt) {
            updates["arCompleted"] = true
        }

        userRef.update(updates)
            .addOnSuccessListener {
                val redemption = hashMapOf(
                    "type"             to "ar_session",
                    "points"           to pointsEarned,
                    "timestamp"        to FieldValue.serverTimestamp(),
                    "checkpointsFound" to newlyCollectedIds.size,
                    "allComplete"      to lockHunt
                )
                userRef.collection("redemptions")
                    .add(redemption)
                    .addOnCompleteListener { onComplete() }
            }
            .addOnFailureListener { onComplete() }
    }

    // ---------------------------------------------------------------------------
    // HUD
    // ---------------------------------------------------------------------------
    private fun updateHud() {
        val totalFound  = previouslyCollectedIds.size + sessionCollectedIds.size
        val totalLeft   = trophyDefs.size - totalFound
        tvScore.text    = "Score: $sessionScore"
        tvRemaining.text = when {
            !pointsLoaded -> "Loading…"
            !hasSpawned   -> "Get ready… ($totalLeft to find)"
            else          -> "${spawnedObjects.size} trophies remaining"
        }
    }

    // ---------------------------------------------------------------------------
    // Score screens
    // ---------------------------------------------------------------------------
    private fun showScoreScreen(allComplete: Boolean) {
        val maxPossible = pointsMap.values.sum().takeIf { it > 0 } ?: 1
        val totalEverCollected = previouslyCollectedIds.size + sessionCollectedIds.size
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
                    if (isAdded) buildCompleteDialog(rank, newTotal, totalEverCollected).show()
                }
                .addOnFailureListener {
                    if (isAdded) buildCompleteDialog(rank, null, totalEverCollected).show()
                }
        } else {
            buildCompleteDialog(rank, null, totalEverCollected).show()
        }
    }

    private fun buildCompleteDialog(rank: String, newTotal: Long?, totalFound: Int): AlertDialog {
        val totalLine = if (newTotal != null) "\nTotal account points: $newTotal pts" else ""
        return AlertDialog.Builder(requireContext())
            .setTitle("All Trophies Found! 🏆")
            .setMessage(
                "Rank: $rank\n\n" +
                        "This session: +$sessionScore pts$totalLine\n\n" +
                        "You found all $totalFound / ${trophyDefs.size} trophies!\n" +
                        "Points added to your account.\n\n" +
                        "The AR hunt is now locked.\nAn admin can re-enable it for you."
            )
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
            .create()
    }

    private fun showPartialSaveConfirmation() {
        val totalFound = previouslyCollectedIds.size + sessionCollectedIds.size
        AlertDialog.Builder(requireContext())
            .setTitle("Progress Saved!")
            .setMessage(
                "+$sessionScore pts added to your account.\n\n" +
                        "Trophies found so far: $totalFound / ${trophyDefs.size}\n" +
                        "${trophyDefs.size - totalFound} still waiting to be found — come back any time!"
            )
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
            .create()
            .show()
    }

    // ---------------------------------------------------------------------------
    // Locked screen
    // ---------------------------------------------------------------------------
    private fun showLockedScreen() {
        arView.visibility       = View.GONE
        tapOverlay.visibility   = View.GONE
        tvScore.visibility      = View.GONE
        tvRemaining.visibility  = View.GONE
        tvStatus.visibility     = View.GONE
        btnFinish.visibility    = View.GONE
        lockedScreen.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------------------
    // Tap overlay & projection helpers
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

        // Re-raise btnFinish above the overlay in Z-order so it receives
        // touches before the overlay sees them — no coordinate math needed
        root.removeView(btnFinish)
        root.addView(btnFinish)

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

    // Max real-world distance in metres the user must be within to collect a trophy
    private val collectionRadiusMetres = 1.5f

    // Returns the 3D distance in metres between the camera and a world pose
    private fun distanceToCamera(frame: Frame, pose: Pose): Float {
        val cam = frame.camera.pose
        val dx = cam.tx() - pose.tx()
        val dy = cam.ty() - pose.ty()
        val dz = cam.tz() - pose.tz()
        return sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    private fun handleTap(tapX: Float, tapY: Float) {
        if (gameOver) return
        val frame = latestFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        var closest: SpawnedObject? = null
        var closestDist = Float.MAX_VALUE
        val hitRadius = maxOf(arView.width, arView.height) * 0.25f

        for (obj in spawnedObjects) {
            val anchorPose = obj.anchorNode.anchor?.pose ?: continue

            // Check real-world distance first — user must be within collectionRadiusMetres
            val worldDist = distanceToCamera(frame, anchorPose)
            if (worldDist > collectionRadiusMetres) continue

            val pos = getScreenPos(frame, anchorPose) ?: continue
            val dx  = tapX - pos.first
            val dy  = tapY - pos.second
            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist <= hitRadius && dist < closestDist) {
                closestDist = dist
                closest = obj
            }
        }

        if (closest == null) {
            // Tap landed on screen but no trophy was close enough — give feedback
            val nearestDist = spawnedObjects.mapNotNull { obj ->
                val pose = obj.anchorNode.anchor?.pose ?: return@mapNotNull null
                distanceToCamera(frame, pose)
            }.minOrNull()

            if (nearestDist != null && nearestDist <= collectionRadiusMetres * 3f) {
                val metres = String.format("%.1f", nearestDist)
                Toast.makeText(requireContext(), "Get closer! ${metres}m away", Toast.LENGTH_SHORT).show()
            }
        }

        closest?.let { collectObject(it) }
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