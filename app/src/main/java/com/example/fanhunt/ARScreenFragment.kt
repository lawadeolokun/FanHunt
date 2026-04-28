package com.example.fanhunt

import android.app.AlertDialog
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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
// One entry per trophy. Edit offsetX / offsetZ to move each trophy.
//
// Standing at your start point, facing forward:
//   offsetZ negative  = in front of you
//   offsetZ positive  = behind you
//   offsetX positive  = to your right
//   offsetX negative  = to your left
//
// points is filled in from Firestore after load — leave as 0 here.
// ---------------------------------------------------------------------------
data class TrophyDef(
    val id: String,           // must match a document ID in qrCodes collection
    val offsetX: Float,       // metres left/right from start
    val offsetZ: Float,       // metres forward/back from start
    val label: String         // display name
)

data class SpawnedObject(
    val anchorNode: AnchorNode,
    val modelNode: ModelNode,
    val id: String,
    val label: String,
    var points: Int
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

    // --- Firebase ---
    private val db   = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // ---------------------------------------------------------------------------
    // 🏆 TROPHY POSITIONS — edit offsetX / offsetZ to place each trophy.
    //    id must match your Firestore qrCodes document IDs so points are loaded.
    //    Spread them around the room — different corners, heights, distances.
    // ---------------------------------------------------------------------------
    private val trophyDefs = listOf(
        TrophyDef(id = "checkpoint(100)", offsetX =  0.0f, offsetZ = -4.0f, label = "Gold Trophy"),   // straight ahead
        TrophyDef(id = "checkpoint(75)",  offsetX =  3.5f, offsetZ = -3.5f, label = "Silver Medal"),  // front-right
        TrophyDef(id = "checkpoint(50)",  offsetX = -3.5f, offsetZ = -3.5f, label = "Silver Medal"),  // front-left
        TrophyDef(id = "checkpoint(25)",  offsetX =  4.5f, offsetZ =  0.0f, label = "Bronze Coin"),   // right wall
        TrophyDef(id = "checkpoint(20)",  offsetX = -4.5f, offsetZ =  0.0f, label = "Bronze Coin"),   // left wall
        TrophyDef(id = "checkpoint(10)",  offsetX =  3.0f, offsetZ =  4.0f, label = "Bronze Coin"),   // back-right
        TrophyDef(id = "checkpoint(5)",   offsetX = -3.0f, offsetZ =  4.0f, label = "Bronze Coin")    // back-left
    )

    // Points per trophy — loaded from Firestore, keyed by document ID
    private val pointsMap = mutableMapOf<String, Int>()
    private var pointsLoaded = false

    // --- AR state ---
    private val spawnedObjects       = mutableListOf<SpawnedObject>()
    private var hasSpawned           = false
    private var latestFrame: Frame?  = null
    private var stableTrackingFrames = 0
    private val requiredStableFrames = 30   // ~1 second of stable tracking before spawning

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

        lockedScreen.visibility = View.GONE
        tvStatus.text = "Checking status…"

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

            // Spawn all trophies once tracking is stable and points are loaded
            if (!gameOver &&
                !hasSpawned &&
                pointsLoaded &&
                stableTrackingFrames >= requiredStableFrames
            ) {
                spawnAllTrophies(frame)
            }
        }

        updateHud()
        checkLockThenLoadPoints()
    }

    // ---------------------------------------------------------------------------
    // Step 1 — check lock, then load points from Firestore
    // ---------------------------------------------------------------------------
    private fun checkLockThenLoadPoints() {
        val uid = auth.currentUser?.uid ?: run {
            loadPointsFromFirestore()
            return
        }

        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (doc.getBoolean("arCompleted") == true) {
                    showLockedScreen()
                } else {
                    loadPointsFromFirestore()
                }
            }
            .addOnFailureListener {
                loadPointsFromFirestore()  // allow play if check fails
            }
    }

    // ---------------------------------------------------------------------------
    // Step 2 — load points for each trophy from Firestore qrCodes
    // ---------------------------------------------------------------------------
    private fun loadPointsFromFirestore() {
        tvStatus.text = "Loading trophies…"

        val ids = trophyDefs.map { it.id }

        // Fetch all checkpoint documents in one batch
        // Firestore whereIn supports up to 10 values — fine for 7 trophies
        db.collection("qrCodes")
            .whereIn("__name__", ids)
            .get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    val pts = (doc.getLong("points") ?: 0L).toInt()
                    pointsMap[doc.id] = pts
                }
                // Any IDs not found in Firestore fall back to 0 pts
                pointsLoaded = true
                tvStatus.text = "Stand at your start point — trophies will appear!"
                updateHud()
            }
            .addOnFailureListener {
                // Firestore failed — use fallback points so the game still works
                trophyDefs.forEach { def -> pointsMap[def.id] = 10 }
                pointsLoaded = true
                tvStatus.text = "Stand at your start point — trophies will appear!"
                updateHud()
            }
    }

    // ---------------------------------------------------------------------------
    // Step 3 — place all 7 trophies at their hardcoded offsets from camera origin
    // ---------------------------------------------------------------------------
    private fun spawnAllTrophies(frame: Frame) {
        hasSpawned = true  // set immediately to prevent re-entry on next frame

        val cameraPose = frame.camera.pose
        val cameraY    = cameraPose.ty()

        // Build a rotation matrix from the camera's current horizontal orientation
        // so offsets are relative to the direction the user is facing, not world north.
        // We extract just the Y-axis rotation from the camera quaternion.
        val qx = cameraPose.qx()
        val qy = cameraPose.qy()
        val qz = cameraPose.qz()
        val qw = cameraPose.qw()

        // Forward vector in world space from camera quaternion
        // ARCore camera looks down -Z in camera space
        val fwdX = 2f * (qx * qz + qw * qy)
        val fwdZ = 1f - 2f * (qx * qx + qy * qy)

        // Right vector is perpendicular to forward in the XZ plane
        val rightX =  fwdZ
        val rightZ = -fwdX

        var spawnedCount = 0

        for (def in trophyDefs) {
            val points = pointsMap[def.id] ?: 0

            // Rotate the hardcoded offset to align with the direction the user faces
            val worldX = cameraPose.tx() + def.offsetX * rightX + def.offsetZ * fwdX
            val worldZ = cameraPose.tz() + def.offsetX * rightZ + def.offsetZ * fwdZ

            val spawnPose = Pose.makeTranslation(worldX, cameraY, worldZ)
            val anchor    = arView.session?.createAnchor(spawnPose) ?: continue

            val scale = when {
                points >= 100 -> 0.5f
                points >= 50  -> 0.75f
                else          -> 1.1f
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

            val spawned = SpawnedObject(
                anchorNode = anchorNode,
                modelNode  = model,
                id         = def.id,
                label      = def.label,
                points     = points
            )

            model.onSingleTapConfirmed = {
                collectObject(spawned)
                true
            }

            anchorNode.addChildNode(model)
            spawnedObjects.add(spawned)
            spawnedCount++
        }

        tvStatus.text = "Find all $spawnedCount trophies!"
        updateHud()
    }

    // ---------------------------------------------------------------------------
    // Tap handling
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
            val dx  = tapX - pos.first
            val dy  = tapY - pos.second
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

        sessionScore += obj.points

        Toast.makeText(
            requireContext(),
            "${obj.label} collected! +${obj.points} pts",
            Toast.LENGTH_SHORT
        ).show()

        updateHud()

        if (spawnedObjects.isEmpty()) {
            finishRound()
        }
    }

    // ---------------------------------------------------------------------------
    // Round end
    // ---------------------------------------------------------------------------
    private fun finishRound() {
        gameOver = true
        tvStatus.text = "Saving score…"
        saveSessionPointsToProfile(sessionScore) {
            if (isAdded) showScoreScreen()
        }
    }

    private fun saveSessionPointsToProfile(pointsEarned: Int, onComplete: () -> Unit) {
        val uid = auth.currentUser?.uid ?: run { onComplete(); return }
        val userRef = db.collection("users").document(uid)

        userRef.update(
            mapOf(
                "totalPoints" to FieldValue.increment(pointsEarned.toLong()),
                "arCompleted" to true
            )
        ).addOnSuccessListener {
            val redemption = hashMapOf(
                "type"             to "ar_session",
                "points"           to pointsEarned,
                "timestamp"        to FieldValue.serverTimestamp(),
                "checkpointsFound" to trophyDefs.size
            )
            userRef.collection("redemptions")
                .add(redemption)
                .addOnCompleteListener { onComplete() }
        }.addOnFailureListener {
            onComplete()
        }
    }

    // ---------------------------------------------------------------------------
    // HUD & score screen
    // ---------------------------------------------------------------------------
    private fun updateHud() {
        tvScore.text     = "Score: $sessionScore"
        tvRemaining.text = when {
            !pointsLoaded -> "Loading…"
            !hasSpawned   -> "Get ready…"
            else          -> "${spawnedObjects.size} trophies remaining"
        }
    }

    private fun showScoreScreen() {
        val maxPossible = pointsMap.values.sum().takeIf { it > 0 } ?: 1
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
                        "All trophies found!\n" +
                        "Points added to your account.\n\n" +
                        "The AR hunt is now locked.\nAn admin can re-enable it for you."
            )
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
            .create()
    }

    // ---------------------------------------------------------------------------
    // Locked screen
    // ---------------------------------------------------------------------------
    private fun showLockedScreen() {
        arView.visibility      = View.GONE
        tapOverlay.visibility  = View.GONE
        tvScore.visibility     = View.GONE
        tvRemaining.visibility = View.GONE
        tvStatus.visibility    = View.GONE
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