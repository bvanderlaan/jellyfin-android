package org.jellyfin.mobile.player.xr

import android.content.Context
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.widget.ImageButton
import android.widget.ImageView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Component
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.MovableComponent
import androidx.xr.scenecore.InteractableComponent
import androidx.xr.scenecore.InputEvent
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.scene
import androidx.xr.scenecore.SurfaceEntity
import org.jellyfin.sdk.model.api.Video3dFormat
import java.util.concurrent.Executor
import timber.log.Timber

/**
 * Manages the Android XR session for stereoscopic video playback.
 */
@RequiresApi(Build.VERSION_CODES.R)
class XrPlayerSession(
    private val video3dFormat: Video3dFormat,
) {
    private var xrSession: Session? = null
    private var surfaceEntity: SurfaceEntity? = null
    private var controlsEntity: PanelEntity? = null
    private var containerEntity: Entity? = null

    /** The [Surface] to pass to ExoPlayer, or null if not yet ready. */
    var videoSurface: Surface? = null
        private set

    /**
     * Initialises the XR session, requests Full Space Mode, and creates a [SurfaceEntity].
     *
     * @return true on success, false if XR is unavailable or session setup failed.
     */
    @MainThread
    fun start(activity: Activity): Boolean {
        return try {
            val createResult = Session.create(activity)
            if (createResult !is SessionCreateSuccess) {
                Timber.w(
                    "XR Session.create() returned non-success result: %s",
                    createResult.javaClass.simpleName,
                )
                return false
            }
            val session = createResult.session
            xrSession = session

            session.scene.requestFullSpace()

            // Position screen 1.5 meters directly in front of the user
            val pose = session.scene.mainPanelEntity.poseInActivitySpace.compose(
                Pose(Vector3(0.0f, 0.0f, -1.5f))
            )

            containerEntity = Entity.create(
                session,
                pose = pose,
                name = "ContainerEntity",
                parent = session.scene.activitySpace,
            )
            surfaceEntity = createSurfaceEntity(session, containerEntity!!)
            // Retrieve the surface for ExoPlayer
            videoSurface = surfaceEntity!!.getSurface()

            session.scene.mainPanelEntity.setEnabled(false)

            Timber.i(
                "XR session started – video3dFormat=%s surface=%s",
                video3dFormat,
                videoSurface,
            )
            videoSurface != null
        } catch (e: Exception) {
            Timber.e(e, "Failed to start XR session")
            false
        }
    }

    private fun createSurfaceEntity(session: Session, parent: Entity) : SurfaceEntity {
        // Resolve the stereo mode configuration
        val stereoModeValue = when (video3dFormat) {
            Video3dFormat.HALF_SIDE_BY_SIDE -> SurfaceEntity.StereoMode.SIDE_BY_SIDE
            Video3dFormat.FULL_SIDE_BY_SIDE -> SurfaceEntity.StereoMode.SIDE_BY_SIDE
            Video3dFormat.HALF_TOP_AND_BOTTOM -> SurfaceEntity.StereoMode.TOP_BOTTOM
            Video3dFormat.FULL_TOP_AND_BOTTOM -> SurfaceEntity.StereoMode.TOP_BOTTOM
            Video3dFormat.MVC -> SurfaceEntity.StereoMode.SIDE_BY_SIDE
        }

        // Create quad shape with 16:9 aspect ratio
        val extents = FloatSize2d(16f / 9f, 1f)
        val quad = SurfaceEntity.Shape.Quad(extents)

        // Create spatial SurfaceEntity
        return SurfaceEntity.create(
            session,
            pose = Pose.Identity,
            quad,
            stereoModeValue,
            parent = parent,
        )
    }

    /**
     * Routes ExoPlayer video output to the spatial [android.view.Surface].
     */
    fun attachPlayer(player: ExoPlayer) {
        val surface = videoSurface ?: return
        player.setVideoSurface(surface)
        Timber.d("Attached XR surface to ExoPlayer")
    }

    /**
     * Hosts the player controls in a [PanelEntity] and makes both the controls panel
     * and the video screen movable via system dragging.
     */
    fun attachControlsView(view: View, activity: Activity) {
        val session = xrSession ?: return
        val surface = surfaceEntity ?: return
        val quadShape = surface.shape as? SurfaceEntity.Shape.Quad ?: return
        val container = containerEntity ?: return

        try {
            val controlsPose = Pose(Vector3(0.0f, 0.0f, 0.01f))
            val panelSize = quadShape.extents

            // hide the controls at first, relay on hover effect to show them.
            view.alpha = 0f

            // Create controls PanelEntity
            controlsEntity = PanelEntity.create(
                session,
                view,
                dimensions = panelSize,
                name = "PlayerControls",
                pose = controlsPose,
                parent = container,
            )

            val orbiterEntity = attachSideBySideFlipOrbiter(
                session,
                surface,
                container,
                activity,
            )

            container.addComponent(MovableComponent.createSystemMovable(session, scaleInZ = false))
            container.addComponent(
                createControlHoverEffect(session, view, activity.mainExecutor, orbiterEntity),
            )

            Timber.d("XR controls panel attached and made movable")
        } catch (e: Exception) {
            Timber.e(e, "Failed to attach XR controls panel")
        }
    }

    private fun attachSideBySideFlipOrbiter(
        session: Session, surface: SurfaceEntity, parent: Entity, context: Context) : Entity {

        val quadShape = surface.shape as? SurfaceEntity.Shape.Quad
        val widthMeters = (quadShape?.extents?.width ?: 1.6f)
        val heightMeters = (quadShape?.extents?.height ?: 0.9f)
        val orbiterSize = heightMeters * 0.15f

        val orbiterPose = Pose(
            translation = Vector3(
                // 15cm to the right of the video
                x = (widthMeters / 2f) + (orbiterSize / 2f) * 0.15f,
                y = 0f,
                z = 0f,
            ),
        )

        val swapButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_rotate)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Swap 3D Eyes"

            var isStereoFlipped = false

            setOnClickListener {
                isStereoFlipped = !isStereoFlipped

                val startPose = surface.getPose()
                val startRotation = startPose.rotation
                val targetRotation = if (isStereoFlipped) {
                    Quaternion(0f, 1f, 0f, 0f)
                } else {
                    Quaternion.Identity
                }

                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 500
                    addUpdateListener { animator ->
                        val fraction = animator.animatedValue as Float
                        val currentRotation = Quaternion.slerp(
                            startRotation,
                            targetRotation,
                            fraction,
                        )

                        surface.setPose(
                            Pose(
                                translation = startPose.translation,
                                rotation = currentRotation,
                            )
                        )
                    }
                    start()
                }
            }
        }

        return PanelEntity.create(
                session,
                view = swapButton,
                dimensions = FloatSize2d(
                    width = orbiterSize,
                    height = orbiterSize,
                ),
                name = "EyeSwapOrbiter",
                pose = orbiterPose,
                parent = parent,
            ).apply {
                // Hover effect will enable/disble this orbiter
                setEnabled(false)
            }
    }

    private fun createControlHoverEffect(
        session: Session,
        controlView: View,
        executor: Executor,
        orbiterEntity: Entity,
    ): Component {

        val handler = Handler(Looper.getMainLooper())
        val autoHideRunnable = Runnable {
            controlView.alpha = 0f
            orbiterEntity.setEnabled(false)
        }

        return InteractableComponent.create(session, executor) {
            when(it.action) {
                InputEvent.Action.HOVER_ENTER, InputEvent.Action.HOVER_MOVE -> {
                    controlView.alpha = 1f
                    orbiterEntity.setEnabled(true)
                    handler.removeCallbacks(autoHideRunnable)
                    handler.postDelayed(autoHideRunnable, 3_000)
                }
                InputEvent.Action.HOVER_EXIT -> {
                    handler.removeCallbacks(autoHideRunnable)
                    handler.postDelayed(autoHideRunnable, 1_000)
                }
            }
        }
    }

    /**
     * Releases spatial resources.
     */
    fun release() {
        xrSession?.scene?.let {
            it.requestHomeSpace()
            it.mainPanelEntity.setEnabled(true)
        }

        try {
            surfaceEntity?.parent = null
            controlsEntity?.parent = null
        } catch (e: Exception) {
            Timber.w(e, "Error detaching XR entities")
        } finally {
            surfaceEntity = null
            controlsEntity = null
            xrSession = null
            videoSurface = null
        }
    }
}
