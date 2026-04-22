package com.example.fanhunt

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ARScreenFragment : Fragment(R.layout.fragment_ar) {

    private lateinit var arView: ARSceneView

    private var rewardNode: AnchorNode? = null
    private var rewardClaimed = false
    private var rewardSpawned = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arView = view.findViewById(R.id.arView)

        // Hide plane dots
        arView.planeRenderer.isVisible = false

        // Enable AR session
        arView.configureSession { _, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL

            // 🔥 Fix dark model (lighting)
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        }

        // 🔥 Spawn trophy once tracking starts
        arView.onSessionUpdated = { _, frame ->

            if (!rewardSpawned) {

                val camera = frame.camera

                if (camera.trackingState == TrackingState.TRACKING) {

                    // 🎯 Random position around user (3–5 meters)
                    val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
                    val distance = Random.nextFloat() * 2f + 3f

                    val x = distance * cos(angle)
                    val z = distance * sin(angle)

                    val pose = camera.pose.compose(
                        Pose.makeTranslation(x, -0.5f, -z)
                    )

                    val anchor = arView.session?.createAnchor(pose)

                    if (anchor != null) {

                        val anchorNode = AnchorNode(arView.engine, anchor)
                        arView.addChildNode(anchorNode)

                        val model = ModelNode(
                            modelInstance = arView.modelLoader.createModelInstance(
                                assetFileLocation = "models/trophy.glb"
                            ),
                            scaleToUnits = 0.5f
                        )

                        // 🔥 Keep upright
                        model.rotation = io.github.sceneview.math.Rotation(0f, 180f, 0f)

                        // ✅ TAP ONLY ON TROPHY
                        model.onSingleTapConfirmed = {

                            if (!rewardClaimed) {

                                rewardClaimed = true

                                arView.removeChildNode(anchorNode)
                                rewardNode = null

                                Toast.makeText(
                                    requireContext(),
                                    "+100 Points!",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            true
                        }

                        anchorNode.addChildNode(model)

                        rewardNode = anchorNode
                        rewardSpawned = true

                        Toast.makeText(
                            requireContext(),
                            "Find the trophy nearby 👀",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
}