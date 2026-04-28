package com.example.fanhunt

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode

class ARScreenFragment : Fragment(R.layout.fragment_ar) {

    private lateinit var arView: ARSceneView
    private lateinit var tapOverlay: View

    private var rewardNode: AnchorNode? = null
    private var modelNode: ModelNode? = null

    private var rewardClaimed = false
    private var rewardSpawned = false
    private var isSpawning = false

    private var latestFrame: Frame? = null

    private lateinit var gestureDetector: GestureDetectorCompat

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arView = view.findViewById(R.id.arView)
        arView.planeRenderer.isVisible = false

        // Transparent overlay sits above ARSceneView in Z-order, so our gesture
        // detector receives taps before SceneView's internal listener swallows them.
        tapOverlay = View(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        (view as FrameLayout).addView(tapOverlay)

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

        arView.configureSession { _, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        }

        arView.onSessionUpdated = { _, frame ->
            latestFrame = frame
            if (!rewardSpawned && !isSpawning &&
                frame.camera.trackingState == TrackingState.TRACKING
            ) {
                spawnTrophyInFront(frame)
            }
        }
    }

    private fun handleTap(tapX: Float, tapY: Float) {
        if (!rewardClaimed && rewardSpawned) {
            val pos = getTrophyScreenPos() ?: return
            val (sx, sy) = pos
            val hitRadius = maxOf(arView.width, arView.height) * 0.25f
            val dx = tapX - sx
            val dy = tapY - sy
            if ((dx * dx + dy * dy) <= hitRadius * hitRadius) {
                claimReward()
            }
        } else if (!rewardSpawned && !isSpawning) {
            latestFrame?.let { spawnTrophyInFront(it) }
                ?: Toast.makeText(requireContext(), "Move phone slowly...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getTrophyScreenPos(): Pair<Float, Float>? {
        val frame = latestFrame ?: return null
        val anchor = rewardNode?.anchor ?: return null
        val screenW = arView.width.toFloat()
        val screenH = arView.height.toFloat()
        if (screenW == 0f || screenH == 0f) return null

        val anchorPose = anchor.pose
        val worldPos = floatArrayOf(anchorPose.tx(), anchorPose.ty(), anchorPose.tz(), 1f)

        val viewMatrix = FloatArray(16)
        frame.camera.getViewMatrix(viewMatrix, 0)

        val projMatrix = FloatArray(16)
        frame.camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

        val cameraSpacePos = FloatArray(4)
        multiplyMatrixVector(viewMatrix, worldPos, cameraSpacePos)

        val clipSpacePos = FloatArray(4)
        multiplyMatrixVector(projMatrix, cameraSpacePos, clipSpacePos)

        if (clipSpacePos[3] <= 0f) return null

        val ndcX = clipSpacePos[0] / clipSpacePos[3]
        val ndcY = clipSpacePos[1] / clipSpacePos[3]

        val screenX = (ndcX + 1f) / 2f * screenW
        val screenY = (1f - ndcY) / 2f * screenH

        return Pair(screenX, screenY)
    }

    private fun multiplyMatrixVector(mat: FloatArray, vec: FloatArray, out: FloatArray) {
        for (i in 0..3) {
            out[i] = mat[i] * vec[0] +
                    mat[i + 4] * vec[1] +
                    mat[i + 8] * vec[2] +
                    mat[i + 12] * vec[3]
        }
    }

    private fun claimReward() {
        if (rewardClaimed) return
        rewardClaimed = true

        val anchorToDetach = rewardNode?.anchor
        rewardNode?.let { arView.removeChildNode(it) }
        anchorToDetach?.detach()

        rewardNode = null
        modelNode = null

        view?.context?.let { ctx ->
            Toast.makeText(ctx, "Trophy collected!", Toast.LENGTH_SHORT).show()
            Toast.makeText(ctx, "+100 Points!", Toast.LENGTH_LONG).show()
        }
    }

    private fun spawnTrophyInFront(frame: Frame) {
        if (frame.camera.trackingState == TrackingState.STOPPED) return

        isSpawning = true

        val pose = frame.camera.displayOrientedPose.compose(
            Pose.makeTranslation(0f, 0f, -1.2f)
        )

        val anchor = arView.session?.createAnchor(pose) ?: run {
            isSpawning = false
            return
        }

        val anchorNode = AnchorNode(arView.engine, anchor)
        arView.addChildNode(anchorNode)

        val model = ModelNode(
            modelInstance = arView.modelLoader.createModelInstance(
                assetFileLocation = "models/trophy.glb"
            ),
            scaleToUnits = 0.8f
        )

        // Adjust rotation if trophy faces away — try (0f, 180f, 0f)
        model.rotation = io.github.sceneview.math.Rotation(90f, 0f, 0f)
        model.isTouchable = true

        // Secondary tap path via SceneView's own gesture system
        model.onSingleTapConfirmed = {
            claimReward()
            true
        }

        anchorNode.addChildNode(model)

        rewardNode = anchorNode
        modelNode = model
        rewardSpawned = true
        isSpawning = false
    }
}