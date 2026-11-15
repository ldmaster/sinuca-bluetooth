package com.ldmaster.sinuca.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.ldmaster.sinuca.game.Aim
import com.ldmaster.sinuca.game.Ball
import com.ldmaster.sinuca.game.Contact
import com.ldmaster.sinuca.game.GameWorld
import com.ldmaster.sinuca.game.Table
import com.ldmaster.sinuca.game.TableSurface
import com.ldmaster.sinuca.game.TableTexture
import com.ldmaster.sinuca.game.TableTheme
import com.ldmaster.sinuca.game.TextureQuality
import com.ldmaster.sinuca.game.TrajectoryPredictor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class GameRenderer(
    private val context: Context,
    private val world: GameWorld,
    private val mirrored: Boolean,
    private val theme: TableTheme = TableTheme.DEFAULT,
    private val textureStyle: TableTexture = TableTexture.DEFAULT,
    private val textureQuality: TextureQuality = TextureQuality.DEFAULT
) : GLSurfaceView.Renderer {

    @Volatile
    var localPlayer: Int = 0

    @Volatile
    var cameraMode: Int = 0
        private set

    @Volatile
    var showTrajectory: Boolean = false

    @Volatile
    var aiOpponent: Boolean = false

    @Volatile
    private var shotPower: Float = 0.55f

    @Volatile
    private var strikeX: Float = 0f

    @Volatile
    private var strikeY: Float = 0f

    private var width = 1
    private var height = 1
    private val viewProjection = FloatArray(16)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val eye = FloatArray(3)
    private val translation = FloatArray(16)
    private val ballTextures = IntArray(16)
    private val predictor = TrajectoryPredictor()
    private var lastPredictionKey = Long.MIN_VALUE

    private var sceneProgram = 0
    private var lineProgram = 0
    private var tableMesh: GpuMesh? = null
    private var cueMesh: GpuMesh? = null
    private var sphereMesh: GpuMesh? = null
    private var floorMesh: GpuMesh? = null
    private var backdropMesh: GpuMesh? = null
    private var detailTexture = 0
    private var uBones = 0
    private var uSkinned = 0
    private var pottedAtShot = 0
    private var shotByPlayer = 0
    private var wasMoving = false
    private val restCue = FloatArray(6)

    private class Spectator {
        val pos = floatArrayOf(0f, 0f)
        val path = ArrayList<FloatArray>()
        var targetX = 0f
        var targetZ = 0f
        var yaw = 0f
        var walking = false
        var initialized = false
    }

    /**
     * One player in the scene: its skinned model, where it stands, the cue it
     * carries and the reaction clip currently playing.
     */
    private class Character {
        var model: SkinnedModel? = null
        val spot = Spectator()
        val matrix = FloatArray(16)
        val shotButt = floatArrayOf(0f, 0f, 0f)
        val shotTip = floatArrayOf(0f, 0f, 0f)
        var visible = false
        var active = false
        var cueMix = 0f
        var reactionUntil = 0L
    }

    private val humanChar = Character()
    private val opponentChar = Character()
    private var watchTurn = -1
    private var watchReady = false
    private var watchX = 0f
    private var watchZ = 0f
    private var cueFrozen = false
    private var frozenCueX = 0f
    private var frozenCueZ = 0f
    private var frozenAim = 0.0
    private var lineBuffer = 0
    private var lineVertices: java.nio.FloatBuffer? = null
    private var copyProgram = 0
    private var copyQuad = 0
    private var uCopyImage = 0
    private var cueHalfLength = 0.725f
    private var lastFrame = 0L
    private var startTime = 0L
    private var frameCount = 0
    private var frameTimeSum = 0.0

    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private var uUseColor = 0
    private var uEye = 0
    private var uUseTexture = 0
    private var uTexture = 0
    private var uStyle = 0
    private var uUseLiteral = 0
    private var uUsePattern = 0
    private var uDetailMap = 0
    private var uDetailScale = 0
    private var uDetailBase = 0
    private var uLightDir = 0
    private var uLightVP = 0
    private var uShadowMap = 0
    private var uShadowTexel = 0
    private var uShadowStrength = 0
    private var uLineMvp = 0
    private var uLineColor = 0

    private val sceneTarget = SceneTarget()
    private val shadowMap = ShadowMap()
    private val post = PostFx()
    private val missingUniforms = LinkedHashSet<String>()
    private val cameraLock = Any()
    @Volatile
    private var freeCamera = false
    @Volatile
    private var sensorCamera = false
    private var orbitYaw = 0f
    private var orbitPitch = 38f
    private var orbitDistance = 3.4f
    private var sensorRefYaw = Float.NaN
    private var sensorRefPitch = 0f
    private var sensorRefOrbitYaw = 0f
    private var sensorRefOrbitPitch = 38f
    private var softwareRenderer = false
    private var samples = 1
    private var shadowSize = 2048
    private var anisotropy = 1f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.010f, 0.013f, 0.020f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        val renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: ""
        softwareRenderer = renderer.lowercase().contains("swiftshader") ||
            renderer.lowercase().contains("software")
        samples = pickSamples()
        shadowSize = if (softwareRenderer) 1024 else 2048
        anisotropy = pickAnisotropy()

        sceneProgram = Gl.link(Shaders.SCENE_VERTEX, Shaders.SCENE_FRAGMENT)
        if (sceneProgram == 0) {
            Log.e(TAG, "scene shader rejected; falling back to flat shading")
        }
        lineProgram = Gl.link(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT)
        bindSceneUniforms()
        if (lineProgram != 0) {
            uLineMvp = uniform(lineProgram, "uMVP")
            uLineColor = uniform(lineProgram, "uColor")
            reportMissingUniforms("line", lineProgram)
        }
        copyProgram = Gl.link(Shaders.QUAD_VERTEX, Shaders.COPY_FRAGMENT)
        if (copyProgram != 0) {
            copyQuad = Gl.fullScreenQuad()
            uCopyImage = uniform(copyProgram, "uImage")
            reportMissingUniforms("copy", copyProgram)
        }

        try {
            tableMesh = GpuMesh(loadTable())
            val cue = GlbLoader.load(context, "models/cue.glb")
            cueMesh = GpuMesh(cue)
            var maxX = 0f
            for (i in 0 until cue.vertexCount) {
                val value = abs(cue.positions[i * 3])
                if (value > maxX) maxX = value
            }
            cueHalfLength = maxX
        } catch (error: Exception) {
            Log.e(TAG, "failed to load models", error)
        }

        sphereMesh = GpuMesh(sphere(Table.BALL_RADIUS.toFloat(), segments = 64, rings = 48))
        floorMesh = GpuMesh(floor())
        backdropMesh = GpuMesh(backdrop())
        for (number in 0..15) ballTextures[number] = createBallTexture(number)
        if (textureQuality != TextureQuality.PROCEDURAL) detailTexture = loadDetailTexture()
        humanChar.model = runCatching {
            SkinnedModel(context, "models/player_human.glb")
        }.getOrNull()
        opponentChar.model = when {
            aiOpponent -> runCatching {
                SkinnedModel(context, "models/player_robot.glb")
            }.getOrNull()
            localPlayer >= 0 -> runCatching {
                SkinnedModel(context, "models/player_human.glb")
            }.getOrNull()
            else -> null
        }

        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        lineBuffer = ids[0]

        shadowMap.create(shadowSize, LIGHT_DIRECTION, 4.6f, 4.0f)
        sceneTarget.create(width, height, samples, hdr = true)
        post.create(width, height)

        lastFrame = System.nanoTime()
        startTime = lastFrame
    }

    private fun bindSceneUniforms() {
        if (sceneProgram == 0) return
        uMvp = uniform(sceneProgram, "uMVP")
        uModel = uniform(sceneProgram, "uModel")
        uColor = uniform(sceneProgram, "uColor")
        uUseColor = uniform(sceneProgram, "uUseColor")
        uEye = uniform(sceneProgram, "uEye")
        uUseTexture = uniform(sceneProgram, "uUseTexture")
        uTexture = uniform(sceneProgram, "uTexture")
        uStyle = uniform(sceneProgram, "uStyle")
        uUseLiteral = uniform(sceneProgram, "uUseLiteral")
        uUsePattern = uniform(sceneProgram, "uUsePattern")
        uDetailMap = uniform(sceneProgram, "uDetailMap")
        uDetailScale = uniform(sceneProgram, "uDetailScale")
        uDetailBase = uniform(sceneProgram, "uDetailBase")
        uLightDir = uniform(sceneProgram, "uLightDir")
        uLightVP = uniform(sceneProgram, "uLightVP")
        uShadowMap = uniform(sceneProgram, "uShadowMap")
        uShadowTexel = uniform(sceneProgram, "uShadowTexel")
        uShadowStrength = uniform(sceneProgram, "uShadowStrength")
        uBones = uniform(sceneProgram, "uBones")
        uSkinned = uniform(sceneProgram, "uSkinned")
        reportMissingUniforms("scene", sceneProgram)
    }

    /**
     * A uniform the shader stopped declaring used to fail silently: the lookup
     * returns -1 and every glUniform call becomes a no-op. That is how a dead
     * detail parameter shipped. Collect the misses and log them once instead.
     */
    private fun uniform(program: Int, name: String): Int {
        val location = GLES30.glGetUniformLocation(program, name)
        if (location < 0) missingUniforms.add(name)
        return location
    }

    private fun reportMissingUniforms(label: String, program: Int) {
        if (missingUniforms.isEmpty()) return
        Log.e(TAG, "$label shader is missing uniforms: ${missingUniforms.sorted()}")
        missingUniforms.clear()
    }

    private fun pickSamples(): Int {
        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, max, 0)
        if (softwareRenderer) return if (max[0] >= 2) 2 else 1
        return when {
            max[0] >= 4 -> 4
            max[0] >= 2 -> 2
            else -> 1
        }
    }

    private fun pickAnisotropy(): Float {
        if (!Gl.hasExtension("GL_EXT_texture_filter_anisotropic")) return 1f
        val max = IntArray(1)
        GLES30.glGetIntegerv(MAX_TEXTURE_MAX_ANISOTROPY, max, 0)
        return max[0].coerceIn(1, 8).toFloat()
    }

    private fun loadTable(): MeshData = runCatching {
        GlbLoader.load(context, theme.asset, theme.key)
    }.getOrElse { error ->
        Log.e(TAG, "failed to load ${theme.asset}; falling back to table.glb", error)
        GlbLoader.load(context, "models/table.glb", theme.key)
    }

    override fun onSurfaceChanged(gl: GL10?, surfaceWidth: Int, surfaceHeight: Int) {
        width = surfaceWidth.coerceAtLeast(1)
        height = surfaceHeight.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
        updateCamera()
        sceneTarget.create(width, height, samples, hdr = true)
        post.create(width, height)
    }

    fun cycleCamera() {
        cameraMode = (cameraMode + 1) % 4
        updateCamera()
    }

    val freeCameraActive: Boolean get() = freeCamera
    val sensorCameraActive: Boolean get() = sensorCamera

    /** Toggle the orbiting free camera; it replaces the fixed presets while on. */
    fun setFreeCamera(enabled: Boolean) {
        synchronized(cameraLock) {
            freeCamera = enabled
            if (enabled) {
                sensorCamera = false
                orbitYaw = 0f
                orbitPitch = 38f
                orbitDistance = 3.4f
            }
            updateCameraLocked()
        }
    }

    /** Toggle the device-orientation camera, anchored to the current view. */
    fun setSensorCamera(enabled: Boolean) {
        synchronized(cameraLock) {
            sensorCamera = enabled
            if (enabled) {
                freeCamera = false
                sensorRefYaw = Float.NaN
                orbitYaw = 0f
                orbitPitch = 38f
                orbitDistance = 3.4f
            }
            updateCameraLocked()
        }
    }

    fun orbitBy(dxPixels: Float, dyPixels: Float) {
        synchronized(cameraLock) {
            if (!freeCamera && !sensorCamera) return
            orbitYaw = (orbitYaw + dxPixels * 0.25f) % 360f
            orbitPitch = (orbitPitch + dyPixels * 0.18f).coerceIn(6f, 82f)
            updateCameraLocked()
        }
    }

    fun zoomBy(factor: Float) {
        if (factor <= 0f) return
        synchronized(cameraLock) {
            if (!freeCamera && !sensorCamera) return
            orbitDistance = (orbitDistance / factor).coerceIn(1.8f, 6.5f)
            updateCameraLocked()
        }
    }

    /**
     * Absolute device heading, in degrees, from the rotation vector. The first
     * sample becomes the reference so enabling the mode never jumps.
     */
    fun setSensorAngles(yawDegrees: Float, pitchDegrees: Float) {
        synchronized(cameraLock) {
            if (!sensorCamera) return
            if (sensorRefYaw.isNaN()) {
                sensorRefYaw = yawDegrees
                sensorRefPitch = pitchDegrees
                sensorRefOrbitYaw = orbitYaw
                sensorRefOrbitPitch = orbitPitch
            }
            val deltaYaw = shortestAngle(yawDegrees - sensorRefYaw)
            val deltaPitch = pitchDegrees - sensorRefPitch
            orbitYaw = (sensorRefOrbitYaw + deltaYaw) % 360f
            orbitPitch = (sensorRefOrbitPitch - deltaPitch).coerceIn(6f, 82f)
            updateCameraLocked()
        }
    }

    private fun shortestAngle(degrees: Float): Float {
        var value = degrees % 360f
        if (value > 180f) value -= 360f
        if (value < -180f) value += 360f
        return value
    }

    private fun updateCamera() {
        synchronized(cameraLock) { updateCameraLocked() }
    }

    private fun updateCameraLocked() {
        val aspect = width.toFloat() / height.toFloat()
        if (freeCamera || sensorCamera) {
            val yawRadians = Math.toRadians(orbitYaw.toDouble())
            val pitchRadians = Math.toRadians(orbitPitch.toDouble())
            val horizontal = cos(pitchRadians) * orbitDistance
            val ex = (sin(yawRadians) * horizontal).toFloat()
            val ey = (0.55 + sin(pitchRadians) * orbitDistance).toFloat()
            val ez = (cos(yawRadians) * horizontal).toFloat()
            Matrix.perspectiveM(projection, 0, 46f, aspect, 0.1f, 40f)
            eye[0] = ex
            eye[1] = ey
            eye[2] = ez
            Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, 0.72f, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
            return
        }
        val side = if (mirrored) 1f else -1f
        val fieldOfView: Float
        val eyeY: Float
        val eyeZ: Float
        val lookY: Float
        when (cameraMode) {
            1 -> {
                fieldOfView = 44f
                eyeY = 3.70f
                eyeZ = 1.55f * side
                lookY = 0.72f
            }
            2 -> {
                fieldOfView = 50f
                eyeY = 1.50f
                eyeZ = 3.55f * side
                lookY = 0.82f
            }
            3 -> {
                fieldOfView = 42f
                eyeY = 4.60f
                eyeZ = 0.0f
                lookY = 0.72f
            }
            else -> {
                fieldOfView = 46f
                eyeY = 2.62f
                eyeZ = 3.15f * side
                lookY = 0.70f
            }
        }
        Matrix.perspectiveM(projection, 0, fieldOfView, aspect, 0.1f, 40f)
        eye[0] = 0f
        eye[1] = eyeY
        eye[2] = eyeZ
        val upY = if (cameraMode == 3) 0f else 1f
        val upZ = if (cameraMode == 3) -1f else 0f
        Matrix.setLookAtM(
            view, 0,
            eye[0], eye[1], eye[2],
            0f, lookY, 0f,
            0f, upY, upZ
        )
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1_000_000_000.0).coerceIn(0.0, 0.05)
        lastFrame = now
        synchronized(world) { world.step(dt) }
        updateCharacters(dt.toFloat())

        if (sceneProgram == 0 && lineProgram == 0) return

        val model = FloatArray(16)
        val mvp = FloatArray(16)

        if (shadowMap.texture != 0) {
            shadowMap.begin()
            if (sceneProgram != 0) {
                drawShadowCasters(model, mvp)
            }
            shadowMap.end()
        }

        sceneTarget.bind()
        GLES30.glViewport(0, 0, sceneTarget.width, sceneTarget.height)
        GLES30.glClearColor(0.010f, 0.013f, 0.020f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glUseProgram(sceneProgram)
        GLES30.glUniform3f(uEye, eye[0], eye[1], eye[2])
        GLES30.glUniform3f(uLightDir, LIGHT_DIRECTION[0], LIGHT_DIRECTION[1], LIGHT_DIRECTION[2])
        GLES30.glUniformMatrix4fv(uLightVP, 1, false, shadowMap.matrix, 0)
        GLES30.glUniform1f(uShadowTexel, 1f / shadowMap.size)
        GLES30.glUniform1f(uShadowStrength, if (shadowMap.texture != 0) 1f else 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowMap.texture)
        GLES30.glUniform1i(uShadowMap, 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        drawMesh(backdropMesh, viewProjection, IDENTITY, useVertexColor = true, color = WHITE)
        drawMesh(floorMesh, viewProjection, IDENTITY, useVertexColor = true, color = WHITE)
        drawMesh(
            tableMesh, viewProjection, IDENTITY,
            useVertexColor = true,
            color = WHITE,
            useLiteral = textureQuality != TextureQuality.PROCEDURAL && detailTexture != 0,
            usePattern = textureQuality == TextureQuality.PROCEDURAL,
            detailId = detailTexture
        )

        for (ball in world.balls) {
            if (!ball.active) continue
            val speed = ball.speed
            if (!ball.falling && speed > 0.001) {
                val degrees = Math.toDegrees(speed * dt / Table.BALL_RADIUS).toFloat()
                Matrix.rotateM(
                    ball.visualRotation, 0, degrees,
                    (ball.vz / speed).toFloat(), 0f, (-ball.vx / speed).toFloat()
                )
            }
            val drop = if (ball.falling) 0.18f * ball.fall * ball.fall else 0f
            Matrix.setIdentityM(translation, 0)
            Matrix.translateM(
                translation, 0,
                ball.x.toFloat(),
                (Table.BALL_Y - drop).toFloat(),
                ball.z.toFloat()
            )
            Matrix.multiplyMM(model, 0, translation, 0, ball.visualRotation, 0)
            Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
            drawMesh(
                sphereMesh, mvp, model,
                useVertexColor = true,
                color = WHITE,
                textureId = ballTextures[ball.number],
                useTexture = true
            )
        }

        drawCharacters()

        if (sceneProgram != 0) {
            GLES30.glUseProgram(sceneProgram)
        }
        drawAim()

        val resolved = sceneTarget.resolve()
        if (post.ready) {
            post.render(resolved, width, height, (now - startTime) / 1_000_000_000f)
        } else {
            presentFallback(resolved)
        }

        frameCount++
        frameTimeSum += dt
        if (frameCount % 120 == 0) {
            Log.i(
                TAG,
                "avg frame ${"%.1f".format(frameTimeSum / frameCount * 1000f)}ms " +
                    "samples=$samples shadow=${shadowMap.size} software=$softwareRenderer"
            )
            frameCount = 0
            frameTimeSum = 0.0
        }
    }

    private fun drawShadowCasters(model: FloatArray, mvp: FloatArray) {
        shadowMap.useProgram()
        shadowMap.setMvp(shadowMap.matrix)
        tableMesh?.draw()
        for (ball in world.balls) {
            if (!ball.active) continue
            val drop = if (ball.falling) 0.18f * ball.fall * ball.fall else 0f
            Matrix.setIdentityM(translation, 0)
            Matrix.translateM(
                translation, 0,
                ball.x.toFloat(),
                (Table.BALL_Y - drop).toFloat(),
                ball.z.toFloat()
            )
            Matrix.multiplyMM(mvp, 0, shadowMap.matrix, 0, translation, 0)
            shadowMap.setMvp(mvp)
            sphereMesh?.draw()
        }
        drawCueShadow(model)
        drawCharacterShadow(humanChar, mvp)
        drawCharacterShadow(opponentChar, mvp)
    }

    private fun drawCharacterShadow(char: Character, mvp: FloatArray) {
        val skinned = char.model ?: return
        if (!char.visible || !skinned.isReady) return
        Matrix.multiplyMM(mvp, 0, shadowMap.matrix, 0, char.matrix, 0)
        shadowMap.setMvp(mvp)
        shadowMap.setSkinned(skinned)
        for (primitive in skinned.primitivesList) {
            skinned.draw(primitive, shadowMap.programId)
        }
        shadowMap.clearSkinned()
    }

    private fun drawCueShadow(model: FloatArray) {
        val mesh = cueMesh ?: return
        val mvp = FloatArray(16)
        for (char in arrayOf(humanChar, opponentChar)) {
            if (!char.visible) continue
            cueMatrix(char, model)
            Matrix.multiplyMM(mvp, 0, shadowMap.matrix, 0, model, 0)
            shadowMap.setMvp(mvp)
            mesh.draw()
        }
    }

    /**
     * Both players live in the scene. The shooter steps behind the cue ball,
     * puts the bridge hand on the rail and the grip hand on the cue, and looks
     * along the shot; the other walks around the table to watch from the far
     * side, carrying their own cue. Potting one ball or two or more triggers a
     * different celebration.
     */
    private fun updateCharacters(dt: Float) {
        val cue = world.cueBall()
        if (world.moving) {
            if (!cueFrozen) {
                frozenCueX = cue.x.toFloat()
                frozenCueZ = cue.z.toFloat()
                frozenAim = world.aimAngle
                cueFrozen = true
            }
        } else {
            cueFrozen = false
        }
        humanChar.visible = humanChar.model?.isReady == true && cue.active
        opponentChar.visible = opponentChar.model?.isReady == true && cue.active
        if (!humanChar.visible && !opponentChar.visible) return

        val practiceMode = localPlayer < 0
        val turn = world.turn
        humanChar.active = practiceMode || turn == 0
        opponentChar.active = !practiceMode && opponentChar.model != null && turn == 1

        val now = System.nanoTime()
        if (world.moving && !wasMoving) {
            pottedAtShot = pottedTotal()
            shotByPlayer = if (practiceMode) 0 else turn
        }
        if (!world.moving && wasMoving) announceReactions(now)
        wasMoving = world.moving

        if (!watchReady || turn != watchTurn) {
            val hand = cueGrip(cue.x, cue.z, world.aimAngle)
            val spot = spectatorTarget(hand[0], hand[1])
            watchX = spot[0]
            watchZ = spot[1]
            watchTurn = turn
            watchReady = true
        }

        // Place the shooter first so the other one can give way to the spot it
        // occupies this frame.
        if (humanChar.active) {
            placeCharacter(humanChar, now, dt, Float.NaN, Float.NaN)
            placeCharacter(
                opponentChar, now, dt, humanChar.spot.pos[0], humanChar.spot.pos[1]
            )
        } else if (opponentChar.active) {
            placeCharacter(opponentChar, now, dt, Float.NaN, Float.NaN)
            placeCharacter(
                humanChar, now, dt, opponentChar.spot.pos[0], opponentChar.spot.pos[1]
            )
        } else {
            placeCharacter(humanChar, now, dt, Float.NaN, Float.NaN)
            placeCharacter(opponentChar, now, dt, Float.NaN, Float.NaN)
        }
        animateCharacter(humanChar, now, dt)
        animateCharacter(opponentChar, now, dt)
    }

    /**
     * The shooter stands behind the cue ball and the other player walks around
     * the perimeter. The cue itself changes between the upright carry pose and
     * the shot pose over [CUE_SWAP_SECONDS] instead of popping into place.
     */
    private fun placeCharacter(
        char: Character,
        now: Long,
        dt: Float,
        avoidX: Float,
        avoidZ: Float
    ) {
        if (char.model == null || !char.visible) return
        val step = dt / CUE_SWAP_SECONDS
        if (char.active) {
            syncSpot(char.spot, placeShot(char.matrix, world.cueBall(), world.aimAngle))
            shotCueEnds(char)
        } else {
            stepSpectator(char.spot, char.matrix, watchX, watchZ, dt, avoidX, avoidZ)
        }
        // A reacting player is not on the shot, so the cue goes back upright
        // instead of hanging over the table through the celebration.
        val holding = char.active && now >= char.reactionUntil
        char.cueMix = moveToward(char.cueMix, if (holding) 1f else 0f, step)
    }

    private fun animateCharacter(char: Character, now: Long, dt: Float) {
        val model = char.model ?: return
        if (!char.visible) return
        model.update(dt)
        if (world.moving && char.active) char.reactionUntil = 0L
        if (now < char.reactionUntil) return
        val clip = when {
            char.active && world.moving -> "strike"
            char.active -> "aim"
            char.spot.walking -> "walk"
            else -> "idle"
        }
        if (model.currentClip != clip) model.setClip(clip, loop = clip != "strike")
    }

    /**
     * Called once the balls settle. One ball draws the small celebration, two
     * or more the big one, and the opponent applauds a big break. A potted cue
     * ball or an empty shot reads as a dejected slump instead.
     */
    private fun announceReactions(now: Long) {
        val potted = pottedTotal() - pottedAtShot
        val shooter = if (shotByPlayer == 1) opponentChar else humanChar
        val other = if (shooter === humanChar) opponentChar else humanChar
        when {
            world.cueFouled -> {
                react(shooter, "dejected", 2_000_000_000L, now)
                Log.d(TAG, "reaction: dejected (cue potted) shot by $shotByPlayer")
            }
            potted >= 2 -> {
                react(shooter, "celebrate_big", 2_800_000_000L, now)
                react(other, "applaud", 2_200_000_000L, now)
                Log.d(TAG, "reaction: celebrate_big shot by $shotByPlayer ($potted balls)")
            }
            potted == 1 -> {
                react(shooter, "celebrate", 2_200_000_000L, now)
                Log.d(TAG, "reaction: celebrate shot by $shotByPlayer (1 ball)")
            }
            else -> {
                react(shooter, "dejected", 2_000_000_000L, now)
                Log.d(TAG, "reaction: dejected shot by $shotByPlayer (no ball)")
            }
        }
    }

    private fun react(char: Character, clip: String, duration: Long, now: Long) {
        val model = char.model ?: return
        if (!model.clipNames.any { it.equals(clip, ignoreCase = true) }) {
            Log.w(TAG, "clip $clip missing from the character model")
            return
        }
        model.setClip(clip, loop = true)
        char.reactionUntil = now + duration
    }

    private fun moveToward(value: Float, target: Float, step: Float): Float =
        if (value < target) minOf(value + step, target) else maxOf(value - step, target)

    /** The spectator picks the side of the table farthest from the shot. */
    private fun spectatorTarget(handX: Float, handZ: Float): FloatArray {
        val marginX = (Table.HALF_LENGTH + RING_LENGTH).toFloat()
        val marginZ = (Table.HALF_WIDTH + RING_WIDTH).toFloat()
        val candidates = arrayOf(
            floatArrayOf(0f, marginZ),
            floatArrayOf(0f, -marginZ),
            floatArrayOf(marginX, 0f),
            floatArrayOf(-marginX, 0f)
        )
        return candidates.maxByOrNull {
            hypot(it[0] - handX, it[1] - handZ)
        } ?: candidates[0]
    }

    private fun syncSpot(spot: Spectator, shot: FloatArray) {
        spot.pos[0] = shot[0]
        spot.pos[1] = shot[1]
        spot.yaw = shot[2]
        spot.walking = false
        spot.initialized = true
    }

    private fun stepSpectator(
        spot: Spectator,
        model: FloatArray,
        targetX: Float,
        targetZ: Float,
        dt: Float,
        avoidX: Float,
        avoidZ: Float
    ) {
        if (!spot.initialized) {
            spot.pos[0] = targetX
            spot.pos[1] = targetZ
            spot.yaw = Math.atan2(-targetX.toDouble(), -targetZ.toDouble()).toFloat()
            spot.initialized = true
            spot.targetX = targetX
            spot.targetZ = targetZ
        } else if (targetX != spot.targetX || targetZ != spot.targetZ) {
            spot.path.clear()
            spot.path.addAll(buildWalkPath(spot.pos[0], spot.pos[1], targetX, targetZ))
            spot.targetX = targetX
            spot.targetZ = targetZ
        }

        var moveX = 0f
        var moveZ = 0f
        val waypoint = spot.path.firstOrNull()
        if (waypoint != null) {
            val dx = waypoint[0] - spot.pos[0]
            val dz = waypoint[1] - spot.pos[1]
            val distance = hypot(dx, dz)
            if (distance < 0.08f) {
                spot.path.removeAt(0)
            } else {
                val step = minOf(1.15f * dt, distance)
                moveX = dx / distance * step
                moveZ = dz / distance * step
                spot.pos[0] += moveX
                spot.pos[1] += moveZ
            }
        }
        // The player on the shot owns the space around the table: the waiting
        // player gives way instead of walking through them.
        val awayX = spot.pos[0] - avoidX
        val awayZ = spot.pos[1] - avoidZ
        val gap = hypot(awayX, awayZ)
        val givingWay = avoidX.isFinite() && gap < SPECTATOR_CLEARANCE && gap > 1e-4f
        if (givingWay) {
            val push = (SPECTATOR_CLEARANCE - gap) * (dt * 5f).coerceAtMost(1f)
            spot.pos[0] += awayX / gap * push
            spot.pos[1] += awayZ / gap * push
        }
        spot.walking = spot.path.isNotEmpty() || givingWay
        val desiredYaw = if (spot.walking && (moveX != 0f || moveZ != 0f)) {
            Math.atan2(moveX.toDouble(), moveZ.toDouble()).toFloat()
        } else {
            Math.atan2(-spot.pos[0].toDouble(), -spot.pos[1].toDouble()).toFloat()
        }
        spot.yaw = lerpAngle(spot.yaw, desiredYaw, (dt * 8f).coerceIn(0f, 1f))
        placeRest(model, spot.pos[0], spot.pos[1], spot.yaw)
    }

    /**
     * The spectator walks around the table, never across it: the route hugs the
     * ring outside the rails and turns at the corners.
     */
    private fun buildWalkPath(
        fromX: Float,
        fromZ: Float,
        toX: Float,
        toZ: Float
    ): List<FloatArray> {
        val rx = (Table.HALF_LENGTH + RING_LENGTH).toFloat()
        val rz = (Table.HALF_WIDTH + RING_WIDTH).toFloat()
        val corners = arrayOf(
            floatArrayOf(rx, rz),
            floatArrayOf(-rx, rz),
            floatArrayOf(-rx, -rz),
            floatArrayOf(rx, -rz)
        )
        val from = ringPoint(fromX, fromZ, rx, rz)
        val to = ringPoint(toX, toZ, rx, rz)
        val path = ArrayList<FloatArray>()
        path.add(from)
        val startCorner = nearestCorner(from, corners)
        val endCorner = nearestCorner(to, corners)
        if (startCorner == endCorner) {
            path.add(corners[startCorner])
        } else {
            val forward = (endCorner - startCorner + 4) % 4
            val backward = (startCorner - endCorner + 4) % 4
            if (forward <= backward) {
                for (step in 1..forward) path.add(corners[(startCorner + step) % 4])
            } else {
                for (step in 1..backward) path.add(corners[(startCorner - step + 4) % 4])
            }
        }
        path.add(to)
        path.add(floatArrayOf(toX, toZ))
        return path
    }

    private fun ringPoint(x: Float, z: Float, rx: Float, rz: Float): FloatArray {
        val gapX = rx - abs(x)
        val gapZ = rz - abs(z)
        return if (gapX < gapZ) {
            floatArrayOf(Math.signum(x) * rx, z.coerceIn(-rz, rz))
        } else {
            floatArrayOf(x.coerceIn(-rx, rx), Math.signum(z) * rz)
        }
    }

    private fun nearestCorner(point: FloatArray, corners: Array<FloatArray>): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (index in corners.indices) {
            val distance = hypot(corners[index][0] - point[0], corners[index][1] - point[1])
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    private fun lerpAngle(from: Float, to: Float, amount: Float): Float {
        val twoPi = (Math.PI * 2.0).toFloat()
        var delta = (to - from) % twoPi
        if (delta > Math.PI) delta -= twoPi
        if (delta < -Math.PI) delta += twoPi
        return from + delta * amount
    }

    private fun pottedTotal(): Int {
        return world.balls.count { it.number != 0 && !it.active }
    }

    /** Puts the hands on the cue line where it leaves the playing rectangle. */
    private fun cueGrip(x: Double, z: Double, aim: Double): FloatArray {
        val dx = -cos(aim)
        val dz = -sin(aim)
        var t = Double.MAX_VALUE
        if (dx > 1e-6) t = minOf(t, (Table.HALF_LENGTH - x) / dx)
        else if (dx < -1e-6) t = minOf(t, (-Table.HALF_LENGTH - x) / dx)
        if (dz > 1e-6) t = minOf(t, (Table.HALF_WIDTH - z) / dz)
        else if (dz < -1e-6) t = minOf(t, (-Table.HALF_WIDTH - z) / dz)
        if (!t.isFinite() || t <= 0.0) t = 0.6
        val railX = x + dx * t
        val railZ = z + dz * t
        val hitX = abs(abs(railX) - Table.HALF_LENGTH) < 0.002
        val outX = if (hitX) Math.signum(railX) * 0.06 else 0.0
        val outZ = if (!hitX) Math.signum(railZ) * 0.06 else 0.0
        return floatArrayOf((railX + outX).toFloat(), (railZ + outZ).toFloat())
    }

    private fun placeShot(model: FloatArray, cue: Ball, aim: Double): FloatArray {
        val hand = cueGrip(cue.x, cue.z, aim)
        val toBallX = cue.x - hand[0]
        val toBallZ = cue.z - hand[1]
        val length = hypot(toBallX, toBallZ).coerceAtLeast(0.001)
        val ux = (toBallX / length).toFloat()
        val uz = (toBallZ / length).toFloat()
        // The bridge hand lands on the rail point and the body stands far
        // enough back that the hand never reaches over the cloth.
        val originX = hand[0] - ux * BRIDGE_FORWARD + uz * STANCE_SIDE
        val originZ = hand[1] - uz * BRIDGE_FORWARD - ux * STANCE_SIDE
        val yaw = Math.atan2(ux.toDouble(), uz.toDouble()).toFloat()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, originX, 0f, originZ)
        Matrix.rotateM(model, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        return floatArrayOf(originX, originZ, yaw)
    }

    private fun placeRest(model: FloatArray, x: Float, z: Float, yaw: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, 0f, z)
        Matrix.rotateM(model, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
    }

    private fun drawCharacters() {
        if (sceneProgram == 0) return
        drawCharacter(humanChar)
        drawCharacter(opponentChar)
        drawHeldCue(humanChar)
        drawHeldCue(opponentChar)
    }

    /** Each player carries their own cue: upright at rest, on the ball to shoot. */
    private fun drawHeldCue(char: Character) {
        val mesh = cueMesh ?: return
        if (!char.visible) return
        val model = FloatArray(16)
        cueMatrix(char, model)
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        drawMesh(mesh, mvp, model, useVertexColor = true, color = WHITE)
    }

    private fun drawCharacter(char: Character) {
        val skinned = char.model ?: return
        if (!char.visible || !skinned.isReady) return
        val matrix = char.matrix
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, matrix, 0)
        GLES30.glUseProgram(sceneProgram)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModel, 1, false, matrix, 0)
        GLES30.glUniform1f(uUseColor, 0f)
        GLES30.glUniform1f(uUseTexture, 0f)
        GLES30.glUniform1f(uUseLiteral, 0f)
        GLES30.glUniform1f(uUsePattern, 0f)
        GLES30.glUniform1f(uStyle, 0f)
        GLES30.glUniform1f(uDetailBase, 0f)
        GLES30.glUniform1f(uSkinned, 1f)
        skinned.uploadBones(sceneProgram, uBones)
        for (primitive in skinned.primitivesList) {
            GLES30.glVertexAttrib3f(4, primitive.metallic, primitive.roughness, 6f)
            skinned.draw(primitive, sceneProgram)
        }
        GLES30.glUniform1f(uSkinned, 0f)
    }

    private fun drawAim() {
        val cue = world.cueBall()
        val showAim = cue.active && !world.moving && world.winner < 0 && world.canShoot(localPlayer)
        if (!showAim) return
        val directionX = cos(world.aimAngle).toFloat()
        val directionZ = sin(world.aimAngle).toFloat()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        drawAimDashes(cue, directionX, directionZ)
        val contact = Aim.firstContact(
            cue,
            world.balls,
            directionX.toDouble(),
            directionZ.toDouble()
        )
        if (contact != null) drawSightIndicator(contact)
        if (showTrajectory) drawPrediction()
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawAimDashes(cue: Ball, directionX: Float, directionZ: Float) {
        val startDistance = Table.BALL_RADIUS.toFloat() + 0.012f
        val endDistance = startDistance + 0.55f
        val points = ArrayList<Float>(32)
        var distance = startDistance
        while (distance < endDistance) {
            val dashEnd = (distance + 0.032f).coerceAtMost(endDistance)
            points.add(cue.x.toFloat() + directionX * distance)
            points.add(Table.BALL_Y.toFloat())
            points.add(cue.z.toFloat() + directionZ * distance)
            points.add(cue.x.toFloat() + directionX * dashEnd)
            points.add(Table.BALL_Y.toFloat())
            points.add(cue.z.toFloat() + directionZ * dashEnd)
            distance = dashEnd + 0.022f
        }
        drawLineVertices(points.toFloatArray(), 1f, 0.93f, 0.62f, 0.42f, 2f, GLES30.GL_LINES)
    }

    private fun drawSightIndicator(contact: Contact) {
        val target = contact.target
        val points = ArrayList<Float>(160)
        addCircle(points, contact.ghostX, contact.ghostZ, Table.BALL_RADIUS)
        addCircle(points, target.x, target.z, Table.BALL_RADIUS * 1.12)

        val startX = target.x + contact.normalX * (Table.BALL_RADIUS + 0.008)
        val startZ = target.z + contact.normalZ * (Table.BALL_RADIUS + 0.008)
        val endX = target.x + contact.normalX * (Table.BALL_RADIUS + 0.30)
        val endZ = target.z + contact.normalZ * (Table.BALL_RADIUS + 0.30)
        points.add(startX.toFloat())
        points.add(Table.BALL_Y.toFloat())
        points.add(startZ.toFloat())
        points.add(endX.toFloat())
        points.add(Table.BALL_Y.toFloat())
        points.add(endZ.toFloat())

        drawLineVertices(
            points.toFloatArray(),
            0.55f, 0.95f, 0.85f, 0.60f,
            2.5f,
            GLES30.GL_LINES
        )
    }

    private fun addCircle(points: ArrayList<Float>, centerX: Double, centerZ: Double, radius: Double) {
        val segments = 28
        for (index in 0 until segments) {
            val first = Math.PI * 2.0 * index / segments
            val second = Math.PI * 2.0 * (index + 1) / segments
            points.add((centerX + cos(first) * radius).toFloat())
            points.add(Table.BALL_Y.toFloat())
            points.add((centerZ + sin(first) * radius).toFloat())
            points.add((centerX + cos(second) * radius).toFloat())
            points.add(Table.BALL_Y.toFloat())
            points.add((centerZ + sin(second) * radius).toFloat())
        }
    }

    private fun drawPrediction() {
        val key = predictionKey()
        if (key != lastPredictionKey) {
            lastPredictionKey = key
            var snapshot: com.ldmaster.sinuca.game.WorldSnapshot
            var angle: Double
            synchronized(world) {
                snapshot = world.snapshot()
                angle = world.aimAngle
            }
            predictor.request(
                key,
                snapshot,
                angle,
                shotPower.toDouble(),
                strikeX.toDouble(),
                strikeY.toDouble()
            )
        }
        val prediction = predictor.latest ?: return
        if (prediction.cuePath.isNotEmpty()) {
            drawLineVertices(prediction.cuePath, 1f, 1f, 1f, 0.45f, 2f, GLES30.GL_LINE_STRIP)
        }
        if (prediction.targetPath.isNotEmpty()) {
            drawLineVertices(
                prediction.targetPath,
                0.45f, 0.95f, 0.85f, 0.70f,
                2.5f, GLES30.GL_LINE_STRIP
            )
        }
    }

    private fun predictionKey(): Long {
        var key = 17L
        key = key * 31 + world.aimAngle.toBits()
        key = key * 31 + shotPower.toBits()
        key = key * 31 + strikeX.toBits()
        key = key * 31 + strikeY.toBits()
        for (ball in world.balls) {
            key = key * 31 + (ball.x * 10000.0).toLong()
            key = key * 31 + (ball.z * 10000.0).toLong()
            key = key * 31 + (if (ball.active) 1 else 0)
        }
        return key
    }

    private fun drawLineVertices(
        points: FloatArray,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        width: Float,
        mode: Int
    ) {
        if (points.size < 6) return
        if (lineProgram == 0) return
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, viewProjection, 0)
        GLES30.glUniform4f(uLineColor, red, green, blue, alpha)
        // The overlay is redrawn every frame while aiming: reuse one direct
        // buffer instead of allocating a few hundred KB per frame.
        var buffer = lineVertices
        if (buffer == null || buffer.capacity() < points.size) {
            buffer = ByteBuffer.allocateDirect(points.size * 4 * 2)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            lineVertices = buffer
        }
        buffer.clear()
        buffer.put(points).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineBuffer)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            points.size * 4,
            buffer,
            GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glLineWidth(width)
        GLES30.glDrawArrays(mode, 0, points.size / 3)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Shot pose of the cue: the bridge hand sits on the rail point, the grip
     * hand 27 cm behind it, the butt 20 cm behind the grip, and the tip just
     * outside the cue ball. The cue travels with the strike clip so the
     * pull-back and follow-through read on screen.
     */
    private fun shotCueEnds(char: Character) {
        if (!char.active) return
        val cue = world.cueBall()
        val cueX = if (cueFrozen) frozenCueX.toDouble() else cue.x
        val cueZ = if (cueFrozen) frozenCueZ.toDouble() else cue.z
        val hand = cueGrip(
            cueX,
            cueZ,
            if (cueFrozen) frozenAim else world.aimAngle
        )
        val toBallX = cueX - hand[0]
        val toBallZ = cueZ - hand[1]
        val reach = hypot(toBallX, toBallZ).coerceAtLeast(0.1)
        val ux = (toBallX / reach).toFloat()
        val uz = (toBallZ / reach).toFloat()

        var pull = 0f
        val model = char.model
        if (model != null && model.currentClip == "strike") {
            val progress = model.clipProgress
            pull = when {
                progress < 0.22f -> 0.16f * (progress / 0.22f)
                progress < 0.55f -> 0.16f - 0.22f * ((progress - 0.22f) / 0.33f)
                else -> 0f
            }
        }
        val gripX = hand[0] - ux * (BRIDGE_FORWARD - GRIP_FORWARD) - ux * pull
        val gripZ = hand[1] - uz * (BRIDGE_FORWARD - GRIP_FORWARD) - uz * pull
        char.shotButt[0] = gripX - ux * 0.20f
        char.shotButt[1] = (Table.RAIL_TOP + 0.06).toFloat()
        char.shotButt[2] = gripZ - uz * 0.20f
        val margin = Table.BALL_RADIUS.toFloat() + 0.003f
        char.shotTip[0] = (cueX - ux * margin).toFloat()
        char.shotTip[1] = Table.BALL_Y.toFloat()
        char.shotTip[2] = (cueZ - uz * margin).toFloat()
    }

    /**
     * Carry pose: the cue stands upright in the right hand. The hand point is
     * taken from the idle clip, so the palm sits on the shaft, and the cue
     * leans slightly outward.
     */
    private fun restCueEnds(char: Character, out: FloatArray) {
        val yaw = char.spot.yaw.toDouble()
        val sinYaw = sin(yaw).toFloat()
        val cosYaw = cos(yaw).toFloat()
        val leanX = CARRY_LEAN
        val norm = sqrt(leanX * leanX + 1f)
        val axisX = leanX / norm
        val axisY = 1f / norm
        val along = CARRY_HAND_Y / axisY
        val baseX = CARRY_HAND_X - axisX * along
        val baseZ = CARRY_HAND_Z
        val length = 2f * cueHalfLength
        val tipX = baseX + axisX * length
        val tipY = axisY * length
        out[0] = char.spot.pos[0] + baseX * cosYaw + baseZ * sinYaw
        out[1] = 0f
        out[2] = char.spot.pos[1] - baseX * sinYaw + baseZ * cosYaw
        out[3] = char.spot.pos[0] + tipX * cosYaw + baseZ * sinYaw
        out[4] = tipY
        out[5] = char.spot.pos[1] - tipX * sinYaw + baseZ * cosYaw
    }

    /**
     * Two-phase transition. The cue first rises from the carry pose to a ready
     * pose directly above the shot line, then drops onto the ball. A straight
     * lerp between the two poses sweeps the shaft through the rail near the
     * corner, so the ready waypoint keeps it `CUE_READY_LIFT` above the wood
     * until it is already over the cloth.
     */
    private fun cueMatrix(char: Character, model: FloatArray) {
        restCueEnds(char, restCue)
        val mix = char.cueMix.coerceIn(0f, 1f)
        val butt = char.shotButt
        val tip = char.shotTip
        val readyButtY = butt[1] + CUE_READY_LIFT
        val readyTipY = tip[1] + CUE_READY_LIFT
        if (mix <= 0.5f) {
            val t = mix * 2f
            placeCue(
                model,
                lerp(restCue[0], butt[0], t),
                lerp(restCue[1], readyButtY, t),
                lerp(restCue[2], butt[2], t),
                lerp(restCue[3], tip[0], t),
                lerp(restCue[4], readyTipY, t),
                lerp(restCue[5], tip[2], t)
            )
        } else {
            val t = (mix - 0.5f) * 2f
            placeCue(
                model,
                butt[0],
                lerp(readyButtY, butt[1], t),
                butt[2],
                tip[0],
                lerp(readyTipY, tip[1], t),
                tip[2]
            )
        }
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private fun placeCue(
        model: FloatArray,
        buttX: Float,
        buttY: Float,
        buttZ: Float,
        tipX: Float,
        tipY: Float,
        tipZ: Float
    ) {
        val dirX = tipX - buttX
        val dirY = tipY - buttY
        val dirZ = tipZ - buttZ
        val span = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ).coerceAtLeast(0.05f)
        val scale = (span / (2f * cueHalfLength)).coerceIn(0.7f, 1.8f)
        val theta = Math.atan2(-dirZ.toDouble(), dirX.toDouble()).toFloat()
        val pitch = Math.asin((dirY / span).toDouble().coerceIn(-1.0, 1.0)).toFloat()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(
            model, 0,
            buttX + dirX * 0.5f,
            buttY + dirY * 0.5f,
            buttZ + dirZ * 0.5f
        )
        Matrix.rotateM(model, 0, Math.toDegrees(theta.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(model, 0, Math.toDegrees(pitch.toDouble()).toFloat(), 0f, 0f, 1f)
        // The cue mesh points its tip at local -X, so turn it around: otherwise
        // the blue tip would ride at the butt and the wrap would touch the ball.
        Matrix.rotateM(model, 0, 180f, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, scale, 1f, 1f)
    }

    private fun railDistance(x: Double, z: Double, dx: Double, dz: Double): Double {
        var distance = Double.MAX_VALUE
        if (dx > 1e-6) distance = minOf(distance, (Table.HALF_LENGTH - x) / dx)
        else if (dx < -1e-6) distance = minOf(distance, (-Table.HALF_LENGTH - x) / dx)
        if (dz > 1e-6) distance = minOf(distance, (Table.HALF_WIDTH - z) / dz)
        else if (dz < -1e-6) distance = minOf(distance, (-Table.HALF_WIDTH - z) / dz)
        return distance.coerceAtLeast(0.0)
    }

    private fun drawMesh(
        mesh: GpuMesh?,
        mvp: FloatArray,
        model: FloatArray,
        useVertexColor: Boolean,
        color: FloatArray,
        textureId: Int = 0,
        useTexture: Boolean = false,
        useLiteral: Boolean = false,
        usePattern: Boolean = false,
        detailId: Int = 0
    ) {
        if (mesh == null) return
        if (sceneProgram == 0) {
            drawFlat(mesh, mvp, color)
            return
        }
        GLES30.glUseProgram(sceneProgram)
        GLES30.glUniform1f(uSkinned, 0f)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniform1f(uUseColor, if (useVertexColor) 0f else 1f)
        GLES30.glUniform1f(uUseTexture, if (useTexture) 1f else 0f)
        GLES30.glUniform1f(uUseLiteral, if (useLiteral) 1f else 0f)
        GLES30.glUniform1f(uUsePattern, if (usePattern) 1f else 0f)
        GLES30.glUniform1f(uStyle, textureStyle.ordinal.toFloat())
        GLES30.glUniform1f(uDetailScale, detailScale())
        GLES30.glUniform1f(uDetailBase, detailBase() * styleAtlasWeight())
        GLES30.glUniform3f(uColor, color[0], color[1], color[2])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (useTexture) textureId else 0)
        GLES30.glUniform1i(uTexture, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, detailId)
        GLES30.glUniform1i(uDetailMap, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        mesh.draw()
    }

    private fun drawFlat(mesh: GpuMesh, mvp: FloatArray, color: FloatArray) {
        if (lineProgram == 0) return
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
        GLES30.glUniform4f(uLineColor, color[0], color[1], color[2], 1f)
        mesh.draw()
    }

    private fun presentFallback(texture: Int) {
        if (copyProgram == 0 || copyQuad == 0) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(copyProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uCopyImage, 0)
        Gl.drawQuad(copyQuad)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /**
     * How much of the look comes from the literal detail atlas. Mobile Atlas
     * leans on it, Procedural Infinite ignores it, and the other styles keep it
     * as a light roughness/occlusion supplement.
     */
    private fun styleAtlasWeight(): Float = when (textureStyle) {
        TableTexture.ATLAS -> 1.0f
        TableTexture.PROCEDURAL -> 0.0f
        else -> 0.55f
    }

    private fun detailScale(): Float = when (textureQuality) {
        TextureQuality.DETAILED_540, TextureQuality.DETAILED_720,
        TextureQuality.DETAILED_1080 -> 1.0f
        TextureQuality.DETAILED_2K -> 1.35f
        TextureQuality.LITERAL -> 1.6f
        TextureQuality.PROCEDURAL -> 1.0f
    }

    private fun detailBase(): Float = when (textureQuality) {
        TextureQuality.DETAILED_540 -> 0.45f
        TextureQuality.DETAILED_720 -> 0.60f
        TextureQuality.DETAILED_1080 -> 0.80f
        TextureQuality.DETAILED_2K -> 1.0f
        TextureQuality.LITERAL -> 1.15f
        TextureQuality.PROCEDURAL -> 0f
    }

    fun rotateAim(deltaDp: Float) {
        synchronized(world) { world.aimAngle += deltaDp * AIM_SENSITIVITY_DP }
    }

    fun release() {
        predictor.release()
    }

    fun setShotParams(power: Float, strikeX: Float, strikeY: Float) {
        shotPower = power.coerceIn(0f, 1f)
        this.strikeX = strikeX.coerceIn(-1f, 1f)
        this.strikeY = strikeY.coerceIn(-1f, 1f)
        lastPredictionKey = Long.MIN_VALUE
    }

    private fun ballPaintColor(number: Int): Int = when (number) {
        0 -> Color.rgb(247, 247, 242)
        1, 9 -> Color.rgb(232, 178, 26)
        2, 10 -> Color.rgb(30, 79, 168)
        3, 11 -> Color.rgb(192, 40, 30)
        4, 12 -> Color.rgb(90, 42, 122)
        5, 13 -> Color.rgb(224, 106, 18)
        6, 14 -> Color.rgb(15, 107, 52)
        7, 15 -> Color.rgb(122, 28, 28)
        else -> Color.rgb(20, 20, 20)
    }

    private fun loadDetailTexture(): Int {
        val suffix = when (textureQuality) {
            TextureQuality.DETAILED_540 -> "540"
            TextureQuality.DETAILED_720 -> "720"
            TextureQuality.DETAILED_1080 -> "1080"
            TextureQuality.DETAILED_2K -> "2k"
            else -> "4k"
        }
        val name = "textures/detail_${textureStyle.key}_$suffix.jpg"
        return try {
            context.assets.open(name).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream) ?: return 0
                val max = IntArray(1)
                GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
                if (bitmap.width > max[0] || bitmap.height > max[0]) {
                    Log.w(TAG, "$name ${bitmap.width}px exceeds GL max ${max[0]}px")
                    bitmap.recycle()
                    return 0
                }
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
                if (anisotropy > 1f) {
                    GLES30.glTexParameterf(
                        GLES30.GL_TEXTURE_2D,
                        TEXTURE_MAX_ANISOTROPY,
                        anisotropy
                    )
                }
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                    bitmap.width, bitmap.height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuffer(bitmap)
                )
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                Log.i(TAG, "loaded $name ${bitmap.width}x${bitmap.height}")
                bitmap.recycle()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                ids[0]
            }
        } catch (error: Exception) {
            Log.e(TAG, "failed to load $name", error)
            0
        }
    }

    private fun pixelBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(buffer)
        buffer.position(0)
        return buffer
    }

    private fun createBallTexture(number: Int): Int {
        // 512x256 is still far more than a ball ever covers on screen and cuts
        // the 16 ball textures from ~43 MB to ~11 MB of VRAM on Adreno.
        val width = 512
        val height = 256
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = ballPaintColor(number)

        if (number == 0) {
            canvas.drawColor(Color.WHITE)
            paint.color = Color.rgb(214, 44, 44)
            canvas.drawCircle(width * 0.5f, height * 0.5f, height * 0.028f, paint)
        } else if (number in 9..15) {
            canvas.drawColor(Color.WHITE)
            paint.color = base
            canvas.drawRect(0f, height * 0.26f, width.toFloat(), height * 0.74f, paint)
        } else {
            canvas.drawColor(base)
        }

        if (number != 0) {
            val patchCenterY = height * 0.50f
            val patchRadius = height * 0.095f
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            for (centerX in floatArrayOf(width * 0.25f, width * 0.75f)) {
                canvas.drawCircle(centerX, patchCenterY, patchRadius, paint)
            }
            paint.color = Color.BLACK
            paint.textSize = height * 0.120f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            for (centerX in floatArrayOf(width * 0.25f, width * 0.75f)) {
                canvas.drawText(
                    number.toString(),
                    centerX,
                    patchCenterY + height * 0.038f,
                    paint
                )
            }
        }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (anisotropy > 1f) {
            GLES30.glTexParameterf(
                GLES30.GL_TEXTURE_2D,
                TEXTURE_MAX_ANISOTROPY,
                anisotropy
            )
        }
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, SRGB8_ALPHA8,
            width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuffer(bitmap)
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        bitmap.recycle()
        return ids[0]
    }

    private fun sphere(radius: Float, segments: Int = 48, rings: Int = 32): MeshData {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val colors = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val materials = ArrayList<Float>()
        val indices = ArrayList<Int>()
        for (ring in 0..rings) {
            val theta = Math.PI * ring / rings
            val y = cos(theta)
            val ringRadius = sin(theta)
            for (segment in 0..segments) {
                val phi = 2.0 * Math.PI * segment / segments
                val x = ringRadius * cos(phi)
                val z = ringRadius * sin(phi)
                positions.add((x * radius).toFloat())
                positions.add((y * radius).toFloat())
                positions.add((z * radius).toFloat())
                normals.add(x.toFloat())
                normals.add(y.toFloat())
                normals.add(z.toFloat())
                colors.add(1f)
                colors.add(1f)
                colors.add(1f)
                materials.add(0f)
                materials.add(0.12f)
                materials.add(TableSurface.CERAMIC)
                uvs.add(1f - segment.toFloat() / segments)
                uvs.add(1f - ring.toFloat() / rings)
            }
        }
        for (ring in 0 until rings) {
            for (segment in 0 until segments) {
                val a = ring * (segments + 1) + segment
                val b = a + segments + 1
                indices.add(a)
                indices.add(b)
                indices.add(a + 1)
                indices.add(a + 1)
                indices.add(b)
                indices.add(b + 1)
            }
        }
        return MeshData(
            positions.toFloatArray(),
            normals.toFloatArray(),
            colors.toFloatArray(),
            indices.toIntArray(),
            uvs.toFloatArray(),
            materials.toFloatArray()
        )
    }

    private fun backdrop(): MeshData {
        val width = 20f
        val height = 7f
        val z = -5.1f
        return quadMesh(
            -width, 0f, z,
            width, 0f, z,
            width, height, z,
            -width, height, z,
            normal = floatArrayOf(0f, 0f, 1f),
            color = floatArrayOf(0.020f, 0.026f, 0.038f),
            surfaceClass = TableSurface.STONE,
            roughness = 0.92f
        )
    }

    private fun floor(): MeshData {
        val size = 5.6f
        return quadMesh(
            -size, 0f, -size,
            size, 0f, -size,
            size, 0f, size,
            -size, 0f, size,
            normal = floatArrayOf(0f, 1f, 0f),
            color = floatArrayOf(0.032f, 0.038f, 0.050f),
            surfaceClass = TableSurface.STONE,
            roughness = 0.86f
        )
    }

    private fun quadMesh(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        normal: FloatArray,
        color: FloatArray,
        surfaceClass: Float,
        roughness: Float
    ): MeshData {
        val colors = FloatArray(12) {
            color[it % 3]
        }
        val materials = FloatArray(12) {
            when (it % 3) {
                0 -> 0f
                1 -> roughness
                else -> surfaceClass
            }
        }
        return MeshData(
            floatArrayOf(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3),
            floatArrayOf(
                normal[0], normal[1], normal[2],
                normal[0], normal[1], normal[2],
                normal[0], normal[1], normal[2],
                normal[0], normal[1], normal[2]
            ),
            colors,
            intArrayOf(0, 1, 2, 0, 2, 3),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
            materials
        )
    }

    private class GpuMesh(mesh: MeshData) {
        private val vertexBuffer: IntArray
        private val indexBuffer: IntArray
        private val count: Int

        init {
            val stride = 14
            val vertexData = FloatArray(mesh.vertexCount * stride)
            var at = 0
            for (i in 0 until mesh.vertexCount) {
                vertexData[at++] = mesh.positions[i * 3]
                vertexData[at++] = mesh.positions[i * 3 + 1]
                vertexData[at++] = mesh.positions[i * 3 + 2]
                vertexData[at++] = mesh.normals[i * 3]
                vertexData[at++] = mesh.normals[i * 3 + 1]
                vertexData[at++] = mesh.normals[i * 3 + 2]
                vertexData[at++] = mesh.colors[i * 3]
                vertexData[at++] = mesh.colors[i * 3 + 1]
                vertexData[at++] = mesh.colors[i * 3 + 2]
                vertexData[at++] = mesh.uvs[i * 2]
                vertexData[at++] = mesh.uvs[i * 2 + 1]
                vertexData[at++] = mesh.materials[i * 3]
                vertexData[at++] = mesh.materials[i * 3 + 1]
                vertexData[at++] = mesh.materials[i * 3 + 2]
            }
            val vertices = ByteBuffer.allocateDirect(vertexData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            vertices.put(vertexData).position(0)
            val indices = ByteBuffer.allocateDirect(mesh.indices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer()
            indices.put(mesh.indices).position(0)

            vertexBuffer = IntArray(1)
            indexBuffer = IntArray(1)
            GLES30.glGenBuffers(1, vertexBuffer, 0)
            GLES30.glGenBuffers(1, indexBuffer, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer[0])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                vertexData.size * 4,
                vertices,
                GLES30.GL_STATIC_DRAW
            )
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer[0])
            GLES30.glBufferData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER,
                mesh.indices.size * 4,
                indices,
                GLES30.GL_STATIC_DRAW
            )
            count = mesh.indices.size
        }

        fun draw() {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer[0])
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer[0])
            val stride = 14 * 4
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 6 * 4)
            GLES30.glEnableVertexAttribArray(3)
            GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, stride, 9 * 4)
            GLES30.glEnableVertexAttribArray(4)
            GLES30.glVertexAttribPointer(4, 3, GLES30.GL_FLOAT, false, stride, 11 * 4)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_INT, 0)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glDisableVertexAttribArray(2)
            GLES30.glDisableVertexAttribArray(3)
            GLES30.glDisableVertexAttribArray(4)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        }
    }

    companion object {
        private const val TAG = "GameRenderer"
        private const val AIM_SENSITIVITY_DP = 0.0032f
        private const val CUE_RADIUS = 0.016
        /**
         * Local forward offsets of the two hands in the aim clip, measured from
         * the model origin. Aligning the bridge hand (0.66) with the rail keeps
         * it out of the playing area; the grip hand sits 0.27 behind it.
         */
        private const val BRIDGE_FORWARD = 0.66f
        private const val GRIP_FORWARD = 0.39f
        /** The stance is shifted half a hand to the right so both palms sit on the cue line. */
        private const val STANCE_SIDE = 0.05f
        /** Seconds for the cue to travel between the carry and shot poses. */
        private const val CUE_SWAP_SECONDS = 0.5f
        /** Clearance above the shot line while the cue swings in over the rail. */
        private const val CUE_READY_LIFT = 0.10f
        /** Walk ring outside the cushions, in metres beyond the playing edge. */
        private const val RING_LENGTH = 1.35f
        private const val RING_WIDTH = 1.20f
        /** The waiting player keeps this much distance from the shooter. */
        private const val SPECTATOR_CLEARANCE = 0.95f
        /** Right palm in the idle clip, used to hang the carried cue in the hand. */
        private const val CARRY_HAND_X = 0.232f
        private const val CARRY_HAND_Y = 0.905f
        private const val CARRY_HAND_Z = 0.452f
        /** Outward lean of the carried cue, as a fraction of its vertical rise. */
        private const val CARRY_LEAN = 0.12f
        private const val TEXTURE_MAX_ANISOTROPY = 0x84FE
        private const val MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF
        private const val SRGB8_ALPHA8 = 0x8C43

        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        private val WHITE = floatArrayOf(1f, 1f, 1f)

        /** Blender KEY at (2.8, -2.8, 5.5) in Z-up, converted to glTF Y-up. */
        private val LIGHT_DIRECTION: FloatArray = run {
            val x = 2.8f
            val y = 5.5f
            val z = 2.8f
            val length = sqrt(x * x + y * y + z * z)
            floatArrayOf(x / length, y / length, z / length)
        }
    }
}
